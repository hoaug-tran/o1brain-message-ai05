import argparse
import hashlib
import json
from collections.abc import Sequence
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.model_selection import StratifiedGroupKFold, cross_val_predict
from sklearn.pipeline import make_pipeline
from sklearn.svm import LinearSVC

from ml.common import environment, sha256, write_json
from ml.datasets import load_btc, normalize, split_btc
from ml.expanded import clean_extra, near_groups, read_sources
from ml.metrics import evaluate


def select_threshold(
    truth: Sequence[str] | pd.Series,
    scores: Sequence[float] | np.ndarray,
    max_fpr: float,
    min_precision: float,
) -> float:
    labels, values = np.asarray(truth), np.asarray(scores)
    if set(labels) != {"ham", "spam"} or not np.isfinite(values).all():
        raise ValueError("Finite aligned scores and both classes required")
    if (
        len(labels) != len(values)
        or not 0 <= max_fpr <= 1
        or not 0 <= min_precision <= 1
    ):
        raise ValueError("Invalid threshold selection input")
    best = (0, float(np.max(values)))
    for threshold in np.unique(np.concatenate(([0.0], values[values >= 0]))):
        positive = values > threshold
        tp = int(np.sum(positive & (labels == "spam")))
        fp = int(np.sum(positive & (labels == "ham")))
        if (
            fp / np.sum(labels == "ham") <= max_fpr
            and tp / max(tp + fp, 1) >= min_precision
        ):
            if tp > best[0]:
                best = (tp, float(threshold))
    return best[1]


