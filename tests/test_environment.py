import importlib.util
from pathlib import Path


def test_environment_reports_missing_without_creating_files(tmp_path: Path) -> None:
    spec = importlib.util.spec_from_file_location(
        "verify_environment",
        Path(__file__).parents[1] / "scripts/verify_environment.py",
    )
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    result = module.inspect_environment(tmp_path)
    assert result["missing"] == [
        "README.md",
        "uv.lock",
    ]
    assert result["inputs"] == {}
    assert result["dataset_present"] is False
    assert list(tmp_path.iterdir()) == []
