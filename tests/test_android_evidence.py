import hashlib
import json
import shutil
from pathlib import Path

import pytest

from scripts.export_android_evidence import export


def test_android_evidence_is_pinned_and_rejects_changed_comparison(
    tmp_path: Path,
) -> None:
    root = Path(__file__).resolve().parents[1]
    registry = json.loads((root / "artifacts/registry.json").read_text())
    metadata = registry["models"][registry["active_model_id"]]["metadata"]
    files = [
        "artifacts/registry.json",
        "artifacts/" + metadata,
        registry["comparison"],
        "android/app/src/main/assets/english_char_svm.json",
    ]
    for name in files:
        target = tmp_path / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(root / name, target)
    export(tmp_path)
    asset = tmp_path / "android/app/src/main/assets/model_evidence.json"
    evidence = json.loads(asset.read_text())
    assert (
        evidence["metrics"]
        == json.loads((root / registry["comparison"]).read_text())["reports"][
            registry["active_model_id"]
        ]["metrics"]
    )
    assert (
        hashlib.sha256(asset.read_bytes()).hexdigest()
        == asset.parent.parent.joinpath("evidence.sha256").read_text().strip()
    )
    comparison = tmp_path / registry["comparison"]
    comparison.write_text(comparison.read_text() + " ")
    with pytest.raises(ValueError, match="checksum"):
        export(tmp_path)
