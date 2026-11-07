import argparse
import csv
import hashlib
import json
import unicodedata
from collections import Counter
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
from scipy.sparse import csr_matrix, vstack
from scipy.sparse.csgraph import connected_components
from sklearn.base import clone
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.model_selection import StratifiedGroupKFold
from sklearn.neighbors import NearestNeighbors
from sklearn.pipeline import make_pipeline
from sklearn.svm import LinearSVC

from ai05.domain import InvalidMessage, UnsupportedLanguage, validate_text
from ai05.infrastructure.models import ModelRegistry
from ml.common import environment, sha256, write_json
from ml.datasets import load_btc, normalize, split_btc
from ml.metrics import evaluate


def near_groups(texts: list[str], threshold: float = 0.9) -> np.ndarray:
    if not texts or not 0 < threshold <= 1:
        raise ValueError("Nonempty texts and similarity in (0, 1] required")
    normalized = [normalize(text) for text in texts]
    if not any(len(text) >= 3 for text in normalized):
        return pd.factorize(np.asarray(normalized))[0]
    matrix = TfidfVectorizer(analyzer="char", ngram_range=(3, 5)).fit_transform(
        normalized
    )
    neighbors = NearestNeighbors(metric="cosine", algorithm="brute", n_jobs=1).fit(
        matrix
    )
    graph = vstack(
        [
            neighbors.radius_neighbors_graph(
                matrix[start : start + 256],
                radius=1 - threshold + 1e-12,
                mode="connectivity",
            )
            for start in range(0, len(texts), 256)
        ],
        format="csr",
    )
    first: dict[str, int] = {}
    links = [first.setdefault(text, i) for i, text in enumerate(normalized)]
    graph += csr_matrix(
        (np.ones(len(texts)), (np.arange(len(texts)), links)), shape=graph.shape
    )
    return connected_components(graph, directed=False)[1]


def clean_extra(
    rows: list[dict[str, str]], excluded: set[str]
) -> tuple[pd.DataFrame, dict]:
    grouped: dict[str, dict] = {}
    counts: Counter = Counter()
    for row in rows:
        counts["source_rows"] += 1
        text, label = row["text"], row["label"].strip().lower()
        if label not in {"ham", "spam", "smishing"}:
            raise ValueError("Unexpected external label")
        try:
            validate_text(text, "en")
            if any(
                c.isalpha() and "LATIN" not in unicodedata.name(c, "") for c in text
            ):
                raise ValueError("Non-Latin text outside English corpus scope")
        except (InvalidMessage, UnsupportedLanguage, ValueError):
            counts["invalid_or_non_latin_rows_removed"] += 1
            continue
        key = normalize(text)
        if key in excluded:
            counts["exact_uci_overlap_removed"] += 1
            continue
        if key in grouped:
            counts["duplicate_excess"] += 1
        item = grouped.setdefault(
            key, {"text": text, "labels": set(), "sources": set()}
        )
        item["labels"].add("ham" if label == "ham" else "spam")
        item["sources"].add(row["source"])
    records = []
    for key, item in sorted(grouped.items()):
        if len(item["labels"]) != 1:
            counts["conflicting_groups_removed"] += 1
            continue
        records.append(
            {
                "text": item["text"],
                "label": next(iter(item["labels"])),
                "source": "+".join(sorted(item["sources"])),
                "id": hashlib.sha256(key.encode()).hexdigest(),
            }
        )
    if not records:
        raise ValueError("No eligible external data")
    return pd.DataFrame(records), dict(counts)


def read_sources(root: Path) -> tuple[list[dict[str, str]], dict]:
    rows: list[dict[str, str]] = []
    sources: dict = {}
    paths = [
        ("mendeley", "PhishNet_Dataset1_original.csv", "TEXT", "LABEL"),
        ("india", "spam_ham_india.csv", "Msg", "Label"),
    ]
    for name, filename, text_key, label_key in paths:
        folder = root / "data/external" / name
        provenance = json.loads((folder / "SOURCE.json").read_text())
        path = folder / filename
        expected = (
            provenance["source_sha256"]
            if name == "mendeley"
            else provenance["files"][filename]["sha256"]
        )
        if sha256(path) != expected:
            raise ValueError(f"Source checksum mismatch: {name}")
        sources[name] = provenance
        with path.open(encoding="utf-8-sig", newline="") as stream:
            rows.extend(
                {"text": row[text_key], "label": row[label_key], "source": name}
                for row in csv.DictReader(stream)
            )
    return rows, sources


