import argparse
import json
import shutil
import zipfile
from pathlib import Path

from ml.common import sha256, write_json


def package_ignore(directory: str, names: list[str]) -> set[str]:
    excluded = set(
        shutil.ignore_patterns(
            "__pycache__",
            "*.pyc",
            "demo_messages.csv",
            "desktop.png",
            "mobile.png",
            "build",
            ".gradle",
            ".kotlin",
            "local.properties",
            "*.jks",
            "*.keystore",
            "*.db*",
            "*.key",
            ".env",
            ".env.*",
            ".ipynb_checkpoints",
        )(directory, names)
    )
    if Path(directory).name == "deploy":
        excluded.add("data")
    return excluded


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Build an allowlisted source/model package; never include SMS data or secrets."
    )
    parser.add_argument(
        "--output", type=Path, default=Path("dist/UNETi22_O(1).Brain_AI05")
    )
    parser.add_argument("--slides", type=Path, required=True)
    parser.add_argument("--presentation", type=Path, default=Path("slides.pptx"))
    parser.add_argument("--apk", type=Path)
    args = parser.parse_args()
    if (
        not args.slides.is_file()
        or args.slides.suffix.lower() != ".pdf"
        or not args.slides.read_bytes().startswith(b"%PDF-")
    ):
        raise ValueError("A verified slides.pdf is required for the BTC submission")
    if not args.presentation.is_file() or not zipfile.is_zipfile(args.presentation):
        raise ValueError("Original presentation.pptx is required")
    notebook = Path("AI05_Colab.ipynb")
    if not notebook.is_file():
        raise ValueError("Verified AI05_Colab.ipynb is required")
    if args.output.exists():
        raise SystemExit(
            "Output exists; choose a new directory to preserve package evidence"
        )
    assets = Path("android/app/src/main/assets")
    if any(assets.glob("product_*.json")):
        raise ValueError("External-trained assets forbidden in submission")
    portable = json.loads((assets / "english_char_svm.json").read_text())
    registry = json.loads(Path("artifacts/registry.json").read_text())
    active = registry["models"][registry["active_model_id"]]
    if portable["source_artifact_sha256"] != active["artifact_sha256"]:
        raise ValueError("APK model differs from frozen UCI registry")
    if args.apk:
        with zipfile.ZipFile(args.apk) as apk:
            if (
                apk.read("assets/english_char_svm.json")
                != (assets / "english_char_svm.json").read_bytes()
            ):
                raise ValueError("Packaged APK model differs from UCI registry")
    if any(Path("results/research").glob("**/*.joblib")):
        raise ValueError("External-trained research weights forbidden in submission")
    args.output.mkdir(parents=True)
    for name in (
        "src",
        "ml",
        "scripts",
        "tests",
        "artifacts",
        "data/manifests",
        "licenses",
        "migrations",
        "vendor/btc",
        "results",
        "android",
        "deploy",
    ):
        shutil.copytree(
            name,
            args.output / name,
            ignore=package_ignore,
        )
    for name in (
        "pyproject.toml",
        "uv.lock",
        ".python-version",
        ".env.example",
        "alembic.ini",
        "README.md",
        "requirements.txt",
        "AI05_Colab.ipynb",
        ".dockerignore",
        ".gitignore",
    ):
        destination = args.output / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(name, destination)
    (args.output / ".task").mkdir()
    shutil.copyfile(args.slides, args.output / "slides.pdf")
    shutil.copyfile(args.presentation, args.output / "slides.pptx")
    if args.apk:
        shutil.copyfile(args.apk, args.output / "android/O1Brain_Message_UCI_1.0.0.apk")
    (args.output / "run_windows.ps1").write_text(
        "Set-Location $PSScriptRoot\nuv run --offline --no-sync alembic upgrade head\nif ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }\nuv run --offline --no-sync uvicorn ai05.interfaces.http:app --host 127.0.0.1 --port 8000 --no-access-log\n"
    )
    (args.output / "run_linux.sh").write_text(
        '#!/bin/sh\nset -eu\ncd "$(dirname "$0")"\nuv run --offline --no-sync alembic upgrade head\nexec uv run --offline --no-sync uvicorn ai05.interfaces.http:app --host 127.0.0.1 --port 8000 --no-access-log\n'
    )
    files = {
        path.relative_to(args.output).as_posix(): sha256(path)
        for path in sorted(args.output.rglob("*"))
        if path.is_file()
    }
    if any(
        "private/" in name
        or "data/raw/" in name
        or (name.startswith("data/research/") and name.endswith(".csv"))
        or name.startswith("deploy/data/")
        or name.endswith((".db", ".jks", ".keystore", ".key"))
        for name in files
    ):
        raise RuntimeError("Forbidden package member")
    write_json(args.output / "checksums.json", files)
    archive = shutil.make_archive(
        str(args.output), "zip", args.output.parent, args.output.name
    )
    print(f"Package: {archive}; files: {len(files)}; SHA-256: {sha256(Path(archive))}")


if __name__ == "__main__":
    main()
