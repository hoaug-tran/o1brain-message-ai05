import argparse
import hashlib
import json
import platform
from pathlib import Path


def inspect_environment(root: Path) -> dict[str, object]:
    required = ["README.md", "uv.lock"]
    inputs = ["Baseline.zip", "Đề thi chung kết cuộc thi Olympic AI.docx"]
    return {
        "python": platform.python_version(),
        "python_supported": platform.python_version_tuple()[:2] == ("3", "12"),
        "missing": [name for name in required if not (root / name).is_file()],
        "inputs": {
            name: hashlib.sha256((root / name).read_bytes()).hexdigest()
            for name in inputs
            if (root / name).is_file()
        },
        "dataset_present": (root / "data/raw/SMSSpamCollection").is_file(),
        "registry_present": (root / "artifacts/registry.json").is_file(),
        "git_present": (root / ".git").exists(),
    }


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Inspect local inputs without reading or displaying message text."
    )
    parser.add_argument(
        "--root", type=Path, default=Path(__file__).resolve().parents[1]
    )
    args = parser.parse_args()
    report = inspect_environment(args.root)
    print(json.dumps(report, indent=2))
    return 0 if report["python_supported"] and not report["missing"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
