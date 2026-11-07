import ast
import base64
import hashlib
import io
import json
import subprocess
import sys
from importlib.metadata import version
from pathlib import Path
from zipfile import ZipFile

from PIL import Image


def build(root: Path) -> dict:
    cells = []

    def cell(kind: str, source: str) -> None:
        if kind == "code" and "\nfrom ml.common" in source:
            setup, imports = source.split("\nfrom ml.common", 1)
            cell(kind, setup)
            cell(kind, "from ml.common" + imports)
            return
        item: dict = {
            "cell_type": kind,
            "metadata": {},
            "source": source.strip().splitlines(keepends=True),
        }
        if kind == "code":
            item.update(execution_count=None, outputs=[])
        cells.append(item)

    def digest(items: list[str]) -> str:
        return hashlib.sha256("\n".join(items).encode()).hexdigest()

    evidence = json.loads((root / "results/report/evidence.json").read_text())
    manifest = json.loads((root / "data/manifests/english_split.json").read_text())
    selection = json.loads((root / "results/report/selection.json").read_text())
    expected = {
        "source_sha256": manifest["source_sha256"],
        "train_ids_sha256": digest(manifest["train"]),
        "validation_ids_sha256": digest(manifest["validation"]),
        "reports": evidence["reports"],
        "train_cv": evidence["train_cv"],
        "chosen_parameters": evidence["chosen_parameters"],
        "selected_on_train": evidence["selected_on_train"],
        "predictions_sha256": {
            name: digest(rows) for name, rows in evidence["predictions"].items()
        },
        "oof_sha256": {
            name: [digest(trial["oof_predictions"]) for trial in trials]
            for name, trials in selection["trials"].items()
        },
        "keywords_sha256": hashlib.sha256(
            json.dumps(
                json.loads((root / "results/report/keywords.json").read_text()),
                sort_keys=True,
                ensure_ascii=False,
            ).encode()
        ).hexdigest(),
    }
    with ZipFile(root / "slides.pptx") as presentation:
        expected["slide_pixels"] = {
            name: hashlib.sha256(
                Image.open(io.BytesIO(presentation.read(f"ppt/media/image{index}.png")))
                .convert("RGB")
                .tobytes()
            ).hexdigest()
            for index, name in (
                (2, "slide04_lengths.png"),
                (3, "slide05_ham.png"),
                (4, "slide05_spam.png"),
            )
        }
    packages = [
        "numpy",
        "scipy",
        "pandas",
        "scikit-learn",
        "joblib",
        "matplotlib",
        "wordcloud",
        "Pillow",
        "contourpy",
        "cycler",
        "fonttools",
        "kiwisolver",
        "packaging",
        "pyparsing",
        "python-dateutil",
        "pytz",
        "tzdata",
        "threadpoolctl",
    ]
    pins = [f"{name}=={version(name)}" for name in packages]
    cell(
        "markdown",
        """# AI05: Phân loại SMS tiếng Anh

Đội UNETi22, O(1).Brain. Chạy lần lượt các ô trên CPU bằng **Runtime > Run all**. Notebook tự tải đúng corpus UCI, kiểm tra hash, chia lại đúng 4.127 train và 1.032 validation, thử 30 cấu hình trên cùng 5 fold train rồi đối chiếu dự đoán với báo cáo.

Không cần Google Drive, khóa API hoặc thư mục project. Cần Internet lần đầu để cài thư viện và tải UCI. Chỉ dùng dữ liệu UCI để học. Thời gian chạy phụ thuộc CPU; không cần GPU. Kết quả lớn lưu ra tệp, không nhúng vào notebook.

Validation dùng báo cáo kết quả và đã được xem trong phát triển, không phải test độc lập. Đề BTC mục 2.5 không cung cấp bộ test bí mật. Không chia lại dữ liệu đã xem để gọi là test mù.""",
    )
    cell(
        "code",
        "import subprocess\nimport sys\n\nPINS = "
        + repr(pins)
        + "\nsubprocess.run([sys.executable, '-m', 'pip', 'install', '--quiet', '--disable-pip-version-check', *PINS], check=True)\nprint('Đã cài đúng phiên bản thư viện.')",
    )
    modules = {}
    for name in ("common", "metrics", "datasets", "benchmark", "eda", "slides"):
        source = (root / "ml" / f"{name}.py").read_text(encoding="utf-8")
        if name == "eda":
            tree = ast.parse(source)
            kept = [
                node
                for node in tree.body
                if not (
                    isinstance(node, ast.ImportFrom)
                    and node.module == "ai05.infrastructure.models"
                )
                and not (
                    isinstance(node, ast.FunctionDef) and node.name in {"run", "main"}
                )
                and not isinstance(node, ast.If)
            ]
            source = "\n\n".join(ast.get_source_segment(source, node) for node in kept)
        modules[f"ml/{name}.py"] = source
    font = root / "android/app/src/main/res/font/manrope.ttf"
    font_encoded = base64.b64encode(font.read_bytes()).decode()
    cell(
        "markdown",
        "## 1. Mã phân tích và cấu hình tái lập\n\nCác hàm dưới đây lấy từ mã nguồn đi kèm bài nộp. Font Manrope theo giấy phép SIL Open Font License. Số liệu chuẩn được dùng để kiểm tra sau khi huấn luyện, không đưa vào mô hình hoặc chọn cấu hình.",
    )
    cell(
        "code",
        """import base64
import hashlib
import io
import json
import os
import tempfile
import urllib.request
import zipfile
from pathlib import Path

os.environ['OMP_NUM_THREADS'] = '2'
os.environ['OPENBLAS_NUM_THREADS'] = '2'
workspace = tempfile.TemporaryDirectory(prefix='AI05_')
ROOT = Path(workspace.name)
"""
        + "\nMODULES = "
        + repr(modules)
        + "\nEXPECTED = "
        + repr(expected)
        + "\nFONT = "
        + repr(font_encoded)
        + "\n"
        + """
for name, source in MODULES.items():
    path = ROOT / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(source, encoding='utf-8')
(ROOT / 'ml/__init__.py').touch()
font = ROOT / 'android/app/src/main/res/font/manrope.ttf'
font.parent.mkdir(parents=True, exist_ok=True)
font.write_bytes(base64.b64decode(FONT))
sys.path.insert(0, str(ROOT))
from ml.common import write_json
from ml.datasets import load_btc, split_btc
from ml.metrics import evaluate
from ml.benchmark import candidates, parameter_grid, tune_on_train, select_on_train
from ml.eda import NAMES, keyword_statistics, render
from ml.slides import render_slides
print('Đã chuẩn bị mã phân tích trong thư mục tạm.')
""",
    )
    cell(
        "markdown",
        "## 2. Tải và kiểm tra dữ liệu\n\nNguồn: https://archive.ics.uci.edu/dataset/228/sms+spam+collection. Đọc TSV bằng QUOTE_NONE để giữ dấu nháy trong tin nhắn. Giữ quy tắc strip/lower và seed 2026 của baseline BTC.",
    )
    cell(
        "code",
        """
url = 'https://archive.ics.uci.edu/static/public/228/sms+spam+collection.zip'
request = urllib.request.Request(url, headers={'User-Agent': 'AI05-data-download'})
with urllib.request.urlopen(request, timeout=60) as response:
    payload = response.read(4_000_001)
if len(payload) > 4_000_000:
    raise ValueError('Tệp tải vượt kích thước dự kiến')
with zipfile.ZipFile(io.BytesIO(payload)) as archive:
    if archive.getinfo('SMSSpamCollection').file_size > 2_000_000:
        raise ValueError('Corpus vượt kích thước dự kiến')
    corpus = archive.read('SMSSpamCollection')
if hashlib.sha256(corpus).hexdigest() != EXPECTED['source_sha256']:
    raise ValueError('Hash corpus khác dữ liệu đã báo cáo')
path = ROOT / 'SMSSpamCollection'
path.write_bytes(corpus)
frame, quality = load_btc(path)
train, validation = split_btc(frame)
def digest(items):
    return hashlib.sha256('\\n'.join(items).encode()).hexdigest()
assert digest(train.source_id.tolist()) == EXPECTED['train_ids_sha256']
assert digest(validation.source_id.tolist()) == EXPECTED['validation_ids_sha256']
assert (quality['source_rows'], quality['clean_rows'], len(train), len(validation)) == (5574, 5159, 4127, 1032)
print('Nguồn:', quality['class_distribution_raw'])
print('Sau làm sạch:', quality['class_distribution_clean'])
print('Train:', train.label.value_counts().to_dict())
print('Validation:', validation.label.value_counts().to_dict())
""",
    )
    cell(
        "markdown",
        "## 3. Chọn mô hình bằng 5-fold CV trên train\n\nBaseline giữ alpha=0,5. Hai họ NB thử alpha 0,1; 0,5; 1. Logistic Regression và hai SVM thử C 0,1; 1; 4; 10, với class_weight None hoặc balanced. Vocabulary và IDF chỉ fit trong phần học của từng fold. Chọn F1 Spam OOF cao nhất; hòa thì Ham FPR thấp hơn. Ngưỡng mặc định NB/LR 0,5, SVM 0. OOF sau tìm kiếm không phải điểm test độc lập.",
    )
    cell(
        "code",
        """
from sklearn.base import clone
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.naive_bayes import MultinomialNB, ComplementNB
from sklearn.pipeline import make_pipeline
from sklearn.svm import LinearSVC

models = {
    'english_baseline': make_pipeline(TfidfVectorizer(ngram_range=(1, 2), sublinear_tf=True), MultinomialNB(alpha=0.5)),
    'english_complement_nb': make_pipeline(TfidfVectorizer(ngram_range=(1, 2), sublinear_tf=True), ComplementNB(alpha=0.5)),
    'english_logreg': candidates()['english_logreg'],
    'english_word_svm': make_pipeline(TfidfVectorizer(ngram_range=(1, 2), sublinear_tf=True), LinearSVC(C=1, random_state=2026)),
    'english_char_svm': candidates()['english_char_svm'],
}
models['english_tuned_nb'] = clone(models['english_baseline'])
fixed = {name: clone(model) for name, model in models.items()}
grids = parameter_grid()
tuned, trials = tune_on_train(train.text.tolist(), train.label.tolist(), {name: models[name] for name in grids}, grids)
for name, chosen in tuned.items():
    models[name][-1].set_params(**chosen['parameters'])
_, baseline_cv = select_on_train(train.text.tolist(), train.label.tolist(), {'english_baseline': models['english_baseline']})
cv = {**baseline_cv, **{name: chosen['report'] for name, chosen in tuned.items()}}
selected = min(cv, key=lambda name: (-(cv[name]['metrics']['spam_f1'] or 0), cv[name]['metrics']['ham_fpr'] or 0, name))
print('Cấu hình chọn từ train:', selected, tuned[selected]['parameters'])
""",
    )
    cell(
        "markdown",
        "## 4. Đánh giá trên cùng validation\n\nGiữ lựa chọn từ train dù NB alpha=0,1 có điểm validation cao hơn. Đây là so sánh các pipeline đã tối ưu; không chứng minh riêng biểu diễn ký tự luôn tốt hơn biểu diễn từ.",
    )
    cell(
        "code",
        """
import joblib
import pandas as pd
from sklearn.metrics import average_precision_score, precision_recall_curve

reports, fixed_reports, predictions, scores, curves = {}, {}, {}, {}, {}
for name, model in fixed.items():
    model.fit(train.text, train.label)
    fixed_reports[name] = evaluate(validation.label.tolist(), model.predict(validation.text).tolist())
for name, model in models.items():
    model.fit(train.text, train.label)
    predicted = model.predict(validation.text).tolist()
    score = model.decision_function(validation.text) if hasattr(model, 'decision_function') else model.predict_proba(validation.text)[:, list(model.classes_).index('spam')]
    reports[name] = evaluate(validation.label.tolist(), predicted)
    predictions[name], scores[name] = predicted, score.tolist()
    precision, recall, _ = precision_recall_curve(validation.label == 'spam', score)
    curves[name] = {'precision': precision.tolist(), 'recall': recall.tolist(), 'average_precision': float(average_precision_score(validation.label == 'spam', score))}
evidence = {
    'quality': quality, 'train_support': train.label.value_counts().to_dict(),
    'validation_support': validation.label.value_counts().to_dict(),
    'selected_on_train': selected, 'train_cv': cv,
    'chosen_parameters': {name: row['parameters'] for name, row in tuned.items()},
    'fixed_reports': fixed_reports, 'reports': reports, 'curves': curves,
    'validation_ids': validation.source_id.tolist(), 'truth': validation.label.tolist(),
    'predictions': predictions, 'scores': scores,
}
table = pd.DataFrame([{'Mô hình': NAMES[name].replace('\\n', ', '), 'F1 OOF (%)': 100*cv[name]['metrics']['spam_f1'], **{key: 100*value for key, value in reports[name]['metrics'].items()}, **reports[name]['confusion']} for name in NAMES])
table
""",
    )
    cell(
        "markdown",
        "## 5. Kiểm tra kết quả so với slides\n\nĐối chiếu toàn bộ 30 dãy dự đoán OOF, cấu hình được chọn, 6 dãy dự đoán validation, bảng chỉ số và thống kê từ. Nếu khác, notebook dừng thay vì hiển thị kết quả như đã khớp.",
    )
    cell(
        "code",
        """
assert selected == EXPECTED['selected_on_train']
assert cv == EXPECTED['train_cv']
assert evidence['chosen_parameters'] == EXPECTED['chosen_parameters']
assert reports == EXPECTED['reports']
for name in predictions:
    assert digest(predictions[name]) == EXPECTED['predictions_sha256'][name], name
for name in trials:
    assert [digest(row['oof_predictions']) for row in trials[name]] == EXPECTED['oof_sha256'][name], name
keywords = keyword_statistics(train)
assert hashlib.sha256(json.dumps(keywords, sort_keys=True, ensure_ascii=False).encode()).hexdigest() == EXPECTED['keywords_sha256']
assert [train.loc[train.label == label, 'text'].str.len().median() for label in ('ham', 'spam')] == [53, 149]
print('Khớp cấu hình, dự đoán, bảng chỉ số và thống kê từ của slides.')
""",
    )
    cell(
        "markdown",
        "## 6. Tạo hình dùng trong slides\n\nSlide 3 dùng tỷ lệ nhãn của 5.574 tin nguồn. Slide 4 và 5 chỉ thống kê train. Word cloud thể hiện tần suất tương đối trong từng lớp, không phải xác suất Spam. Slide 10 dùng cùng validation cho baseline và SVM balanced. Các bảng và biểu đồ phụ lục lấy từ cùng kết quả bên trên.",
    )
    cell(
        "code",
        """
from IPython.display import display, Image as DisplayImage
from PIL import Image as RasterImage

output = ROOT / 'results'
output.mkdir()
render(ROOT, output, train, evidence, keywords)
slide_images = render_slides(output, evidence, keywords)
for name, expected_hash in EXPECTED['slide_pixels'].items():
    with RasterImage.open(output / 'slides' / name) as raster:
        assert hashlib.sha256(raster.convert('RGB').tobytes()).hexdigest() == expected_hash, name
for image in slide_images:
    print(image.name)
    display(DisplayImage(filename=str(image), width=1050))
""",
    )
    cell(
        "markdown",
        "## 7. Lưu kết quả và thử dự đoán\n\nĐiểm SVM là decision_function, không phải phần trăm xác suất. Ham không chứng minh an toàn; Spam không xác nhận lừa đảo. Corpus nhỏ, có 19 tin validation gần trùng train. Chưa có test độc lập để kết luận hiệu quả trên SMS thực tế.",
    )
    cell(
        "code",
        """
import shutil

write_json(output / 'evidence.json', evidence)
write_json(output / 'selection.json', {'selected': selected, 'chosen_parameters': evidence['chosen_parameters'], 'train_cv': cv, 'trials': trials})
write_json(output / 'keywords.json', keywords)
table.to_csv(output / 'metrics.csv', index=False, encoding='utf-8-sig')
joblib.dump(models[selected], output / 'selected_model.joblib')
bundle = shutil.make_archive(str(Path.cwd() / 'AI05_ket_qua'), 'zip', output)
print('Đã lưu:', bundle)
message = 'Hey, are we meeting at the library tomorrow?'
print('Nhãn:', models[selected].predict([message])[0])
print('Điểm SVM:', float(models[selected].decision_function([message])[0]))
""",
    )
    notebook = {
        "nbformat": 4,
        "nbformat_minor": 5,
        "metadata": {
            "kernelspec": {
                "display_name": "Python 3",
                "language": "python",
                "name": "python3",
            },
            "language_info": {"name": "python", "version": "3.12"},
            "colab": {"provenance": []},
        },
        "cells": [
            {"id": f"ai05-{index:02d}", **item} for index, item in enumerate(cells)
        ],
    }
    checked = subprocess.run(
        [
            sys.executable,
            "-m",
            "ruff",
            "check",
            "--select",
            "I",
            "--fix",
            "--stdin-filename",
            "AI05_Colab.ipynb",
            "-",
        ],
        input=json.dumps(notebook, ensure_ascii=False),
        text=True,
        capture_output=True,
        check=True,
    )
    formatted = subprocess.run(
        [
            sys.executable,
            "-m",
            "ruff",
            "format",
            "--stdin-filename",
            "AI05_Colab.ipynb",
            "-",
        ],
        input=checked.stdout,
        text=True,
        capture_output=True,
        check=True,
    )
    return json.loads(formatted.stdout)


if __name__ == "__main__":
    root = Path(__file__).resolve().parents[1]
    target = root / "AI05_Colab.ipynb"
    target.write_text(
        json.dumps(build(root), ensure_ascii=False, indent=1) + "\n", encoding="utf-8"
    )
    print(target)
