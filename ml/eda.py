import argparse
import csv
import html
import json
import re
import unicodedata
import zipfile
from collections import Counter
from pathlib import Path
from random import Random

import joblib
import numpy as np
import pandas as pd
from matplotlib import pyplot as plt
from matplotlib.figure import Figure
from PIL import Image, ImageDraw
from sklearn.base import clone
from sklearn.feature_extraction.text import ENGLISH_STOP_WORDS, TfidfVectorizer
from sklearn.metrics import average_precision_score, precision_recall_curve
from sklearn.naive_bayes import ComplementNB
from sklearn.pipeline import make_pipeline
from sklearn.svm import LinearSVC
from wordcloud import WordCloud

from ai05.infrastructure.models import ModelRegistry
from ml.benchmark import candidates, parameter_grid, select_on_train, tune_on_train
from ml.common import environment, sha256, source_hash, write_json
from ml.datasets import load_btc, split_btc
from ml.metrics import evaluate

plt.switch_backend("Agg")

LABELS = ("ham", "spam")
COLORS = ("#246878", "#c65432")


def make_wordcloud(words: list[dict], label: str, font: Path) -> WordCloud:
    if label not in LABELS or not words:
        raise ValueError("Nonempty ham/spam frequencies required")
    frequencies = {row["term"]: row["occurrences"] for row in words}
    if len(frequencies) != len(words) or any(
        not re.fullmatch(r"[a-z]{3,}", term) or type(count) is not int or count <= 0
        for term, count in frequencies.items()
    ):
        raise ValueError("Unique whole words and positive integer counts required")
    mask = Image.new("L", (1200, 900), 255)
    draw = ImageDraw.Draw(mask)
    draw.rounded_rectangle((30, 30, 1170, 735), radius=115, fill=0)
    draw.polygon(((180, 710), (180, 865), (380, 710)), fill=0)
    palettes = {
        "ham": ("#115c70", "#257c80", "#326b94", "#218e7d", "#527c56", "#4b6590"),
        "spam": ("#9f3444", "#bb4a32", "#b36a30", "#823f65", "#b9454b", "#865442"),
    }

    def color(
        *args: object, random_state: Random | None = None, **kwargs: object
    ) -> str:
        return palettes[label][
            (random_state or Random(2026)).randrange(len(palettes[label]))
        ]

    return WordCloud(
        font_path=str(font),
        mask=np.asarray(mask),
        width=1200,
        height=900,
        background_color="white",
        color_func=color,
        random_state=2026,
        max_words=100,
        min_font_size=14,
        max_font_size=150,
        margin=5,
        prefer_horizontal=0.88,
        relative_scaling=0.5,
        scale=2,
        collocations=False,
        normalize_plurals=False,
        repeat=False,
    ).generate_from_frequencies(frequencies)


KEYWORD_EXPANSIONS = {
    "u": "you",
    "ur": "your",
    "im": "i am",
    "ive": "i have",
    "youre": "you are",
    "dont": "do not",
    "cant": "can not",
    "wont": "will not",
    "didnt": "did not",
    "doesnt": "does not",
    "isnt": "is not",
    "wasnt": "was not",
    "havent": "have not",
    "hasnt": "has not",
    "txt": "text",
    "txts": "texts",
    "msg": "message",
    "msgs": "messages",
    "sms": "message",
    "mob": "mobile",
    "pls": "please",
    "plz": "please",
    "can't": "can not",
    "won't": "will not",
    "thats": "that is",
    "lemme": "let me",
    "min": "minute",
    "mins": "minutes",
    "gud": "good",
    "thk": "think",
    "dat": "that",
    "dis": "this",
    "cos": "because",
    "coz": "because",
    "wan": "want",
    "wanna": "want to",
    "gonna": "going to",
    "jus": "just",
    "juz": "just",
    "thanx": "thanks",
    "thx": "thanks",
    "til": "until",
    "wif": "with",
    "aight": "alright",
    "freemsg": "free message",
    "pics": "pictures",
}
KEYWORD_NOISE = set(
    "lol haha hahaha yeah yep yup nah hmm ah oh eh lor lah leh lar liao la da dun wat amp quot nbsp lt gt ll ve re".split()
)
KEYWORD_STOP_WORDS = (
    (
        set(ENGLISH_STOP_WORDS)
        - {
            "amount",
            "bill",
            "call",
            "cry",
            "empty",
            "fire",
            "full",
            "interest",
            "system",
        }
    )
    | KEYWORD_NOISE
    | {"just", "did", "does", "doing", "done", "really", "actually", "basically"}
)


