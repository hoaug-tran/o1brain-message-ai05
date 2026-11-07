import argparse
import hashlib
import json
from pathlib import Path


def export(root: Path) -> None:
    registry = json.loads((root / "artifacts/registry.json").read_text())
    portable = root / "android/app/src/main/assets/english_char_svm.json"
    model = json.loads(portable.read_text())
    entry = registry["models"][registry["active_model_id"]]
    metadata_path = root / "artifacts" / entry["metadata"]
    metadata = json.loads(metadata_path.read_text())
    comparison_path = root / registry["comparison"]
    if (
        hashlib.sha256(comparison_path.read_bytes()).hexdigest()
        != registry["comparison_sha256"]
    ):
        raise ValueError("Comparison checksum mismatch")
    comparison = json.loads(comparison_path.read_text())
    metrics = comparison["reports"][registry["active_model_id"]]
    if not (
        hashlib.sha256(metadata_path.read_bytes()).hexdigest()
        == entry["metadata_sha256"]
        and metadata["version"] == model["model_version"] == metrics["experiment_id"]
        and metadata["artifact_sha256"]
        == model["source_artifact_sha256"]
        == metrics["artifact_sha256"]
        and metadata["split_sha256"]
        == model["split_sha256"]
        == metrics["split_manifest_sha256"]
        and metrics["evaluation_scope"].startswith("development_validation;")
    ):
        raise ValueError("Android model and evaluation provenance differ")
    evidence = {
        key: metrics[key]
        for key in (
            "dataset_id",
            "metrics",
            "confusion",
            "support",
            "split_id",
            "evaluation_scope",
            "dataset_sha256",
            "split_manifest_sha256",
            "artifact_sha256",
        )
    }
    evidence.update(
        model_version=model["model_version"],
        threshold=model["threshold"],
        score_kind=model["score_kind"],
        model_id=model["model_id"],
        portable_sha256=hashlib.sha256(portable.read_bytes()).hexdigest(),
    )
    out = portable.with_name("model_evidence.json")
    out.write_text(
        json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    portable.parent.parent.joinpath("evidence.sha256").write_text(
        hashlib.sha256(out.read_bytes()).hexdigest() + "\n"
    )
    print(
        "Verified comparison checksum, model identity, source metadata, split and development validation scope"
    )


if __name__ == "__main__":
    argparse.ArgumentParser(
        description="Export pinned development metrics for the exact Android model, without training."
    ).parse_args()
    export(Path("."))
