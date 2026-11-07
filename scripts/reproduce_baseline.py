import argparse
import subprocess
import sys
from pathlib import Path

from ml.common import sha256, write_json


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Run supplied baseline with one recorded TSV quotation parser correction."
    )
    parser.add_argument("--data", type=Path, default=Path("data/raw/SMSSpamCollection"))
    parser.add_argument("--out", type=Path, default=Path(".task/btc-parser-corrected"))
    args = parser.parse_args()
    original = Path("vendor/btc/AI05/train.py")
    code = original.read_text(encoding="utf-8")
    before = "encoding='latin-1',engine='python'"
    after = "encoding='latin-1',engine='python',quoting=3"
    if code.count(before) != 1:
        raise ValueError("BTC source changed; review parser correction again")
    args.out.mkdir(parents=True, exist_ok=True)
    patched = args.out / "btc_train_parser_corrected.py"
    patched.write_text(code.replace(before, after), encoding="utf-8")
    write_json(
        args.out / "parser_correction.json",
        {
            "baseline_kind": "btc_parser_corrected",
            "original_sha256": sha256(original),
            "executed_sha256": sha256(patched),
            "replacement_before": before,
            "replacement_after": after,
            "reason": "UCI TSV message quotes are literal; unchanged BTC parser raises ParserError",
        },
    )
    subprocess.run(
        [
            sys.executable,
            str(patched),
            "--data",
            str(args.data),
            "--out",
            str(args.out),
        ],
        check=True,
    )


if __name__ == "__main__":
    main()