def keyword_tokens(text: str) -> list[str | None]:
    normalized = (
        unicodedata.normalize("NFKC", html.unescape(text)).casefold().replace("’", "'")
    )
    normalized = re.sub(r"https?://\S+|www\.\S+|\b\S+@\S+\b", " . ", normalized)
    normalized = re.sub(
        r"\b(?:[a-z0-9-]+\.)+(?:com|net|org|co\.uk|uk|info|biz|mobi)\b\S*",
        " . ",
        normalized,
    )
    tokens: list[str | None] = []
    for token in re.findall(r"\w+(?:'\w+)*|[^\w\s]", normalized):
        token = KEYWORD_EXPANSIONS.get(token, token)
        if token.endswith("n't"):
            token = token[:-3] + " not"
        elif token.endswith(("'re", "'ve", "'ll", "'m", "'d")):
            base, suffix = token.rsplit("'", 1)
            if base.endswith("n't"):
                base = base[:-3] + " not"
            token = (
                base
                + " "
                + {"re": "are", "ve": "have", "ll": "will", "m": "am", "d": "."}[suffix]
            )
        elif token.endswith("'s"):
            token = token[:-2]
        for word in token.split():
            tokens.append(word if re.fullmatch(r"[a-z]+", word) else None)
    return tokens


def keyword_statistics(frame: pd.DataFrame, min_messages: int = 5) -> dict:
    if (
        frame.empty
        or not frame.text.map(
            lambda value: isinstance(value, str) and bool(value.strip())
        ).all()
        or min_messages < 1
    ):
        raise ValueError("Nonempty text and positive message support required")
    if set(frame.label) != set(LABELS):
        raise ValueError("Both ham and spam labels required")
    counts: dict[str, dict[str, Counter]] = {
        label: {"words": Counter(), "phrases": Counter()} for label in LABELS
    }
    documents: dict[str, dict[str, Counter]] = {
        label: {"words": Counter(), "phrases": Counter()} for label in LABELS
    }

    def content(word: str | None) -> bool:
        return word is not None and len(word) >= 3 and word not in KEYWORD_STOP_WORDS

    for label, text in zip(frame.label.tolist(), frame.text.tolist(), strict=True):
        tokens = keyword_tokens(text)
        words = [word for word in tokens if content(word)]
        phrases = []
        for start in range(len(tokens)):
            for length in (2, 3):
                window = tokens[start : start + length]
                if len(window) != length or None in window:
                    continue
                if (
                    (content(window[0]) or window[0] in {"not", "never", "no"})
                    and content(window[-1])
                    and not any(word in KEYWORD_NOISE for word in window)
                ):
                    phrases.append(
                        " ".join(word for word in window if word is not None)
                    )
        for unit, terms in (("words", words), ("phrases", phrases)):
            counts[label][unit].update(terms)
            documents[label][unit].update(set(terms))
    result: dict = {"minimum_messages_across_train": min_messages, "labels": {}}
    support = frame.label.value_counts().to_dict()
    for label in LABELS:
        result["labels"][label] = {"messages": int(support[label])}
        for unit in ("words", "phrases"):
            frequencies = [
                {
                    "term": term,
                    "occurrences": count,
                    "messages": documents[label][unit][term],
                    "message_rate": documents[label][unit][term] / support[label],
                }
                for term, count in counts[label][unit].items()
                if sum(documents[other][unit][term] for other in LABELS) >= min_messages
            ]
            result["labels"][label][unit] = sorted(
                frequencies,
                key=lambda row: (-row["occurrences"], -row["messages"], row["term"]),
            )
        phrase_records = result["labels"][label]["phrases"]
        result["labels"][label]["display_phrases"] = [
            phrase
            for phrase in phrase_records
            if not any(
                phrase["term"] != longer["term"]
                and f" {phrase['term']} " in f" {longer['term']} "
                and longer["occurrences"] >= 0.8 * phrase["occurrences"]
                for longer in phrase_records
            )
        ]
    result["preprocessing"] = {
        "scope": "EDA only; frozen classifier preprocessing unchanged",
        "unicode": "HTML unescape then NFKC/casefold; curly apostrophe normalized",
        "expansions": KEYWORD_EXPANSIONS,
        "stop_words": sorted(KEYWORD_STOP_WORDS),
        "word_policy": "whole ASCII alphabetic tokens, at least 3 letters; no stemming or dictionary-based correction",
        "phrase_policy": "2-3 consecutive tokens; punctuation, URLs, email and invalid tokens break adjacency; content endpoints or leading negation; internal function words retained",
        "ranking": "descending total occurrences, then document frequency, then alphabetical",
        "phrase_display": "prefer longer phrase when it covers at least 80% of occurrences of a contained shorter phrase; complete counts remain in phrases",
        "limitations": "lexical filtering, not semantic understanding or POS tagging; unigrams lose context; inflected forms remain separate",
    }
    return result


