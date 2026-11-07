import json
from pathlib import Path

import joblib
import numpy as np
import pytest
from matplotlib.figure import Figure
from sklearn.metrics import average_precision_score

from ml.common import sha256
from ml.datasets import load_btc, split_btc
from ml.eda import NAMES, render, run
from ml.metrics import evaluate

ROOT = Path(__file__).resolve().parents[2]
REPORT = ROOT / "results/report"


def test_report_metrics_recompute_from_same_frozen_validation():
    evidence = json.loads((REPORT / "evidence.json").read_text())
    manifest = json.loads((ROOT / "data/manifests/english_split.json").read_text())
    assert evidence["validation_ids"] == manifest["validation"]
    assert len(evidence["truth"]) == 1032
    assert evidence["validation_support"] == {"ham": 904, "spam": 128}
    assert set(evidence["reports"]) == set(NAMES)
    assert len(NAMES) == 6
    for name, report in evidence["reports"].items():
        assert report == evaluate(evidence["truth"], evidence["predictions"][name])
        assert evidence["curves"][name]["average_precision"] == average_precision_score(
            np.array(evidence["truth"]) == "spam", evidence["scores"][name]
        )
    assert evidence["reports"]["english_baseline"]["confusion"] == {
        "tn": 904,
        "fp": 0,
        "fn": 31,
        "tp": 97,
    }
    selection = json.loads((REPORT / "selection.json").read_text())
    ranked = sorted(
        selection["train_cv"],
        key=lambda n: (
            -selection["train_cv"][n]["metrics"]["spam_f1"],
            selection["train_cv"][n]["metrics"]["ham_fpr"],
            n,
        ),
    )
    assert evidence["selected_on_train"] == selection["selected"] == ranked[0]
    plan = json.loads((REPORT / "plan.json").read_text())
    assert sum(map(len, plan["search_grid"].values())) == 30
    manifest_train = plan["train_ids"]
    assert manifest_train == manifest["train"]
    assert evidence["train_cv"] == selection["train_cv"]
    assert (
        sha256(REPORT / "selected_model.joblib") == evidence["selected_artifact_sha256"]
    )
    for name, trials in selection["trials"].items():
        assert [row["parameters"] for row in trials] == plan["search_grid"][name]
        for trial in trials:
            assert trial["report"] == evaluate(
                selection["train_truth"], trial["oof_predictions"]
            )
        best = max(
            trials,
            key=lambda row: (
                row["report"]["metrics"]["spam_f1"],
                -row["report"]["metrics"]["ham_fpr"],
            ),
        )
        assert best["parameters"] == evidence["chosen_parameters"][name]
        assert best["report"] == selection["train_cv"][name]
        assert all(len(row["oof_predictions"]) == len(manifest_train) for row in trials)
    for item in json.loads((REPORT / "figures.json").read_text()):
        assert (
            sha256(REPORT / "figures" / (item["name"] + ".png")) == item["png_sha256"]
        )
        assert (
            sha256(REPORT / "figures" / (item["name"] + ".svg")) == item["svg_sha256"]
        )


def test_rendered_values_and_text_match_evidence(tmp_path, monkeypatch):
    evidence = json.loads((REPORT / "evidence.json").read_text())
    keywords = json.loads((REPORT / "keywords.json").read_text())
    source = ROOT / "data/raw/SMSSpamCollection"
    if not source.is_file():
        pytest.skip("Local UCI corpus required to regenerate train-only figures")
    frame, _ = load_btc(source)
    train, validation = split_btc(frame)
    selection = json.loads((REPORT / "selection.json").read_text())
    assert selection["train_truth"] == train.label.tolist()
    model = joblib.load(REPORT / "selected_model.joblib")
    assert (
        model.predict(validation.text).tolist()
        == evidence["predictions"][evidence["selected_on_train"]]
    )
    seen = set()

    def inspect(figure, path, **kwargs):
        path = Path(path)
        if path.suffix == ".svg":
            path.write_text("<svg/>")
            return
        figure.canvas.draw()
        assert len(figure.texts) == 1
        assert figure.texts[0] is figure._suptitle
        for text in figure.findobj(match=lambda obj: hasattr(obj, "get_text")):
            assert not any(symbol in text.get_text() for symbol in ("·", "•", "—", "–"))
        if path.stem == "08_models":
            for ax, key in zip(
                figure.axes, ("spam_f1", "spam_precision", "ham_fpr"), strict=True
            ):
                assert [bar.get_width() for bar in ax.patches] == [
                    100 * evidence["reports"][name]["metrics"][key] for name in NAMES
                ]
        if path.stem == "11_tuning":
            renderer = figure.canvas.get_renderer()
            names = [name for name in NAMES if name != "english_baseline"]
            for ax, key in zip(
                figure.axes, ("spam_f1", "spam_precision", "ham_fpr"), strict=True
            ):
                expected = [
                    100 * evidence[source][name]["metrics"][key]
                    for source in ("fixed_reports", "reports")
                    for name in names
                ]
                assert [bar.get_width() for bar in ax.patches] == expected
                legend = ax.get_legend().get_window_extent(renderer)
                assert all(
                    not legend.overlaps(text.get_window_extent(renderer))
                    for text in ax.texts
                )
        if path.stem == "09_confusion":
            assert len(figure.axes) == 7
            assert figure.axes[-1].get_ylabel() == "Số tin nhắn"
            assert figure.axes[-1].get_ylim() == (0, 904)
            for ax, report in zip(
                figure.axes[:6],
                (evidence["reports"][name] for name in NAMES),
                strict=True,
            ):
                c = report["confusion"]
                assert ax.images[0].get_array().tolist() == [
                    [c["tn"], c["fp"]],
                    [c["fn"], c["tp"]],
                ]
                assert ax.images[0].get_clim() == (0, 904)
            renderer = figure.canvas.get_renderer()
            for top, bottom in zip(figure.axes[:2], figure.axes[3:5], strict=True):
                assert (
                    bottom.title.get_window_extent(renderer).y1
                    < top.xaxis.label.get_window_extent(renderer).y0
                )
        seen.add(path.stem)
        path.write_bytes(b"checked")

    monkeypatch.setattr(Figure, "savefig", inspect)
    render(ROOT, tmp_path, train, evidence, keywords)
    assert {
        "01_classes",
        "02_quality",
        "03_lengths",
        "05_signals",
        "06_tokens",
        "07_phrases",
        "06_ham_wordcloud",
        "06_spam_wordcloud",
        "08_models",
        "09_confusion",
        "10_precision_recall",
        "11_tuning",
    } == seen


def test_report_refuses_to_overwrite_existing_evidence(tmp_path):
    with pytest.raises(ValueError, match="Preserve existing"):
        run(tmp_path, tmp_path)
