from collections.abc import Sequence
from typing import TypedDict


class Evaluation(TypedDict):
    positive_label: str
    support: dict[str, int]
    confusion: dict[str, int]
    metrics: dict[str, float | None]
    undefined_policy: str


def evaluate(truth: Sequence[str], predictions: Sequence[str]) -> Evaluation:
    if not truth or len(truth) != len(predictions):
        raise ValueError("Nonempty aligned truth and predictions required")
    if (set(truth) | set(predictions)) - {"ham", "spam"}:
        raise ValueError("Only ham/spam labels allowed")
    tn = fp = fn = tp = 0
    for actual, predicted in zip(truth, predictions, strict=True):
        if actual == "ham":
            tn += predicted == "ham"
            fp += predicted == "spam"
        else:
            fn += predicted == "ham"
            tp += predicted == "spam"
    return {
        "positive_label": "spam",
        "support": {"ham": tn + fp, "spam": tp + fn},
        "confusion": {"tn": tn, "fp": fp, "fn": fn, "tp": tp},
        "metrics": {
            "spam_precision": tp / (tp + fp) if tp + fp else None,
            "spam_recall": tp / (tp + fn) if tp + fn else None,
            "spam_f1": 2 * tp / (2 * tp + fp + fn) if 2 * tp + fp + fn else None,
            "ham_fpr": fp / (fp + tn) if fp + tn else None,
        },
        "undefined_policy": "null means zero denominator; confusion and support retained",
    }
