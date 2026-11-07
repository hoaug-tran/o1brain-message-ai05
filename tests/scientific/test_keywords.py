import json
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

from ml.eda import keyword_statistics, keyword_tokens, make_wordcloud


def test_keywords_count_occurrences_and_messages_without_stopwords_or_stems():
    frame = pd.DataFrame(
        {
            "label": ["ham", "ham", "spam", "spam"],
            "text": [
                "You are dumb. You are dumb. You are dump.",
                "Let me know. Good morning! Collection collections.",
                "Claim your prize! Free entry. Call call.",
                "Call. txt msg. not free. Bill interest fire.",
            ],
        }
    )
    result = keyword_statistics(frame, min_messages=1)
    ham = {row["term"]: row for row in result["labels"]["ham"]["words"]}
    spam = {row["term"]: row for row in result["labels"]["spam"]["words"]}
    assert ham["dumb"] == {
        "term": "dumb",
        "occurrences": 2,
        "messages": 1,
        "message_rate": 0.5,
    }
    assert ham["dump"]["occurrences"] == 1
    assert {"collection", "collections"} <= set(ham)
    assert not {"you", "are", "me", "your", "not", "collect"} & set(ham)
    assert spam["call"]["occurrences"] == 3 and spam["call"]["messages"] == 2
    assert {"text", "message", "bill", "interest", "fire"} <= set(spam)
    assert not {"txt", "msg"} & set(spam)
    assert result["labels"]["spam"]["words"][0]["term"] == "call"


def test_phrases_keep_real_adjacency_and_negation_not_filtered_word_bridges():
    frame = pd.DataFrame(
        {
            "label": ["ham", "spam"],
            "text": [
                "Let me know! Good morning. You are not sure.",
                "Claim your prize. Claim. Prize. Not free. Don't reply. free https://example.test gift; cash amp reward.",
            ],
        }
    )
    result = keyword_statistics(frame, min_messages=1)
    ham = {row["term"] for row in result["labels"]["ham"]["phrases"]}
    spam = {row["term"] for row in result["labels"]["spam"]["phrases"]}
    assert {"let me know", "good morning", "not sure"} <= ham
    assert {"claim your prize", "not free", "not reply"} <= spam
    assert not {"let know", "claim prize", "free gift", "cash reward", "prize not"} & (
        ham | spam
    )


def test_tokens_drop_markup_unicode_fragments_addresses_and_alphanumeric_parts():
    text = "you &amp; are dumb âã Ð¢ abc123 123cash txt https://example.test/free user@example.test &nbsp; running collection"
    frame = pd.DataFrame({"label": ["ham", "spam"], "text": [text, "Claim prize"]})
    result = keyword_statistics(frame, min_messages=1)
    words = {row["term"] for row in result["labels"]["ham"]["words"]}
    assert words == {"dumb", "text", "running", "collection"}
    assert keyword_tokens("Don't") == ["do", "not"]
    assert keyword_tokens("couldn't've") == ["could", "not", "have"]
    assert keyword_tokens("ＦＲＥＥ") == ["free"]
    assert "running" in words and "run" not in words
    assert "example.test" not in json.dumps(result)


def test_support_and_invalid_input_are_explicit():
    frame = pd.DataFrame(
        {
            "label": ["ham", "ham", "spam"],
            "text": ["Friendly friendly", "Meeting", "Claim"],
        }
    )
    assert keyword_statistics(frame, min_messages=2)["labels"]["ham"]["words"] == []
    for bad in (
        frame.iloc[:1],
        pd.DataFrame({"label": ["ham", "spam"], "text": [None, "Claim"]}),
    ):
        with pytest.raises(ValueError):
            keyword_statistics(bad)


def test_phrase_display_prefers_complete_extension_without_changing_counts():
    frame = pd.DataFrame(
        {
            "label": ["ham", "ham", "spam"],
            "text": [
                "Happy new year! Happy new year! Happy new year! Happy new year!",
                "Happy new beginning. Lemme know.",
                "Claim your prize.",
            ],
        }
    )
    result = keyword_statistics(frame, min_messages=1)["labels"]["ham"]
    raw = {row["term"]: row["occurrences"] for row in result["phrases"]}
    shown = {row["term"] for row in result["display_phrases"]}
    assert raw["happy new"] == 5 and raw["happy new year"] == 4
    assert "happy new" not in shown and "happy new year" in shown
    assert "let me know" in shown and "lemme know" not in shown


def test_cloud_uses_exact_frequencies_and_repeats_layout_without_adding_words():
    font = (
        Path(__file__).resolve().parents[2]
        / "android/app/src/main/res/font/manrope.ttf"
    )
    words = [
        {"term": "claim", "occurrences": 12},
        {"term": "prize", "occurrences": 6},
        {"term": "mobile", "occurrences": 3},
    ]
    first = make_wordcloud(words, "spam", font)
    second = make_wordcloud(words, "spam", font)
    assert first.words_ == {"claim": 1.0, "prize": 0.5, "mobile": 0.25}
    assert set(item[0][0] for item in first.layout_) == {"claim", "prize", "mobile"}
    assert first.layout_ == second.layout_
    assert np.array_equal(first.to_array(), second.to_array())
    for bad in (
        [],
        [{"term": "abc123", "occurrences": 1}],
        [{"term": "claim", "occurrences": 0}],
    ):
        with pytest.raises(ValueError):
            make_wordcloud(bad, "spam", font)


def test_sms_abbreviations_expand_before_counting_and_domains_do_not_become_words():
    frame = pd.DataFrame(
        {
            "label": ["ham", "spam"],
            "text": [
                "gud good thk think dat cos wan want gonna wif lar liao",
                "freemsg free message visit claim.example.com/free",
            ],
        }
    )
    result = keyword_statistics(frame, min_messages=1)
    ham = {row["term"]: row["occurrences"] for row in result["labels"]["ham"]["words"]}
    spam = {
        row["term"]: row["occurrences"] for row in result["labels"]["spam"]["words"]
    }
    assert ham["good"] == ham["think"] == ham["want"] == 2
    assert not {"gud", "thk", "dat", "cos", "wan", "wif", "lar", "liao"} & set(ham)
    assert spam["free"] == spam["message"] == 2
    assert not {"example", "com", "claim"} & set(spam)
