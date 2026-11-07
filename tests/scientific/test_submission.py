import hashlib
import io
import json
import xml.etree.ElementTree as ET
from pathlib import Path
from zipfile import ZipFile

import joblib
import pytest
from PIL import Image

from scripts.build_notebook import build

ROOT = Path(__file__).resolve().parents[2]


def test_runtime_is_exact_train_cv_selected_model():
    registry = json.loads((ROOT / "artifacts/registry.json").read_text())
    evidence = json.loads((ROOT / "results/report/evidence.json").read_text())
    selected = evidence["selected_on_train"]
    assert registry["active_model_id"] == selected
    path = ROOT / "artifacts" / registry["models"][selected]["artifact"]
    assert (
        hashlib.sha256(path.read_bytes()).hexdigest()
        == evidence["selected_artifact_sha256"]
    )
    model = joblib.load(path)
    assert model[-1].get_params()["class_weight"] == "balanced"
    assert model[-1].get_params()["C"] == 1
    portable = json.loads(
        (ROOT / "android/app/src/main/assets/english_char_svm.json").read_text()
    )
    assert portable["source_artifact_sha256"] == evidence["selected_artifact_sha256"]


def test_notebook_is_current_small_and_contains_no_saved_outputs():
    notebook = json.loads((ROOT / "AI05_Colab.ipynb").read_text(encoding="utf-8"))
    assert notebook == build(ROOT)
    assert len(json.dumps(notebook).encode()) < 600_000
    for cell in notebook["cells"]:
        if cell["cell_type"] == "code":
            assert cell["outputs"] == [] and cell["execution_count"] is None
            compile("".join(cell["source"]), "notebook", "exec")


def test_presentation_figures_and_native_chart_values_match_evidence():
    evidence = json.loads((ROOT / "results/report/evidence.json").read_text())
    keywords = json.loads((ROOT / "results/report/keywords.json").read_text())
    ns = {"c": "http://schemas.openxmlformats.org/drawingml/2006/chart"}
    with ZipFile(ROOT / "slides.pptx") as deck:
        crops = (
            (2, "03_lengths.png", (0, 130, 2340, 1080)),
            (3, "06_ham_wordcloud.png", (164, 263, 1634, 1306)),
            (4, "06_spam_wordcloud.png", (168, 262, 1628, 1265)),
        )
        for index, source, bounds in crops:
            with Image.open(ROOT / "results/report/figures" / source) as report:
                actual = Image.open(
                    io.BytesIO(deck.read(f"ppt/media/image{index}.png"))
                ).convert("RGB")
                assert report.crop(bounds).convert("RGB").tobytes() == actual.tobytes()
        names = [
            "english_tuned_nb",
            "english_complement_nb",
            "english_logreg",
            "english_word_svm",
            "english_char_svm",
        ]
        expected = {
            1: [[4827, 747]],
            2: [
                [evidence[key][name]["metrics"]["spam_f1"] for name in names]
                for key in ("fixed_reports", "reports")
            ],
            3: [
                [
                    row["occurrences"]
                    for row in keywords["labels"]["ham"]["words"][:8][::-1]
                ]
            ],
            4: [
                [
                    row["occurrences"]
                    for row in keywords["labels"]["spam"]["words"][:8][::-1]
                ]
            ],
        }
        for index, rows in expected.items():
            chart = ET.fromstring(deck.read(f"ppt/charts/chart{index}.xml"))
            series = chart.findall(".//c:ser", ns)
            assert len(series) == len(rows)
            for actual, values in zip(series, rows, strict=True):
                assert [
                    float(x.text) for x in actual.findall("c:val//c:v", ns)
                ] == pytest.approx(values, abs=1e-12)
