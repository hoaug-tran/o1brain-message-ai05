import json
from pathlib import Path

import pytest

from scripts.export_android_evidence import export


def test_expanded_android_export_rejects_wrong_model_before_writing(
    monkeypatch, tmp_path
):
    import scripts.export_android_evidence as module

    class Registry:
        ready = True
        active = "english_char_svm"
        metadata = {
            "english_char_svm": {
                "artifact_sha256": "expected",
                "version": "current",
                "threshold": 0,
            }
        }

    monkeypatch.setattr(module, "ModelRegistry", lambda path: Registry())
    asset = tmp_path / "android/app/src/main/assets/english_char_svm.json"
    asset.parent.mkdir(parents=True)
    asset.write_text(
        json.dumps(
            {
                "source_artifact_sha256": "wrong",
                "model_version": "current",
                "threshold": 0,
            }
        )
    )
    with pytest.raises(ValueError, match="differ"):
        export(tmp_path)
    assert not asset.with_name("model_evidence.json").exists()


def test_packaged_android_evidence_matches_expanded_artifact():
    root = Path(__file__).resolve().parents[1]
    mobile = json.loads((root / "artifacts/mobile.json").read_text())
    asset = json.loads(
        (root / "android/app/src/main/assets/english_char_svm.json").read_text()
    )
    evidence = json.loads(
        (root / "android/app/src/main/assets/model_evidence.json").read_text()
    )
    assert asset["source_artifact_sha256"] == mobile["model_sha256"]
    assert (
        asset["model_version"] == evidence["model_version"] == Path(mobile["run"]).name
    )
    assert evidence["artifact_sha256"] == asset["source_artifact_sha256"]
    assert asset["supported_languages"] == ["en", "vi"]
    assert 0 <= asset["warning_threshold"] < asset["automatic_threshold"]