NAMES = {
    "english_baseline": "Multinomial NB\nBaseline BTC, từ 1 đến 2",
    "english_tuned_nb": "Multinomial NB\nTối ưu alpha, từ 1 đến 2",
    "english_complement_nb": "Complement NB\nTừ 1 đến 2",
    "english_logreg": "Logistic Regression\nTừ 1 đến 2",
    "english_word_svm": "Linear SVM\nTừ 1 đến 2",
    "english_char_svm": "Linear SVM\nKý tự 3 đến 5",
}


def run(root: Path, output: Path) -> None:
    if output.exists():
        raise ValueError("Preserve existing report; choose a new --output")
    registry = ModelRegistry(root / "artifacts/registry.json")
    if not registry.ready:
        raise ValueError("Pinned UCI models unavailable")
    source = root / "data/raw/SMSSpamCollection"
    frame, quality = load_btc(source)
    train, validation = split_btc(frame)
    manifest = json.loads((root / "data/manifests/english_split.json").read_text())
    if (
        train.source_id.tolist() != manifest["train"]
        or validation.source_id.tolist() != manifest["validation"]
        or sha256(source) != manifest["source_sha256"]
    ):
        raise ValueError("Frozen source or split differs")
    models = {
        "english_baseline": clone(registry.models["english_baseline"]),
        "english_complement_nb": make_pipeline(
            TfidfVectorizer(ngram_range=(1, 2), sublinear_tf=True),
            ComplementNB(alpha=0.5),
        ),
        "english_logreg": candidates()["english_logreg"],
        "english_word_svm": make_pipeline(
            TfidfVectorizer(ngram_range=(1, 2), sublinear_tf=True),
            LinearSVC(C=1, random_state=2026),
        ),
        "english_char_svm": candidates()["english_char_svm"],
    }
    grids = parameter_grid()
    models["english_tuned_nb"] = clone(models["english_baseline"])
    output.mkdir(parents=True)
    with zipfile.ZipFile(
        output / "training_source.zip", "w", zipfile.ZIP_DEFLATED
    ) as snapshot:
        for folder in ("src", "ml", "scripts", "tests", "vendor/btc/AI05"):
            for path in sorted((root / folder).rglob("*.py")):
                snapshot.write(path, path.relative_to(root).as_posix())
        for name in ("pyproject.toml", "uv.lock"):
            snapshot.write(root / name, name)
    write_json(
        output / "plan.json",
        {
            "source_sha256": sha256(source),
            "split_sha256": sha256(root / "data/manifests/english_split.json"),
            "source_code_sha256": source_hash(root),
            "training_snapshot_sha256": sha256(output / "training_source.zip"),
            "environment": environment(),
            "train_ids": train.source_id.tolist(),
            "validation_ids": validation.source_id.tolist(),
            "parameters": {
                name: {k: str(v) for k, v in model.get_params().items()}
                for name, model in models.items()
            },
            "search_grid": grids,
            "selection_cv": {
                "folds": 5,
                "shuffle": True,
                "seed": 2026,
                "vectorizer_fit_within_each_fold": True,
            },
            "selection": "5-fold train OOF Spam F1, tie lower Ham FPR then first declared grid entry; family tie stable ID; default thresholds",
            "scope": "UCI only; reused development validation, not blind final test; runtime registry unchanged",
        },
        immutable=True,
    )
    print(
        "Plan saved. Tuning 30 configurations on identical train-only 5-fold splits.",
        flush=True,
    )
    fixed = {name: clone(model) for name, model in models.items()}
    tuned, trials = tune_on_train(
        train.text.tolist(),
        train.label.tolist(),
        {name: models[name] for name in grids},
        grids,
    )
    for name, chosen in tuned.items():
        models[name][-1].set_params(**chosen["parameters"])
    _, baseline_cv = select_on_train(
        train.text.tolist(),
        train.label.tolist(),
        {"english_baseline": models["english_baseline"]},
    )
    cv = {
        "english_baseline": baseline_cv["english_baseline"],
        **{name: row["report"] for name, row in tuned.items()},
    }
    selected = min(
        cv,
        key=lambda name: (
            -(cv[name]["metrics"]["spam_f1"] or 0),
            cv[name]["metrics"]["ham_fpr"] or 0,
            name,
        ),
    )
    write_json(
        output / "selection.json",
        {
            "selected": selected,
            "train_cv": cv,
            "chosen_parameters": {
                name: row["parameters"] for name, row in tuned.items()
            },
            "trials": trials,
            "train_truth": train.label.tolist(),
        },
        immutable=True,
    )
    fixed_reports = {}
    for name, model in fixed.items():
        model.fit(train.text, train.label)
        fixed_predictions = model.predict(validation.text).tolist()
        if (
            name in registry.models
            and fixed_predictions
            != registry.models[name].predict(validation.text).tolist()
        ):
            raise ValueError(f"Fixed-model prediction parity failed: {name}")
        fixed_reports[name] = evaluate(validation.label.tolist(), fixed_predictions)
    reports, predictions, scores, curves = {}, {}, {}, {}
    for name, model in models.items():
        model.fit(train.text, train.label)
        predicted = model.predict(validation.text).tolist()
        if name == "english_baseline":
            if predicted != registry.models[name].predict(validation.text).tolist():
                raise ValueError(f"Pinned prediction parity failed: {name}")
        reports[name] = evaluate(validation.label.tolist(), predicted)
        predictions[name] = predicted
        score = (
            model.decision_function(validation.text)
            if hasattr(model, "decision_function")
            else model.predict_proba(validation.text)[
                :, list(model.classes_).index("spam")
            ]
        )
        scores[name] = score.tolist()
        precision, recall, _ = precision_recall_curve(validation.label == "spam", score)
        curves[name] = {
            "precision": precision.tolist(),
            "recall": recall.tolist(),
            "average_precision": float(
                average_precision_score(validation.label == "spam", score)
            ),
        }
        print(name, reports[name]["metrics"], flush=True)
    artifact = output / "selected_model.joblib"
    joblib.dump(models[selected], artifact)
    evidence = {
        "selected_artifact_sha256": sha256(artifact),
        "quality": quality,
        "train_support": {
            str(k): int(v) for k, v in train.label.value_counts().items()
        },
        "validation_support": {
            str(k): int(v) for k, v in validation.label.value_counts().items()
        },
        "selected_on_train": selected,
        "train_cv": cv,
        "evaluation_scope": "reused BTC development validation; selection uses training OOF only",
        "chosen_parameters": {name: row["parameters"] for name, row in tuned.items()},
        "fixed_reports": fixed_reports,
        "reports": reports,
        "curves": curves,
        "validation_ids": validation.source_id.tolist(),
        "truth": validation.label.tolist(),
        "predictions": predictions,
        "scores": scores,
        "metric_definitions": {
            "spam_f1": "2TP/(2TP+FP+FN)",
            "spam_precision": "TP/(TP+FP)",
            "ham_fpr": "FP/(FP+TN)",
            "positive_label": "spam",
        },
    }
    write_json(output / "evidence.json", evidence, immutable=True)
    with (output / "metrics.csv").open("w", newline="", encoding="utf-8-sig") as stream:
        writer = csv.writer(stream)
        writer.writerow(
            [
                "model",
                "spam_f1",
                "spam_precision",
                "spam_recall",
                "ham_fpr",
                "tn",
                "fp",
                "fn",
                "tp",
            ]
        )
        for name, report in reports.items():
            writer.writerow(
                [
                    name,
                    *[
                        report["metrics"][k]
                        for k in ("spam_f1", "spam_precision", "spam_recall", "ham_fpr")
                    ],
                    *[report["confusion"][k] for k in ("tn", "fp", "fn", "tp")],
                ]
            )
    keywords = keyword_statistics(train)
    write_json(output / "keywords.json", keywords, immutable=True)
    render(root, output, train, evidence, keywords)


