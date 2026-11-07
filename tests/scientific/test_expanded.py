import pytest

from ml.expanded import clean_extra, near_groups


def test_external_cleaning_rejects_conflicts_duplicates_bad_labels_and_exclusions():
    rows = [
        {"text": "Hello friend", "label": "ham", "source": "a"},
        {"text": " hello FRIEND ", "label": "spam", "source": "b"},
        {"text": "Claim free prize", "label": "smishing", "source": "a"},
        {"text": "claim free prize", "label": "spam", "source": "b"},
        {"text": "Meeting tomorrow", "label": "ham", "source": "a"},
        {"text": "already in uci", "label": "ham", "source": "a"},
        {"text": "", "label": "ham", "source": "a"},
    ]
    frame, quality = clean_extra(rows, {"already in uci"})
    assert set(frame.label) == {"ham", "spam"}
    assert len(frame) == 2
    assert frame.loc[frame.label == "spam", "source"].item() == "a+b"
    assert quality["conflicting_groups_removed"] == 1
    assert quality["exact_uci_overlap_removed"] == 1
    with pytest.raises(ValueError, match="Unexpected"):
        clean_extra([{**rows[0], "label": "unknown"}], set())


def test_near_groups_keep_exact_and_near_messages_together():
    groups = near_groups(
        [
            "Claim your free cash prize now",
            "CLAIM your free cash prize now",
            "Meet at library tomorrow",
        ],
        threshold=0.9,
    )
    assert groups[0] == groups[1] and groups[0] != groups[2]
    with pytest.raises(ValueError):
        near_groups([], 0.9)