def run(root: Path, output: Path) -> None:
    root = root.resolve()
    output = (root / output).resolve()
    if not output.is_relative_to(root / "results/expanded"):
        raise ValueError("Expanded output must remain in this clone's results/expanded")
    if output.exists():
        raise ValueError("Output exists; preserve evidence and choose a new run")
    original_root = root / "references/uci"
    registry = ModelRegistry(original_root / "artifacts/registry.json")
    if not registry.ready:
        raise ValueError("Verified UCI reference registry required")
    source = root / "data/raw/SMSSpamCollection"
    split = json.loads((root / "data/manifests/english_split.json").read_text())
    if sha256(source) != split["source_sha256"]:
        raise ValueError("UCI source hash changed")
    frame, _ = load_btc(source)
    train, validation = split_btc(frame)
    if (
        train.source_id.tolist() != split["train"]
        or validation.source_id.tolist() != split["validation"]
    ):
        raise ValueError("Frozen UCI IDs changed")
    rows, sources = read_sources(root)
    extra, cleaning = clean_extra(rows, set(map(normalize, frame.text)))
    print("External unique before near filtering:", len(extra), flush=True)
    vectorizer = registry.models["english_char_svm"][0]
    neighbors = NearestNeighbors(metric="cosine", algorithm="brute").fit(
        vectorizer.transform(frame.text)
    )
    distances = neighbors.kneighbors(vectorizer.transform(extra.text), n_neighbors=1)[
        0
    ][:, 0]
    cleaning["near_uci_removed"] = int((distances <= 0.1).sum())
    extra = extra.iloc[np.flatnonzero(distances > 0.1)].reset_index(drop=True)
    groups = near_groups(extra.text.tolist())
    fitting, held = next(
        StratifiedGroupKFold(5, shuffle=True, random_state=2026).split(
            extra.text, extra.label, groups
        )
    )
    if set(groups[fitting]) & set(groups[held]):
        raise ValueError("External near-duplicate groups cross holdout")
    external_train, holdout = extra.iloc[fitting], extra.iloc[held]
    combined = pd.concat(
        [train[["text", "label"]], external_train[["text", "label"]]], ignore_index=True
    )
    if set(holdout.label) != {"ham", "spam"}:
        raise ValueError("Both external holdout classes required")
    output.mkdir(parents=True)
    run_id = output.name
    plan = {
        "scope": "separate_expanded_demo_not_competition_submission",
        "evaluation_status": "source-held-out development evaluation; Mendeley and UCI previously inspected, not a blind final test",
        "sources": {"uci_sha256": sha256(source), **sources},
        "label_mapping": "ham=ham, spam/smishing=spam; no synthetic examples or UI templates added",
        "cleaning": cleaning,
        "near_filter": "exclude external neighbors of any UCI row at char TF-IDF cosine >=0.9 using frozen UCI vectorizer",
        "grouping": "external connected components at char 3-5 cosine >=0.9; grouping features audit-only, not classifier preprocessing",
        "selection": "fixed before holdout evaluation: NB alpha=0.5; char LinearSVC C=1 balanced; no threshold tuning",
        "counts": {
            "uci_train": len(train),
            "external_train": len(external_train),
            "train_total": len(combined),
            "external_holdout": len(holdout),
            "uci_validation": len(validation),
        },
        "external_train_sources": external_train.source.value_counts().to_dict(),
        "holdout_sources": holdout.source.value_counts().to_dict(),
        "train_ids": train.source_id.tolist() + external_train.id.tolist(),
        "external_holdout_ids": holdout.id.tolist(),
        "uci_validation_ids": validation.source_id.tolist(),
        "environment": environment(),
    }
    write_json(output / "plan.json", plan, immutable=True)
    models = {
        "english_baseline": clone(registry.models["english_baseline"]),
        "english_char_svm": make_pipeline(
            TfidfVectorizer(analyzer="char", ngram_range=(3, 5), sublinear_tf=True),
            LinearSVC(C=1, class_weight="balanced", random_state=2026),
        ),
    }
    evaluations: dict = {"external_holdout": {}, "uci_validation": {}}
    predictions: dict = {"external_holdout": {}, "uci_validation": {}}
    for name, model in models.items():
        print("Fitting", name, "on", len(combined), "messages", flush=True)
        model.fit(combined.text, combined.label)
        artifact = output / (name + ".joblib")
        joblib.dump(model, artifact)
        for part_name, part in (
            ("external_holdout", holdout),
            ("uci_validation", validation),
        ):
            predicted = model.predict(part.text).tolist()
            if joblib.load(artifact).predict(part.text).tolist() != predicted:
                raise ValueError("Saved artifact prediction mismatch")
            predictions[part_name][name] = predicted
            evaluations[part_name][name] = evaluate(part.label.tolist(), predicted)
        metadata = {
            "model_id": name,
            "labels": ["ham", "spam"],
            "language": "en",
            "version": run_id,
            "artifact_sha256": sha256(artifact),
            "environment": environment(),
            "score_kind": "decision_margin"
            if name == "english_char_svm"
            else "uncalibrated_score",
            "threshold": 0 if name == "english_char_svm" else 0.5,
            "dataset_sha256": sha256(output / "plan.json"),
            "split_sha256": sha256(output / "plan.json"),
            "test_protocol_status": "expanded_grouped_development_holdout",
            "parameters": {"C": 1, "class_weight": "balanced"}
            if name == "english_char_svm"
            else {"alpha": 0.5},
        }
        metadata_path = output / (name + "_metadata.json")
        write_json(metadata_path, metadata, immutable=True)
    reference = {
        name: evaluate(holdout.label.tolist(), model.predict(holdout.text).tolist())
        for name, model in registry.models.items()
        if name in models
    }
    report = {
        "plan": plan,
        "evaluations": evaluations,
        "truth": {
            "external_holdout": holdout.label.tolist(),
            "uci_validation": validation.label.tolist(),
        },
        "predictions": predictions,
        "uci_only_reference_on_same_external_holdout": reference,
    }
    write_json(output / "report.json", report, immutable=True)
    comparison = {
        "run_id": run_id,
        "dataset_id": "uci_mendeley_india_english",
        "split_id": "external_grouped_holdout_seed2026",
        "reports": {
            name: {**value, "evaluation_scope": plan["evaluation_status"]}
            for name, value in evaluations["external_holdout"].items()
        },
    }
    write_json(output / "comparison.json", comparison, immutable=True)
    print(
        json.dumps(
            {
                "counts": plan["counts"],
                "evaluations": evaluations,
                "reference": reference,
            },
            indent=2,
        ),
        flush=True,
    )


if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        description="Train only baseline NB and optimized Linear SVM in the separate expanded demo project"
    )
    parser.add_argument(
        "--output", type=Path, default=Path("results/expanded/run-20261107")
    )
    args = parser.parse_args()
    run(Path.cwd(), args.output)