def render(
    root: Path, output: Path, train: pd.DataFrame, evidence: dict, keywords: dict
) -> None:
    plt.rcParams.update(
        {
            "font.family": "DejaVu Sans",
            "font.size": 11,
            "axes.spines.top": False,
            "axes.spines.right": False,
            "svg.fonttype": "none",
        }
    )
    figures = output / "figures"
    figures.mkdir()
    sections = []
    q = evidence["quality"]

    def save(fig: Figure, name: str, title: str) -> None:
        fig.suptitle(title, fontsize=19, fontweight="bold")
        fig.tight_layout(rect=(0.015, 0.025, 0.985, 0.91), h_pad=4, w_pad=3)
        for extension in ("png", "svg"):
            fig.savefig(figures / f"{name}.{extension}", dpi=180, facecolor="white")
        plt.close(fig)
        sections.append((name, title))

    fig, axes = plt.subplots(1, 2, figsize=(13, 6))
    for ax, key, title in zip(
        axes,
        ("class_distribution_raw", "class_distribution_clean"),
        ("Tệp nguồn", "Sau làm sạch BTC"),
        strict=True,
    ):
        values = [q[key][label] for label in LABELS]
        bars = ax.bar(["Ham", "Spam"], values, color=COLORS)
        ax.bar_label(
            bars,
            labels=[f"{n:,} tin\n{n / sum(values):.2%}" for n in values],
            padding=5,
        )
        ax.set(
            title=f"{title}: {sum(values):,} tin",
            ylabel="Số tin nhắn",
            ylim=(0, max(values) * 1.2),
        )
    save(
        fig,
        "01_classes",
        "Phân bố nhãn Ham và Spam",
    )

    counts = [
        q["source_rows"],
        q["quarantined_conflict_rows"],
        q["source_rows"] - q["quarantined_conflict_rows"] - q["clean_rows"],
        q["clean_rows"],
        len(train),
        sum(evidence["validation_support"].values()),
    ]
    fig, ax = plt.subplots(figsize=(13, 6))
    bars = ax.bar(
        [
            "Nguồn",
            "Loại nhãn\nmâu thuẫn",
            "Loại trùng\ncòn lại",
            "Còn lại",
            "Train",
            "Validation",
        ],
        counts,
        color=["#718096", "#c65432", "#c65432", "#246878", "#246878", "#668cac"],
    )
    ax.bar_label(bars, padding=4, fmt="%d")
    ax.set(ylabel="Số tin nhắn", ylim=(0, max(counts) * 1.15))
    save(
        fig,
        "02_quality",
        "Làm sạch và chia dữ liệu",
    )

    fig, axes = plt.subplots(1, 2, figsize=(13, 6))
    for ax, unit in zip(axes, ("Ký tự", "Từ tách theo khoảng trắng"), strict=True):
        arrays = [
            train.loc[train.label == label, "text"]
            .map(len if unit == "Ký tự" else lambda t: len(t.split()))
            .to_numpy()
            for label in LABELS
        ]
        edges = np.linspace(0, max(a.max() for a in arrays) + 1, 35)
        for label, color, lengths in zip(LABELS, COLORS, arrays, strict=True):
            ax.hist(
                lengths,
                bins=edges,
                weights=np.full(len(lengths), 100 / len(lengths)),
                histtype="step",
                linewidth=2,
                color=color,
                label=f"{label.capitalize()}: trung vị {np.median(lengths):g}, tối đa {lengths.max()}",
            )
        ax.set(xlabel=unit, ylabel="Tỷ lệ tin trong từng lớp (%)")
        ax.legend(fontsize=9)
    save(
        fig,
        "03_lengths",
        "Độ dài tin nhắn theo lớp",
    )

    patterns = {
        "Có chữ số": r"\d",
        "Có URL": r"(?i)https?://|www\.",
        "Có tiền tệ": r"[$£€]",
        "Có dấu !": r"!",
        "Có từ viết hoa\nít nhất 3 chữ": r"\b[A-Z]{3,}\b",
    }
    signal_counts = {
        label: [
            int(
                train.loc[train.label == label, "text"]
                .str.contains(pattern, regex=True)
                .sum()
            )
            for pattern in patterns.values()
        ]
        for label in LABELS
    }
    fig, ax = plt.subplots(figsize=(13, 6))
    x = np.arange(len(patterns))
    for offset, label, color in zip((-0.2, 0.2), LABELS, COLORS, strict=True):
        n = keywords["labels"][label]["messages"]
        values = [100 * c / n for c in signal_counts[label]]
        bars = ax.bar(
            x + offset, values, 0.4, color=color, label=f"{label.capitalize()} (n={n})"
        )
        ax.bar_label(
            bars,
            labels=[
                f"{v:.1f}%\n{c}/{n}"
                for v, c in zip(values, signal_counts[label], strict=True)
            ],
            fontsize=9,
            padding=3,
        )
    ax.set(
        xticks=x,
        xticklabels=list(patterns),
        ylabel="Tỷ lệ tin trong từng lớp (%)",
        ylim=(0, 115),
    )
    ax.legend()
    write_json(output / "signals.json", {"patterns": patterns, "counts": signal_counts})
    save(
        fig,
        "05_signals",
        "Dấu hiệu văn bản cơ bản",
    )

    for name, unit, title in (
        ("06_tokens", "words", "12 từ xuất hiện nhiều nhất trong từng lớp"),
        (
            "07_phrases",
            "display_phrases",
            "10 cụm từ xuất hiện nhiều nhất trong từng lớp",
        ),
    ):
        fig, axes = plt.subplots(1, 2, figsize=(14, 8))
        for ax, label, color in zip(axes, LABELS, COLORS, strict=True):
            rows = keywords["labels"][label][unit][: 12 if unit == "words" else 10][
                ::-1
            ]
            bars = ax.barh(
                [r["term"] for r in rows], [r["occurrences"] for r in rows], color=color
            )
            ax.bar_label(bars, padding=3)
            ax.set(
                title=f"{label.capitalize()}: {keywords['labels'][label]['messages']} tin",
                xlabel="Tổng lượt xuất hiện",
            )
            ax.set_xlim(right=max(r["occurrences"] for r in rows) * 1.18)
        save(
            fig,
            name,
            title,
        )

    font = root / "android/app/src/main/res/font/manrope.ttf"
    for label in LABELS:
        cloud = make_wordcloud(keywords["labels"][label]["words"], label, font)
        fig, ax = plt.subplots(figsize=(10, 8))
        ax.imshow(cloud.to_array())
        ax.axis("off")
        save(
            fig,
            f"06_{label}_wordcloud",
            f"Từ phổ biến trong tin {label.capitalize()}",
        )

    names = list(NAMES)
    fig, axes = plt.subplots(1, 3, figsize=(17, 8))
    for ax, key, title in zip(
        axes,
        ("spam_f1", "spam_precision", "ham_fpr"),
        (
            "F1 lớp Spam (%)",
            "Precision lớp Spam (%)",
            "False Positive Rate trên Ham (%)",
        ),
        strict=True,
    ):
        values = [100 * evidence["reports"][n]["metrics"][key] for n in names]
        bars = ax.barh(
            [NAMES[n] for n in names],
            values,
            color=["#718096" if n == "english_baseline" else "#246878" for n in names],
        )
        ax.bar_label(bars, fmt="%.2f", padding=4)
        ax.set(
            title=title,
            xlabel="Tỷ lệ (%)",
            xlim=(0, max(100 if key != "ham_fpr" else 1, max(values)) * 1.13),
        )
        ax.invert_yaxis()
    save(
        fig,
        "08_models",
        "Baseline BTC và các cấu hình sau tối ưu",
    )

    fig = plt.figure(figsize=(15, 10))
    grid = fig.add_gridspec(2, 4, width_ratios=[1, 1, 1, 0.055])
    axes = np.array(
        [[fig.add_subplot(grid[row, col]) for col in range(3)] for row in range(2)]
    )
    vmax = max(max(r["confusion"].values()) for r in evidence["reports"].values())
    for ax, name in zip(axes.flat, names):
        c = evidence["reports"][name]["confusion"]
        matrix = np.array([[c["tn"], c["fp"]], [c["fn"], c["tp"]]])
        image = ax.imshow(matrix, cmap="Blues", vmin=0, vmax=vmax)
        for i in range(2):
            for j in range(2):
                ax.text(
                    j,
                    i,
                    f"{matrix[i, j]}",
                    ha="center",
                    va="center",
                    fontsize=18,
                    color="white" if matrix[i, j] > vmax * 0.55 else "#182b3a",
                )
        ax.set(
            title=NAMES[name],
            xticks=[0, 1],
            xticklabels=["Ham", "Spam"],
            yticks=[0, 1],
            yticklabels=["Ham", "Spam"],
            xlabel="Nhãn dự đoán",
            ylabel="Nhãn thực tế",
        )
    color_axis = fig.add_subplot(grid[:, 3])
    fig.colorbar(image, cax=color_axis, ticks=[0, 200, 400, 600, 800, vmax]).set_label(
        "Số tin nhắn"
    )
    save(
        fig,
        "09_confusion",
        "Ma trận nhầm lẫn sau tối ưu",
    )

    fig, ax = plt.subplots(figsize=(12, 7))
    for name in names:
        curve = evidence["curves"][name]
        ax.step(
            curve["recall"],
            curve["precision"],
            where="post",
            label=f"{NAMES[name].replace(chr(10), ', ')}: AP={curve['average_precision']:.4f}",
        )
    ax.set(
        xlabel="Recall lớp Spam",
        ylabel="Precision lớp Spam",
        xlim=(0, 1.01),
        ylim=(0, 1.03),
    )
    ax.legend(loc="lower left", fontsize=10)
    save(
        fig,
        "10_precision_recall",
        "Precision và Recall sau tối ưu",
    )

    compared = [name for name in names if name != "english_baseline"]
    fig, axes = plt.subplots(1, 3, figsize=(17, 8))
    y = np.arange(len(compared))
    for ax, key, title in zip(
        axes,
        ("spam_f1", "spam_precision", "ham_fpr"),
        ("F1 lớp Spam (%)", "Precision lớp Spam (%)", "FPR trên Ham (%)"),
        strict=True,
    ):
        for offset, source, label, color in (
            (-0.19, evidence["fixed_reports"], "Trước tối ưu", "#718096"),
            (0.19, evidence["reports"], "Sau tối ưu", "#246878"),
        ):
            values = [100 * source[name]["metrics"][key] for name in compared]
            bars = ax.barh(y + offset, values, 0.38, label=label, color=color)
            ax.bar_label(bars, fmt="%.2f", padding=3, fontsize=9)
        ax.set(
            yticks=y,
            yticklabels=[NAMES[name] for name in compared],
            title=title,
            xlabel="Tỷ lệ (%)",
            xlim=(
                0,
                113
                if key != "ham_fpr"
                else max(1, max(bar.get_width() for bar in ax.patches)) * 1.2,
            ),
        )
        ax.invert_yaxis()
        ax.set_ylim(len(compared) - 0.5, -1.3)
        ax.legend(loc="upper right", fontsize=9)
    save(fig, "11_tuning", "Kết quả trước và sau chọn siêu tham số")

    parameters = "".join(
        "<tr><td>"
        + html.escape(NAMES[name].replace("\n", ", "))
        + "</td><td>"
        + html.escape(str(evidence["chosen_parameters"].get(name, {"alpha": 0.5})))
        + f"</td><td>{100 * evidence['train_cv'][name]['metrics']['spam_f1']:.4f}%</td></tr>"
        for name in names
    )
    rows = "".join(
        "<tr><td>"
        + html.escape(NAMES[n].replace("\n", ", "))
        + "</td>"
        + "".join(
            f"<td>{100 * r['metrics'][k]:.4f}%</td>"
            for k in ("spam_f1", "spam_precision", "spam_recall", "ham_fpr")
        )
        + "".join(f"<td>{r['confusion'][k]}</td>" for k in ("tn", "fp", "fn", "tp"))
        + "</tr>"
        for n, r in ((n, evidence["reports"][n]) for n in names)
    )
    content = "".join(
        f'<section><h2>{html.escape(title)}</h2><img src="figures/{name}.png" alt="{html.escape(title)}"><a href="figures/{name}.svg">Bản SVG</a></section>'
        for name, title in sections
    )
    validation_best = max(
        names, key=lambda name: evidence["reports"][name]["metrics"]["spam_f1"]
    )
    document = f"""<!doctype html><html lang="vi"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>UCI SMS Spam Collection: dữ liệu và mô hình</title><link rel="stylesheet" href="style.css"><main><h1>UCI SMS Spam Collection</h1><p>Phân tích dữ liệu và so sánh mô hình AI05. Chỉ huấn luyện trên UCI tiếng Anh. Mọi mô hình dùng cùng tập huấn luyện và validation BTC đã khóa.</p><p>Có 30 cấu hình siêu tham số được thử trên cùng 5 fold train. NB/Complement NB: alpha = 0.1, 0.5, 1. Logistic Regression và hai SVM: C = 0.1, 1, 4, 10; class_weight = None hoặc balanced. Baseline BTC giữ TF-IDF từ 1 đến 2, sublinear_tf=True, MultinomialNB alpha=0.5. Adapter đọc TSV bằng QUOTE_NONE để giữ dấu nháy nguyên văn; không sửa mã vendor. Mọi vocabulary và IDF chỉ fit trong train của mỗi fold.</p><p>Lựa chọn trên train bằng 5-fold OOF Spam F1, hòa thì Ham FPR thấp hơn, rồi ID. Mỗi họ mô hình chọn tham số theo cùng tiêu chí OOF; giữ ngưỡng mặc định, không tinh chỉnh trên validation. Mô hình được chọn: {html.escape(NAMES[evidence["selected_on_train"]].replace(chr(10), ", "))}. F1 validation cao nhất thuộc {html.escape(NAMES[validation_best].replace(chr(10), ", "))}: {100 * evidence["reports"][validation_best]["metrics"]["spam_f1"]:.4f}%. Kết quả này không dùng để chọn lại mô hình hoặc tham số. Bảng validation dùng báo cáo, không dùng chọn lại cấu hình. Web và Android bản nộp dùng cùng SVM được chọn bằng CV trên train.</p><p>Validation đã được xem trước, không phải test mù. Audit BTC ghi 19 mẫu validation gần trùng train; loại trùng chính xác không loại hết gần trùng. Không tuyên bố kết quả này bảo đảm hiệu quả ngoài thực tế.</p><div class="table"><table><caption>Tham số được chọn và F1 OOF trên train</caption><tr><th>Cấu hình</th><th>Tham số</th><th>F1 Spam OOF</th></tr>{parameters}</table><table><caption>Cùng validation BTC; Spam là lớp dương</caption><thead><tr><th>Mô hình</th><th>F1 Spam</th><th>Precision Spam</th><th>Recall Spam</th><th>Ham FPR</th><th>TN</th><th>FP</th><th>FN</th><th>TP</th></tr></thead><tbody>{rows}</tbody></table></div>{content}<p>Điểm SVM là khoảng cách quyết định, không phải xác suất tin cậy đã hiệu chỉnh. Điểm predict_proba của NB và Logistic Regression cũng chưa được hiệu chỉnh. Ham không chứng minh an toàn; Spam không xác nhận lừa đảo.</p><p>Nguồn: <a href="https://archive.ics.uci.edu/dataset/228/sms+spam+collection">UCI SMS Spam Collection</a>. Số liệu đầy đủ: evidence.json, metrics.csv, keywords.json, signals.json. Tham số, hash dữ liệu, split và phiên bản thư viện: plan.json. Chọn mô hình và kết quả OOF: selection.json.</p></main></html>"""
    (output / "index.html").write_text(document, encoding="utf-8")
    (output / "style.css").write_text(
        "body{margin:0;background:#f4f6f8;color:#182b3a;font:17px/1.6 system-ui}main{max-width:1400px;margin:auto;padding:32px}section{background:white;padding:24px;margin:28px 0;border-radius:12px}img{width:100%;height:auto}table{border-collapse:collapse;width:100%;background:white}th,td{padding:12px;text-align:left;border-bottom:1px solid #ccd5df}.table{overflow-x:auto}a{color:#15566c}",
        encoding="utf-8",
    )
    write_json(
        output / "figures.json",
        [
            {
                "name": name,
                "title": title,
                "png_sha256": sha256(figures / f"{name}.png"),
                "svg_sha256": sha256(figures / f"{name}.svg"),
            }
            for name, title in sections
        ],
    )
    print(f"Report saved: {output}; {len(sections)} figures", flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="UCI-only report on the frozen BTC split; lexical statistics use train only."
    )
    parser.add_argument("--output", type=Path, default=Path("results/report"))
    args = parser.parse_args()
    run(Path.cwd(), args.output)


if __name__ == "__main__":
    main()
