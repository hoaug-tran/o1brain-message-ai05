from pathlib import Path

import numpy as np
from matplotlib import pyplot as plt
from PIL import Image


def render_slides(report: Path, evidence: dict, keywords: dict) -> list[Path]:
    target = report / "slides"
    target.mkdir(exist_ok=True)
    crops = {
        "slide04_lengths.png": ("03_lengths.png", (0, 130, 2340, 1080)),
        "slide05_ham.png": ("06_ham_wordcloud.png", (164, 263, 1634, 1306)),
        "slide05_spam.png": ("06_spam_wordcloud.png", (168, 262, 1628, 1265)),
    }
    for name, (source, bounds) in crops.items():
        with Image.open(report / "figures" / source) as raster:
            if raster.width < bounds[2] or raster.height < bounds[3]:
                raise ValueError("Unexpected report figure geometry")
            raster.crop(bounds).save(target / name)
    with plt.rc_context({"font.family": "DejaVu Sans", "font.size": 15}):
        figure, axis = plt.subplots(figsize=(7, 6))
        raw = evidence["quality"]["class_distribution_raw"]
        axis.pie(
            [raw["ham"], raw["spam"]],
            labels=["Ham", "Spam"],
            colors=["#246878", "#c65432"],
            autopct="%.2f%%",
            startangle=90,
            wedgeprops={"width": 0.35},
        )
        figure.savefig(
            target / "slide03_classes.png",
            dpi=180,
            bbox_inches="tight",
            facecolor="white",
        )
        plt.close(figure)
        figure = plt.figure(figsize=(17, 6.3))
        grid = figure.add_gridspec(1, 3, width_ratios=[1, 1, 0.06], wspace=0.48)
        for index, (name, title) in enumerate(
            (
                ("english_baseline", "Baseline BTC"),
                (evidence["selected_on_train"], "SVM ký tự, C = 1, balanced"),
            )
        ):
            axis = figure.add_subplot(grid[index])
            c = evidence["reports"][name]["confusion"]
            matrix = np.array([[c["tn"], c["fp"]], [c["fn"], c["tp"]]])
            image = axis.imshow(matrix, cmap="Blues", vmin=0, vmax=904)
            for row in range(2):
                for column in range(2):
                    axis.text(
                        column,
                        row,
                        str(matrix[row, column]),
                        ha="center",
                        va="center",
                        fontsize=25,
                        color="white" if matrix[row, column] > 500 else "#182b3a",
                    )
            axis.set(
                title=title,
                xticks=[0, 1],
                xticklabels=["Ham", "Spam"],
                yticks=[0, 1],
                yticklabels=["Ham", "Spam"],
                xlabel="Nhãn dự đoán",
                ylabel="Nhãn thực tế",
            )
        figure.colorbar(image, cax=figure.add_subplot(grid[2])).set_label("Số tin nhắn")
        figure.savefig(
            target / "slide10_confusion.png",
            dpi=180,
            bbox_inches="tight",
            facecolor="white",
        )
        plt.close(figure)
        names = [
            "english_tuned_nb",
            "english_complement_nb",
            "english_logreg",
            "english_word_svm",
            "english_char_svm",
        ]
        labels = [
            "Multinomial NB",
            "Complement NB",
            "Logistic Regression",
            "SVM theo từ",
            "SVM theo ký tự",
        ]
        figure, axis = plt.subplots(figsize=(13, 6))
        x = np.arange(len(names))
        for offset, key, title, color in (
            (-0.2, "fixed_reports", "Trước tối ưu", "#718096"),
            (0.2, "reports", "Sau chọn tham số", "#246878"),
        ):
            bars = axis.bar(
                x + offset,
                [100 * evidence[key][name]["metrics"]["spam_f1"] for name in names],
                width=0.4,
                label=title,
                color=color,
            )
            axis.bar_label(bars, fmt="%.2f", fontsize=10, padding=3)
        axis.set(xticks=x, xticklabels=labels, ylabel="F1 lớp Spam (%)", ylim=(0, 110))
        axis.legend(loc="upper left", fontsize=11)
        figure.savefig(
            target / "slide16_tuning.png",
            dpi=180,
            bbox_inches="tight",
            facecolor="white",
        )
        plt.close(figure)
        figure, axes = plt.subplots(1, 2, figsize=(13, 6))
        for axis, label, color in zip(
            axes, ("ham", "spam"), ("#246878", "#c65432"), strict=True
        ):
            rows = keywords["labels"][label]["words"][:8][::-1]
            bars = axis.barh(
                [row["term"] for row in rows],
                [row["occurrences"] for row in rows],
                color=color,
            )
            axis.bar_label(bars, padding=3)
            axis.set(title=label.capitalize(), xlabel="Tổng lượt xuất hiện")
            axis.set_xlim(right=max(row["occurrences"] for row in rows) * 1.18)
        figure.tight_layout(w_pad=4)
        figure.savefig(
            target / "slide17_words.png",
            dpi=180,
            bbox_inches="tight",
            facecolor="white",
        )
        plt.close(figure)
    return sorted(target.glob("*.png"))
