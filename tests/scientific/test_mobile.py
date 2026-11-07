import json
from pathlib import Path

import numpy as np
import pytest

from ml.metrics import evaluate
from ml.mobile import select_threshold


def test_threshold_uses_strict_boundary_and_respects_false_positives():
    truth = ["ham", "ham", "spam", "spam"]
    scores = np.array([-1, 0.3, 0.2, 1.0])
    threshold = select_threshold(truth, scores, 0, 1)
    assert threshold == 0.3
    assert evaluate(truth, np.where(scores > threshold, "spam", "ham").tolist())[
        "confusion"
    ] == {"tn": 2, "fp": 0, "fn": 1, "tp": 1}
    with pytest.raises(ValueError):
        select_threshold(truth, [float("nan")] * 4, 0, 1)


def test_mobile_holdout_disjoint_and_metrics_recomputed():
    root = Path(__file__).resolve().parents[2]
    mobile = json.loads((root / "artifacts/mobile.json").read_text())
    run = root / mobile["run"]
    plan, report = [
        json.loads((run / name).read_text()) for name in ["plan.json", "report.json"]
    ]
    assert not set(plan["train_ids"]) & set(plan["holdout_ids"])
    frozen = json.loads((root / "results/expanded/run-20261107/plan.json").read_text())
    assert set(frozen["train_ids"]) <= set(plan["train_ids"])
    assert set(frozen["uci_validation_ids"]) <= set(plan["holdout_ids"])
    assert set(frozen["external_holdout_ids"]) <= set(plan["holdout_ids"])
    for language in ("en", "vi"):
        idx = [i for i, value in enumerate(report["languages"]) if value == language]
        truth = [report["truth"][i] for i in idx]
        for tier, threshold in [
            ("binary", 0),
            ("warning", plan["warning_threshold"]),
            ("automatic", plan["automatic_threshold"]),
        ]:
            assert report["evaluations"][language][tier] == evaluate(
                truth,
                ["spam" if report["margins"][i] > threshold else "ham" for i in idx],
            )
