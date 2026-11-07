import hashlib
import io
import json
import zipfile

import pytest

from scripts.download_data import install


def test_download_validates_before_replacing_existing_data(tmp_path):
    messages = b"ham\thello\nspam\tclaim prize\n"
    manifest = tmp_path / "data/manifests/sources.json"
    manifest.parent.mkdir(parents=True)
    manifest.write_text(
        json.dumps({"source_sha256": hashlib.sha256(messages).hexdigest()})
    )
    payload = io.BytesIO()
    with zipfile.ZipFile(payload, "w") as archive:
        archive.writestr("SMSSpamCollection", messages)
        archive.writestr("readme", "source notice")
    install(payload.getvalue(), tmp_path)
    target = tmp_path / "data/raw/SMSSpamCollection"
    assert target.read_bytes() == messages
    install(payload.getvalue(), tmp_path)
    target.write_bytes(b"user data")
    with pytest.raises(ValueError, match="Existing data differs"):
        install(payload.getvalue(), tmp_path)
    assert target.read_bytes() == b"user data"
    manifest.write_text(json.dumps({"source_sha256": "0" * 64}))
    with pytest.raises(ValueError, match="hash differs"):
        install(payload.getvalue(), tmp_path)
    assert target.read_bytes() == b"user data"
