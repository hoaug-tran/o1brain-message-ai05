import json
from pathlib import Path

from ai05.infrastructure.models import ModelRegistry
from ml.common import sha256, write_json
from ml.metrics import evaluate


def export(root: Path) -> None:
    if (root / "artifacts/mobile.json").exists():
        from ml.mobile import export as export_mobile

        export_mobile(
            root, root / json.loads((root / "artifacts/mobile.json").read_text())["run"]
        )
        return
    registry_path = root / "artifacts/registry.json"
    registry = ModelRegistry(registry_path)
    if not registry.ready or registry.active != "english_char_svm":
        raise ValueError("Verified expanded SVM required")
    portable = root / "android/app/src/main/assets/english_char_svm.json"
    model = json.loads(portable.read_text())
    metadata = registry.metadata[registry.active]
    if (
        model["source_artifact_sha256"] != metadata["artifact_sha256"]
        or model["model_version"] != metadata["version"]
        or model["threshold"] != metadata["threshold"]
        or sha256(portable)
        != portable.parent.parent.joinpath("model.sha256").read_text().strip()
    ):
        raise ValueError("Portable model and expanded registry differ")
    record = json.loads(registry_path.read_text())
    report = json.loads(
        (root / Path(record["comparison"]).parent / "report.json").read_text()
    )
    for part, reports in report["evaluations"].items():
        for name, metrics in reports.items():
            if metrics != evaluate(
                report["truth"][part], report["predictions"][part][name]
            ):
                raise ValueError(
                    "Expanded evaluation differs from recorded predictions"
                )
    evidence = {
        **report["evaluations"]["uci_validation"][registry.active],
        "dataset_id": "uci_sms_spam_en_v1",
        "split_id": "btc_dev_80_20_seed2026_v1",
        "evaluation_scope": "reused_UCI_development_validation_of_expanded_model",
        "model_version": model["model_version"],
        "model_id": registry.active,
        "artifact_sha256": metadata["artifact_sha256"],
        "portable_sha256": sha256(portable),
        "training_plan_sha256": metadata["dataset_sha256"],
        "threshold": model["threshold"],
        "score_kind": model["score_kind"],
    }
    write_json(portable.with_name("model_evidence.json"), evidence)
    portable.parent.parent.joinpath("evidence.sha256").write_text(
        sha256(portable.with_name("model_evidence.json")) + "\n"
    )
    external = {
        "model_sha256": sha256(portable),
        "selected_model": registry.active,
        "scope": "expanded_source_held_out_development_evaluation_not_blind_final_test",
        "datasets": {
            "english_external": {"reports": report["evaluations"]["external_holdout"]}
        },
    }
    write_json(portable.with_name("external_evidence.json"), external)
    portable.parent.parent.joinpath("external_evidence.sha256").write_text(
        sha256(portable.with_name("external_evidence.json")) + "\n"
    )
    estimator = registry.models[registry.active]
    for name in ("user-diagnostic-parity.json", "ten-sms-parity.json"):
        path = root / "android/app/src/androidTest/assets" / name
        fixture = json.loads(path.read_text())
        for row in fixture["cases"]:
            row["actual_margin"] = float(estimator.decision_function([row["text"]])[0])
            row["actual_label"] = str(estimator.predict([row["text"]])[0])
        fixture["model_version"] = metadata["version"]
        fixture["scope"] = (
            "Known synthetic diagnostic parity only; not training or selection"
        )
        if all("expected_label" in row for row in fixture["cases"]):
            fixture["evaluation"] = evaluate(
                [r["expected_label"] for r in fixture["cases"]],
                [r["actual_label"] for r in fixture["cases"]],
            )
        write_json(path, fixture)
    write_json(
        root / "android/app/src/androidTest/assets/parity.json",
        json.loads((root / "android/app/src/test/resources/parity.json").read_text()),
    )
    print(
        "Expanded Android model, evaluation hashes and Python parity fixtures verified"
    )


if __name__ == "__main__":
    export(Path.cwd())
