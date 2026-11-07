import sys

import pytest

from scripts.package_offline import main


@pytest.mark.parametrize("content", [None, b"not a PDF"])
def test_packaging_requires_slides_before_creating_output(
    tmp_path, monkeypatch, content
):
    slides = tmp_path / "slides.pdf"
    if content is not None:
        slides.write_bytes(content)
    output = tmp_path / "submission"
    monkeypatch.setattr(
        sys,
        "argv",
        ["package_offline.py", "--slides", str(slides), "--output", str(output)],
    )
    with pytest.raises(ValueError, match="slides.pdf is required"):
        main()
    assert not output.exists()