def run(root: Path, output: Path) -> None:
    if output.exists() or not output.resolve().is_relative_to(
        (root / "results/mobile").resolve()
    ):
        raise ValueError("Choose a new results/mobile run; existing runs are immutable")
    output.mkdir(parents=True)
    frame, _ = load_btc(root / "data/raw/SMSSpamCollection")
    uci_train, uci_validation = split_btc(frame)
    split = json.loads((root / "data/manifests/english_split.json").read_text())
    if (
        sha256(root / "data/raw/SMSSpamCollection") != split["source_sha256"]
        or uci_train.source_id.tolist() != split["train"]
        or uci_validation.source_id.tolist() != split["validation"]
    ):
        raise ValueError("Frozen UCI source or split changed")
    frozen = json.loads((root / "results/expanded/run-20261107/plan.json").read_text())
    rows, _ = read_sources(root)
    extra, _ = clean_extra(rows, set(map(normalize, frame.text)))
    en_train = extra[extra.id.isin(frozen["train_ids"])].copy()
    en_holdout = extra[extra.id.isin(frozen["external_holdout_ids"])].copy()
    en_train = pd.concat(
        [uci_train.rename(columns={"source_id": "id"}).assign(source="uci"), en_train],
        ignore_index=True,
    )[["text", "label", "id", "source"]].assign(language="en")
    en_test = pd.concat(
        [
            uci_validation.rename(columns={"source_id": "id"}).assign(source="uci"),
            en_holdout,
        ],
        ignore_index=True,
    )[["text", "label", "id", "source"]].assign(language="en")
    folder = root / "data/external/vietnamese"
    provenance = json.loads((folder / "SOURCE.json").read_text())
    for row in provenance:
        if sha256(folder / row["path"]) != row["sha256"]:
            raise ValueError("Vietnamese source checksum changed")
    vi = pd.read_csv(folder / "full_dataset.csv").rename(columns={"message": "text"})
    if not set(vi.label) <= {0, 1}:
        raise ValueError("Unexpected Vietnamese label")
    vi["label"] = vi.label.map({0: "ham", 1: "spam"})
    vi = vi[
        vi.text.map(
            lambda x: (
                isinstance(x, str)
                and 0 < len(x.strip()) <= 2000
                and len(x.encode()) <= 6000
            )
        )
    ].copy()
    vi["normalized"] = vi.text.map(normalize)
    conflicts = vi.groupby("normalized").label.nunique()
    vi = (
        vi[~vi.normalized.isin(conflicts[conflicts > 1].index)]
        .drop_duplicates("normalized")
        .reset_index(drop=True)
    )
    sample = pd.read_csv(folder / "sample_300.csv")
    print("Grouping Vietnamese templates", flush=True)
    groups = near_groups(vi.text.tolist() + sample.text.tolist(), threshold=0.85)
    reserved = set(groups[len(vi) :])
    vi = vi.assign(group=groups[: len(vi)])
    excluded = int(vi.group.isin(reserved).sum())
    vi = vi[~vi.group.isin(reserved)].copy()
    vi["id"] = vi.normalized.map(lambda text: hashlib.sha256(text.encode()).hexdigest())
    vi = vi.assign(source="vietnamese_sms_dataset", language="vi")
    fit, held = next(
        StratifiedGroupKFold(5, shuffle=True, random_state=2026).split(
            vi.text, vi.label, vi.group
        )
    )
    vi_train, vi_test = vi.iloc[fit], vi.iloc[held]
    train = pd.concat([en_train, vi_train], ignore_index=True)
    test = pd.concat([en_test, vi_test], ignore_index=True)
    print("Grouping training templates", len(train), flush=True)
    train_groups = near_groups(train.text.tolist(), threshold=0.9)
    model = make_pipeline(
        TfidfVectorizer(
            analyzer="char", ngram_range=(3, 5), sublinear_tf=True, max_features=180_000
        ),
        LinearSVC(C=1, class_weight="balanced", random_state=2026),
    )
    print("Three-fold out-of-fold scoring", flush=True)
    folds = list(
        StratifiedGroupKFold(3, shuffle=True, random_state=2026).split(
            train.text, train.label, train_groups
        )
    )
    oof = cross_val_predict(
        model, train.text, train.label, cv=folds, method="decision_function", n_jobs=1
    )
    warning = max(
        select_threshold(
            train.label[train.language == language],
            oof[train.language == language],
            0.02,
            0.9,
        )
        for language in ("en", "vi")
    )
    automatic = max(
        warning,
        *(
            select_threshold(
                train.label[train.language == language],
                oof[train.language == language],
                0.005,
                0.98,
            )
            for language in ("en", "vi")
        ),
    )
    print("Fitting final bilingual model", flush=True)
    model.fit(train.text, train.label)
    joblib.dump(model, output / "model.joblib")
    previous = joblib.load(
        root / "results/expanded/run-20261107/english_char_svm.joblib"
    )
    scores = model.decision_function(test.text)
    report = {}
    for language in ("en", "vi"):
        mask = test.language.to_numpy() == language
        truth = test.label.to_numpy()[mask].tolist()
        report[language] = {
            "binary": evaluate(
                truth, np.where(scores[mask] > 0, "spam", "ham").tolist()
            ),
            "warning": evaluate(
                truth, np.where(scores[mask] > warning, "spam", "ham").tolist()
            ),
            "automatic": evaluate(
                truth, np.where(scores[mask] > automatic, "spam", "ham").tolist()
            ),
            "previous_english_model": evaluate(
                truth, previous.predict(test.text[mask]).tolist()
            ),
        }
    plan = {
        "version": output.name,
        "environment": environment(),
        "sources": provenance,
        "train_ids": train.id.tolist(),
        "holdout_ids": test.id.tolist(),
        "train_counts": train.groupby(["language", "label"]).size().to_dict(),
        "vietnamese_unlabelled_sample_neighbors_excluded": excluded,
        "threshold_selection": "Training-only 3-fold OOF, constraints applied separately to each language; warning FPR<=2%, precision>=90%; automatic FPR<=0.5%, precision>=98%; maximize recall; held-out results not used for selection",
        "evaluation_status": "Grouped development holdout, not blind; English frozen validation previously inspected; Vietnamese sample_300 has no labels and is not scored",
        "warning_threshold": warning,
        "automatic_threshold": automatic,
        "model_sha256": sha256(output / "model.joblib"),
    }
    plan["train_counts"] = {
        "/".join(k): int(v) for k, v in plan["train_counts"].items()
    }
    write_json(output / "plan.json", plan, immutable=True)
    write_json(
        output / "report.json",
        {
            "evaluations": report,
            "ids": test.id.tolist(),
            "languages": test.language.tolist(),
            "truth": test.label.tolist(),
            "margins": scores.tolist(),
        },
        immutable=True,
    )
    write_json(
        output / "oof.json",
        {
            "ids": train.id.tolist(),
            "truth": train.label.tolist(),
            "margins": oof.tolist(),
            "groups": train_groups.tolist(),
        },
        immutable=True,
    )
    print(
        json.dumps({"thresholds": [warning, automatic], "evaluations": report}),
        flush=True,
    )


def export_policy_fixture(root: Path, report: dict) -> None:
    required = [
        "data/raw/SMSSpamCollection",
        "data/external/vietnamese/full_dataset.csv",
        "data/external/mendeley/PhishNet_Dataset1_original.csv",
        "data/external/india/spam_ham_india.csv",
    ]
    if not all((root / name).is_file() for name in required):
        return
    frame, _ = load_btc(root / required[0])
    texts = {str(row.source_id): row.text for row in frame.itertuples()}
    rows, _ = read_sources(root)
    extra, _ = clean_extra(rows, set(map(normalize, frame.text)))
    texts.update({str(row.id): row.text for row in extra.itertuples()})
    for text in pd.read_csv(root / required[1]).message:
        if (
            isinstance(text, str)
            and 0 < len(text.strip()) <= 2000
            and len(text.encode()) <= 6000
        ):
            texts.setdefault(hashlib.sha256(normalize(text).encode()).hexdigest(), text)
    cases = [
        {
            "id": identity,
            "language": language,
            "truth": truth,
            "margin": margin,
            "text": texts[str(identity)],
        }
        for identity, language, truth, margin in zip(
            report["ids"],
            report["languages"],
            report["truth"],
            report["margins"],
            strict=True,
        )
    ]
    write_json(root / "android/app/build/mobile-policy-input.json", {"cases": cases})


