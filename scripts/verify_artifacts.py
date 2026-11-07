import argparse
import json
from pathlib import Path

from ai05.infrastructure.models import ModelRegistry
from ml.common import sha256
from ml.datasets import load_btc, normalize, split_btc
from ml.expanded import clean_extra, read_sources
from ml.metrics import evaluate


def verify(root: Path, registry_path: Path) -> None:
    registry = ModelRegistry(registry_path)
    if not registry.ready or set(registry.models) != {
        "english_baseline",
        "english_char_svm",
    }:
        raise ValueError("Exactly two verified expanded models required")
    run = root / Path(json.loads(registry_path.read_text())["comparison"]).parent
    report = json.loads((run / "report.json").read_text())
    for part, predictions in report["predictions"].items():
        for name, values in predictions.items():
            if (
                evaluate(report["truth"][part], values)
                != report["evaluations"][part][name]
            ):
                raise ValueError("Recorded predictions and metrics disagree")
    if any(
        metadata["dataset_sha256"] != sha256(run / "plan.json")
        for metadata in registry.metadata.values()
    ):
        raise ValueError("Training plan hash differs")
    if (root / "data/external/india/spam_ham_india.csv").is_file():
        frame, _ = load_btc(root / "data/raw/SMSSpamCollection")
        _, validation = split_btc(frame)
        rows, _ = read_sources(root)
        extra, _ = clean_extra(rows, set(map(normalize, frame.text)))
        external = extra.set_index("id").loc[report["plan"]["external_holdout_ids"]]
        for part, data in (
            ("external_holdout", external),
            ("uci_validation", validation),
        ):
            if data.label.tolist() != report["truth"][part]:
                raise ValueError("Holdout identities or labels differ")
            for name, model in registry.models.items():
                if (
                    model.predict(data.text).tolist()
                    != report["predictions"][part][name]
                ):
                    raise ValueError("Actual holdout predictions differ")
        print("2 models: checksums and validation predictions verified")
    else:
        print(
            "2 model checksums verified; recorded metrics verified; raw-source replay skipped: data missing"
        )


if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        description="Verify the two expanded demo artifacts and held-out predictions"
    )
    parser.add_argument(
        "--registry", type=Path, default=Path("artifacts/registry.json")
    )
    args = parser.parse_args()
    verify(Path.cwd(), args.registry)
