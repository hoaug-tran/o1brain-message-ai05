from pathlib import Path

import pandas as pd
import pytest

from ml.common import sha256, write_json
from ml.datasets import assert_disjoint, load_btc, near_overlap, normalize, split_btc


def test_quarantine_and_duplicates(tmp_path: Path) -> None:
    path = tmp_path / "sms"
    path.write_text(
        "ham\tHello\nham\t hello \nham\tconflict\nspam\tCONFLICT\nspam\tPrize\n",
        encoding="latin-1",
    )
    clean, report = load_btc(path)
    assert list(clean.text) == ["Hello", "Prize"]
    assert report["quarantined_conflict_rows"] == 2
    assert report["quarantined_conflict_groups"] == 1


@pytest.mark.parametrize("text", ["", "other\thello\n", "ham\t\n", "ham\thello\n"])
def test_bad_data_rejected(tmp_path: Path, text: str) -> None:
    path = tmp_path / "sms"
    path.write_text(text)
    with pytest.raises((ValueError, pd.errors.EmptyDataError)):
        load_btc(path)


def test_split_repeatable_and_disjoint() -> None:
    frame = pd.DataFrame(
        {"label": ["ham", "spam"] * 20, "normalized": [str(i) for i in range(40)]}
    )
    first, second = split_btc(frame)
    repeated, _ = split_btc(frame)
    assert list(first.index) == list(repeated.index)
    assert len(second) == 8
    with pytest.raises(ValueError, match="overlap"):
        assert_disjoint(first, first, "normalized")


def test_normalization_and_near_overlap() -> None:
    assert normalize("  Café\nNOW ") == normalize("cafe\u0301 now")
    train = pd.DataFrame(
        {"audit_normalized": ["claim free prize today"], "source_id": ["a"]}
    )
    validation = pd.DataFrame(
        {"audit_normalized": ["claim free prize today"], "source_id": ["b"]}
    )
    report = near_overlap(train, validation)
    assert report["validation_rows_at_or_above_threshold"] == 1
    assert report == near_overlap(train, validation)


def test_hash_and_frozen_manifest(tmp_path: Path) -> None:
    path = tmp_path / "manifest.json"
    write_json(path, {"seed": 2026}, immutable=True)
    before = sha256(path)
    write_json(path, {"seed": 2026}, immutable=True)
    assert sha256(path) == before
    with pytest.raises(ValueError, match="Frozen"):
        write_json(path, {"seed": 42}, immutable=True)
    path.write_text("changed")
    assert sha256(path) != before


def test_literal_quotes_are_sms_content(tmp_path: Path) -> None:
    path = tmp_path / "sms"
    path.write_text('ham\t"Hello" said friend\nspam\t"Claim" prize!\n')
    frame, _ = load_btc(path)
    assert frame.iloc[0].text == '"Hello" said friend'


def test_fitted_vocabulary_excludes_validation_only_token() -> None:
    from sklearn.feature_extraction.text import TfidfVectorizer
    from sklearn.naive_bayes import MultinomialNB
    from sklearn.pipeline import make_pipeline

    model = make_pipeline(
        TfidfVectorizer(ngram_range=(1, 2), sublinear_tf=True), MultinomialNB(alpha=0.5)
    )
    model.fit(["normal hello friend", "claim free prize"], ["ham", "spam"])
    model.predict(["validationonlytoken"])
    assert "validationonlytoken" not in model[0].vocabulary_
