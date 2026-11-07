import argparse
import html
import json
import shutil
from pathlib import Path

from matplotlib import pyplot as plt

from ai05.infrastructure.models import ModelRegistry
from ml.common import sha256, write_json


def activate(root: Path, run: Path) -> None:
    root = root.resolve()
    run = (root / run).resolve()
    if not run.is_relative_to(root / "results/expanded"):
        raise ValueError("Expanded run must belong to this clone")
    report = json.loads((run / "report.json").read_text())
    plan = report["plan"]
    comparison = json.loads((run / "comparison.json").read_text())
    records = {}
    for name in ("english_baseline", "english_char_svm"):
        folder = root / "artifacts" / name / run.name
        folder.mkdir(parents=True, exist_ok=True)
        for source, target in (
            (run / (name + ".joblib"), folder / "model.joblib"),
            (run / (name + "_metadata.json"), folder / "metadata.json"),
        ):
            if target.exists() and sha256(target) != sha256(source):
                raise ValueError("Existing artifact differs")
            shutil.copy2(source, target)
        records[name] = {
            "artifact": (folder / "model.joblib")
            .relative_to(root / "artifacts")
            .as_posix(),
            "metadata": (folder / "metadata.json")
            .relative_to(root / "artifacts")
            .as_posix(),
            "artifact_sha256": sha256(folder / "model.joblib"),
            "metadata_sha256": sha256(folder / "metadata.json"),
        }
    registry = {
        "active_model_id": "english_char_svm",
        "models": records,
        "run_id": run.name,
        "comparison": (run / "comparison.json").relative_to(root).as_posix(),
        "comparison_sha256": sha256(run / "comparison.json"),
    }
    pending = root / "artifacts/registry.pending.json"
    write_json(pending, registry)
    if not ModelRegistry(pending).ready:
        raise ValueError("Expanded registry failed artifact validation")
    pending.replace(root / "artifacts/registry.json")
    destination = root / "results/report"
    destination.mkdir(parents=True, exist_ok=True)
    write_json(
        destination / "evidence.json",
        {
            "reports": comparison["reports"],
            "train_count": plan["counts"]["train_total"],
            "train_sources": plan["external_train_sources"],
            "scope": plan["scope"],
        },
    )
    figures = destination / "figures"
    figures.mkdir(exist_ok=True)
    plt.switch_backend("Agg")
    color = ("#246878", "#c65432")
    fig, ax = plt.subplots(figsize=(7, 4), layout="constrained")
    support = comparison["reports"]["english_baseline"]["support"]
    ax.bar(list(support), list(support.values()), color=color)
    ax.set(title="Phân bố nhãn trên tập đánh giá mở rộng", ylabel="Số tin nhắn")
    for i, value in enumerate(support.values()):
        ax.text(i, value, str(value), ha="center", va="bottom")
    fig.savefig(figures / "01_classes.png", dpi=180)
    plt.close(fig)
    fig, axes = plt.subplots(1, 2, figsize=(10, 4), layout="constrained")
    scale = max(support.values())
    for ax, (name, item) in zip(axes, comparison["reports"].items(), strict=True):
        c = item["confusion"]
        matrix = [[c["tn"], c["fp"]], [c["fn"], c["tp"]]]
        image = ax.imshow(matrix, cmap="Blues", vmin=0, vmax=scale)
        ax.set(
            xticks=[0, 1],
            yticks=[0, 1],
            xticklabels=["Ham", "Spam"],
            yticklabels=["Ham", "Spam"],
            xlabel="Nhãn dự đoán",
            ylabel="Nhãn thực tế",
            title="Baseline NB" if name == "english_baseline" else "Linear SVM",
        )
        for row in range(2):
            for col in range(2):
                value = matrix[row][col]
                ax.text(
                    col,
                    row,
                    str(value),
                    ha="center",
                    va="center",
                    color="white" if value > scale / 2 else "#132B42",
                )
    fig.colorbar(image, ax=axes, label="Số tin nhắn")
    fig.savefig(figures / "09_confusion.png", dpi=180)
    fig.savefig(figures / "09_confusion.svg")
    plt.close(fig)
    rows = []
    for name, item in comparison["reports"].items():
        metrics = item["metrics"]
        rows.append(
            "<tr><td>"
            + html.escape(name)
            + "</td>"
            + "".join(
                f"<td>{100 * metrics[k]:.2f}%</td>"
                for k in ("spam_f1", "spam_precision", "spam_recall", "ham_fpr")
            )
            + "</tr>"
        )
    (destination / "index.html").write_text(
        f"""<!doctype html><html lang="vi"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><link rel="stylesheet" href="style.css"><title>Baseline NB và Linear SVM</title><main><h1>Baseline NB và Linear SVM</h1><p>UCI SMS Spam Collection, Mendeley SMS Phishing và India SMS. Bản demo mở rộng riêng, không thuộc kết quả nộp BTC.</p><p>Số tin trong tập huấn luyện: {plan["counts"]["train_total"]}. Đánh giá mở rộng: {plan["counts"]["external_holdout"]} tin ngoài phần huấn luyện, chia theo nhóm gần trùng.</p><table><thead><tr><th>Mô hình</th><th>F1 Spam</th><th>Precision Spam</th><th>Recall Spam</th><th>Ham FPR</th></tr></thead><tbody>{"".join(rows)}</tbody></table><p>Đánh giá phát triển trên các nguồn đã biết, chưa phải kiểm thử mù độc lập hoặc bảo đảm kết quả với mọi tin mới.</p><img src="figures/09_confusion.png" alt="Hai ma trận nhầm lẫn"></main></html>""",
        encoding="utf-8",
    )
    (destination / "style.css").write_text(
        "body{font:18px/1.6 Arial,sans-serif;color:#182b40;margin:40px auto;padding:0 24px;max-width:1100px}table{border-collapse:collapse;width:100%}th,td{text-align:left;padding:12px;border-bottom:1px solid #d5e1ec}th{background:#eff5fb}img{max-width:100%}",
        encoding="utf-8",
    )
    print("Activated two verified expanded models; product web assets unchanged")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        description="Activate a verified expanded training run in the separate demo clone"
    )
    parser.add_argument(
        "--run", type=Path, default=Path("results/expanded/run-20261107")
    )
    args = parser.parse_args()
    activate(Path.cwd(), args.run)