def export(root: Path, output: Path) -> None:
    plan = json.loads((output / "plan.json").read_text())
    report = json.loads((output / "report.json").read_text())
    if sha256(output / "model.joblib") != plan["model_sha256"]:
        raise ValueError("Mobile model checksum mismatch")
    model = joblib.load(output / "model.joblib")
    vectorizer, classifier = model[0], model[1]
    assets = root / "android/app/src/main/assets"
    portable = {
        "format_version": 1,
        "score_kind": "decision_margin",
        "threshold": 0,
        "supported_languages": ["en", "vi"],
        "model_version": plan["version"],
        "model_id": "bilingual_char_svm",
        "labels": ["ham", "spam"],
        "source_artifact_sha256": plan["model_sha256"],
        "dataset_sha256": sha256(output / "plan.json"),
        "split_sha256": sha256(output / "plan.json"),
        "warning_threshold": plan["warning_threshold"],
        "automatic_threshold": plan["automatic_threshold"],
        "vocabulary": vectorizer.get_feature_names_out().tolist(),
        "idf": vectorizer.idf_.tolist(),
        "weights": classifier.coef_[0].tolist(),
        "intercept": float(classifier.intercept_[0]),
    }
    write_json(assets / "english_char_svm.json", portable)
    if (assets / "english_char_svm.json").stat().st_size > 16 * 1024 * 1024:
        raise ValueError("Mobile artifact exceeds runtime limit")
    digest = sha256(assets / "english_char_svm.json")
    for language, reports in report["evaluations"].items():
        indices = [
            i for i, value in enumerate(report["languages"]) if value == language
        ]
        truth = [report["truth"][i] for i in indices]
        for tier, threshold in [
            ("binary", 0),
            ("warning", plan["warning_threshold"]),
            ("automatic", plan["automatic_threshold"]),
        ]:
            actual = evaluate(
                truth,
                [
                    "spam" if report["margins"][i] > threshold else "ham"
                    for i in indices
                ],
            )
            if actual != reports[tier]:
                raise ValueError("Recorded mobile metrics differ from predictions")
    evidence = {
        **evaluate(
            report["truth"],
            [
                "spam" if x > plan["automatic_threshold"] else "ham"
                for x in report["margins"]
            ],
        ),
        "model_version": plan["version"],
        "artifact_sha256": plan["model_sha256"],
        "portable_sha256": digest,
        "threshold": plan["automatic_threshold"],
        "evaluation_scope": plan["evaluation_status"],
    }
    write_json(assets / "model_evidence.json", evidence)
    write_json(
        assets / "external_evidence.json",
        {
            "model_sha256": digest,
            "selected_model": "bilingual_char_svm",
            "datasets": {
                name: {
                    "reports": {
                        "bilingual_char_svm": report["evaluations"][lang]["automatic"]
                    }
                }
                for name, lang in [
                    ("english_external", "en"),
                    ("vietnamese_external", "vi"),
                ]
            },
        },
    )
    for asset, checksum in [
        ("english_char_svm.json", "model"),
        ("model_evidence.json", "evidence"),
        ("external_evidence.json", "external_evidence"),
    ]:
        (assets.parent / (checksum + ".sha256")).write_text(
            sha256(assets / asset) + "\n"
        )
    resource = root / "android/app/src/test/resources/parity.json"
    fixture = json.loads(resource.read_text())
    for row in fixture["fixtures"]:
        row["margin"] = float(model.decision_function([row["text"]])[0])
        row["label"] = str(model.predict([row["text"]])[0])
    fixture["model_version"] = plan["version"]
    write_json(resource, fixture)
    test_assets = root / "android/app/src/androidTest/assets"
    write_json(test_assets / "parity.json", fixture)
    for name in ["user-diagnostic-parity.json", "ten-sms-parity.json"]:
        fixture = json.loads((test_assets / name).read_text())
        for row in fixture["cases"]:
            row["actual_margin"] = float(model.decision_function([row["text"]])[0])
            row["actual_label"] = str(model.predict([row["text"]])[0])
        fixture["model_version"] = plan["version"]
        if all("expected_label" in row for row in fixture["cases"]):
            fixture["evaluation"] = evaluate(
                [r["expected_label"] for r in fixture["cases"]],
                [r["actual_label"] for r in fixture["cases"]],
            )
        write_json(test_assets / name, fixture)
    write_json(
        root / "artifacts/mobile.json",
        {
            "run": output.relative_to(root).as_posix(),
            "model_sha256": plan["model_sha256"],
            "portable_sha256": digest,
        },
    )
    export_policy_fixture(root, report)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--export", action="store_true")
    args = parser.parse_args()
    (export if args.export else run)(Path.cwd(), args.output.resolve())
