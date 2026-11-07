import hashlib
import io
import json
import urllib.request
import zipfile
from pathlib import Path

URL = "https://archive.ics.uci.edu/static/public/228/sms+spam+collection.zip"


def install(payload: bytes, root: Path) -> None:
    expected = json.loads((root / "data/manifests/sources.json").read_text())[
        "source_sha256"
    ]
    with zipfile.ZipFile(io.BytesIO(payload)) as archive:
        if (
            archive.getinfo("SMSSpamCollection").file_size > 2_000_000
            or archive.getinfo("readme").file_size > 100_000
        ):
            raise ValueError("Unexpected UCI uncompressed member size")
        messages = archive.read("SMSSpamCollection")
        notice = archive.read("readme")
    if hashlib.sha256(messages).hexdigest() != expected:
        raise ValueError("UCI corpus hash differs from frozen BTC training data")
    destination = root / "data/raw"
    destination.mkdir(parents=True, exist_ok=True)
    for name, content in (("SMSSpamCollection", messages), ("readme", notice)):
        path = destination / name
        if path.exists() and path.read_bytes() != content:
            raise ValueError(f"Existing data differs: {name}")
    for name, content in (("SMSSpamCollection", messages), ("readme", notice)):
        if not (destination / name).exists():
            (destination / name).write_bytes(content)


def main() -> None:
    request = urllib.request.Request(URL, headers={"User-Agent": "AI05-data-download"})
    with urllib.request.urlopen(request, timeout=60) as response:
        payload = response.read(4_000_001)
    if len(payload) > 4_000_000:
        raise ValueError("UCI archive exceeds expected download size")
    install(payload, Path(__file__).resolve().parents[1])
    print("UCI corpus verified against frozen training source hash")


if __name__ == "__main__":
    main()
