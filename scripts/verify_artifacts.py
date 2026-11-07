import argparse
import json
from pathlib import Path

from ai05.infrastructure.models import ModelRegistry
from ml.common import sha256
from ml.datasets import load_btc, split_btc


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Verify registry hashes and predictions against recorded development IDs."
    )
    parser.add_argument(
        "--registry", type=Path, default=Path("artifacts/registry.json")
    )
    parser.add_argument("--data", type=Path, default=Path("data/raw/SMSSpamCollection"))
    args = parser.parse_args()
    registry = ModelRegistry(args.registry)
    if not registry.ready:
        raise SystemExit("Artifact verification failed: registry unavailable")
    if args.data.exists():
        if any(
            sha256(args.data) != metadata["dataset_sha256"]
            for metadata in registry.metadata.values()
        ):
            raise SystemExit("Dataset source hash differs from training evidence")
        frame, _ = load_btc(args.data)
        _, validation = split_btc(frame)
        result_dir = Path("results/experiments") / registry.comparison["run_id"]
        for model_id, model in registry.models.items():
            recorded = json.loads(
                (result_dir / f"{model_id}_predictions.json").read_text()
            )
            if list(validation.source_id) != [
                item["source_id"] for item in recorded["rows"]
            ]:
                raise SystemExit("Validation identities differ")
            if list(model.predict(validation.text)) != [
                item["prediction"] for item in recorded["rows"]
            ]:
                raise SystemExit("Predictions differ")
        print(
            f"{len(registry.models)} models: checksums and validation predictions verified"
        )
    else:
        print(
            f"{len(registry.models)} model checksums verified; validation replay skipped: data missing"
        )


if __name__ == "__main__":
    main()
