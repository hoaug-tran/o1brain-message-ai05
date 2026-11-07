import pytest

from ml.metrics import evaluate


def test_hand_calculated_fixture_not_a_benchmark() -> None:
    truth = ["spam"] * 10 + ["ham"] * 20
    predicted = ["spam"] * 8 + ["ham"] * 2 + ["spam"] * 2 + ["ham"] * 18
    report = evaluate(truth, predicted)
    assert report["confusion"] == {"tp": 8, "fp": 2, "fn": 2, "tn": 18}
    assert report["metrics"] == {
        "spam_f1": 0.8,
        "spam_precision": 0.8,
        "spam_recall": 0.8,
        "ham_fpr": 0.1,
    }


def test_undefined_and_invalid() -> None:
    assert evaluate(["ham"], ["ham"])["metrics"]["spam_precision"] is None
    assert evaluate(["spam"], ["ham"])["metrics"]["ham_fpr"] is None
    for truth, predicted in (([], []), (["ham"], []), (["fraud"], ["spam"])):
        with pytest.raises(ValueError):
            evaluate(truth, predicted)
