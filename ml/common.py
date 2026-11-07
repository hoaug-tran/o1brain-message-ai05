import hashlib
import json
import platform
import sys
from importlib.metadata import version
from pathlib import Path


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path: Path, value: object, *, immutable: bool = False) -> None:
    payload = (
        json.dumps(value, indent=2, sort_keys=True, ensure_ascii=False, allow_nan=False)
        + "\n"
    )
    if immutable and path.exists():
        if path.read_text(encoding="utf-8") != payload:
            raise ValueError(f"Frozen evidence differs: {path}")
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(payload, encoding="utf-8")


def source_hash(root: Path) -> str:
    files = sorted(
        p
        for folder in ("src", "ml", "scripts", "tests", "vendor/btc/AI05")
        for p in (root / folder).rglob("*.py")
    ) + [root / "pyproject.toml", root / "uv.lock"]
    digest = hashlib.sha256()
    for path in files:
        digest.update(path.relative_to(root).as_posix().encode())
        digest.update(path.read_bytes())
    return digest.hexdigest()


def environment() -> dict[str, object]:
    return {
        "python": sys.version,
        "platform": platform.platform(),
        "processor": platform.processor(),
        "packages": {
            name: version(name)
            for name in ("scikit-learn", "numpy", "pandas", "joblib")
        },
        "git_commit": None,
        "git_status": "not_a_git_repository; source snapshot hash supplied instead",
    }
