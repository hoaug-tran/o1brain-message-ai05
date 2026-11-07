import json
import math
from importlib.metadata import version
from pathlib import Path

import joblib

from ai05.domain import Classification, ModelUnavailable
from ml.common import sha256


def confined(root: Path, relative: str) -> Path:
    target = (root / relative).resolve()
    if not target.is_relative_to(root.resolve()) or Path(relative).is_absolute():
        raise ValueError("Artifact path escapes trusted directory")
    return target


class ModelRegistry:
    def __init__(self, path: Path) -> None:
        self.models: dict = {}
        self.metadata: dict = {}
        self.comparison: dict = {}
        self.active = ""
        self.load_error: str | None = None
        try:
            registry = json.loads(path.read_text(encoding="utf-8"))
            self.active = registry["active_model_id"]
            required = {"english_baseline", registry["active_model_id"]}
            if not required <= set(registry["models"]) or len(required) != 2:
                raise ValueError("Distinct baseline and candidate required")
            for model_id, record in registry["models"].items():
                artifact = confined(path.parent, record["artifact"])
                metadata_path = confined(path.parent, record["metadata"])
                if (
                    sha256(artifact) != record["artifact_sha256"]
                    or sha256(metadata_path) != record["metadata_sha256"]
                ):
                    raise ValueError("Checksum mismatch")
                metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
                if (
                    metadata["model_id"] != model_id
                    or metadata["labels"] != ["ham", "spam"]
                    or metadata["language"] != "en"
                    or metadata["artifact_sha256"] != record["artifact_sha256"]
                ):
                    raise ValueError("Incompatible model metadata")
                for package in ("scikit-learn", "numpy", "joblib"):
                    if metadata["environment"]["packages"][package] != version(package):
                        raise ValueError("Dependency mismatch")
                if metadata["score_kind"] not in {
                    "decision_margin",
                    "uncalibrated_score",
                }:
                    raise ValueError("Unknown score kind")
                model = joblib.load(artifact)
                if list(model.classes_) != ["ham", "spam"]:
                    raise ValueError("Incompatible model labels")
                self.models[model_id] = model
                self.metadata[model_id] = metadata
                self.classify(model_id, "Synthetic startup greeting for a friend")
            comparison = confined(path.parent.parent, registry["comparison"])
            if sha256(comparison) != registry["comparison_sha256"]:
                raise ValueError("Comparison checksum mismatch")
            self.comparison = json.loads(comparison.read_text(encoding="utf-8"))
        except Exception:
            self.models.clear()
            self.metadata.clear()
            self.comparison.clear()
            self.load_error = "MODEL_UNAVAILABLE"

    @property
    def ready(self) -> bool:
        return bool(self.models) and self.active in self.models

    def classify(self, model_id: str, text: str) -> Classification:
        if model_id not in self.models:
            raise ModelUnavailable("Requested model unavailable")
        model = self.models[model_id]
        label = str(model.predict([text])[0])
        kind = self.metadata[model_id]["score_kind"]
        score = (
            float(model.decision_function([text])[0])
            if kind == "decision_margin"
            else float(
                model.predict_proba([text])[0][list(model.classes_).index("spam")]
            )
        )
        threshold = float(self.metadata[model_id]["threshold"])
        expected = "spam" if score > threshold else "ham"
        if (
            label not in {"ham", "spam"}
            or not math.isfinite(score)
            or not math.isfinite(threshold)
            or label != expected
        ):
            raise ModelUnavailable("Invalid classifier output")
        return Classification(label, score, kind)

    def public_info(self, model_id: str) -> dict[str, str]:
        metadata = self.metadata[model_id]
        return {
            "id": model_id,
            "version": metadata["version"],
            "artifact_sha256_prefix": metadata["artifact_sha256"][:12],
            "language": "en",
            "validation_status": "development_validation_only",
        }
