import json
import shutil
from pathlib import Path

from ai05.infrastructure.models import ModelRegistry, confined
from ai05.interfaces.http import ROOT


def test_corrupt_metadata_fails_closed(tmp_path: Path) -> None:
    shutil.copytree(ROOT / "artifacts", tmp_path / "artifacts")
    registry_path = tmp_path / "artifacts/registry.json"
    registry = json.loads(registry_path.read_text())
    record = next(iter(registry["models"].values()))
    (tmp_path / "artifacts" / record["metadata"]).write_text("{}")
    model = ModelRegistry(registry_path)
    assert not model.ready
    assert model.models == {}


def test_path_escape_rejected(tmp_path: Path) -> None:
    import pytest

    with pytest.raises(ValueError):
        confined(tmp_path, "../elsewhere")


def test_new_process_reload_matches_recorded_validation() -> None:
    import subprocess
    import sys

    result = subprocess.run(
        [sys.executable, "scripts/verify_artifacts.py"],
        cwd=ROOT,
        capture_output=True,
        text=True,
    )
    assert result.returncode == 0, result.stdout + result.stderr
    assert (
        "checksums verified" in result.stdout
        or "validation predictions verified" in result.stdout
    )
