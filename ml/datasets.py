import argparse
import csv
import hashlib
import json
import unicodedata
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.model_selection import train_test_split
from sklearn.neighbors import NearestNeighbors

from ml.common import sha256, write_json

DATASET_ID = "uci_sms_spam_en_v1"
SPLIT_ID = "btc_dev_80_20_seed2026_v1"


def normalize(text: str) -> str:
    return " ".join(unicodedata.normalize("NFC", text).casefold().split())


def load_btc(path: Path) -> tuple[pd.DataFrame, dict[str, object]]:

    frame = pd.read_csv(
        path,
        sep="\t",
        header=None,
        names=["label", "text"],
        encoding="latin-1",
        engine="python",
        quoting=csv.QUOTE_NONE,
    )
    if frame.empty or frame.isna().any().any():
        raise ValueError("Dataset empty or contains missing label/text")
    if not frame.label.isin(["ham", "spam"]).all():
        raise ValueError("Dataset labels must be exactly ham/spam")
    if not frame.text.map(
        lambda text: isinstance(text, str) and bool(text.strip())
    ).all():
        raise ValueError("Dataset contains invalid or empty text")
    frame["source_row_index"] = frame.index
    frame["normalized"] = frame.text.astype(str).str.strip().str.lower()
    bad = frame.groupby("normalized").label.nunique()
    ambiguous = set(bad[bad > 1].index)
    conflicts = frame.normalized.isin(ambiguous)
    clean = frame[~conflicts].drop_duplicates("normalized").copy()
    clean["source_id"] = clean.apply(
        lambda row: hashlib.sha256(
            f"{row.source_row_index}\0{row.normalized}".encode()
        ).hexdigest(),
        axis=1,
    )
    clean["audit_normalized"] = clean.text.map(normalize)
    report: dict[str, object] = {
        "source_rows": len(frame),
        "clean_rows": len(clean),
        "class_distribution_raw": {
            str(k): int(v) for k, v in frame.label.value_counts().items()
        },
        "class_distribution_clean": {
            str(k): int(v) for k, v in clean.label.value_counts().items()
        },
        "exact_duplicate_excess_rows": int(frame.text.duplicated().sum()),
        "btc_normalized_duplicate_excess_rows": int(
            frame.normalized.duplicated().sum()
        ),
        "quarantined_conflict_groups": len(ambiguous),
        "quarantined_conflict_rows": int(conflicts.sum()),
        "quarantined_ids": [
            hashlib.sha256(text.encode()).hexdigest() for text in sorted(ambiguous)
        ],
        "character_length_by_class": {
            label: {
                "min": int(lengths.min()),
                "median": float(lengths.median()),
                "max": int(lengths.max()),
                "mean": float(lengths.mean()),
            }
            for label in ("ham", "spam")
            if not (lengths := clean.loc[clean.label == label, "text"].str.len()).empty
        },
        "word_count_by_class": {
            label: float(
                clean.loc[clean.label == label, "text"].str.split().str.len().mean()
            )
            for label in ("ham", "spam")
        },
    }
    if set(clean.label) != {"ham", "spam"}:
        raise ValueError("Both classes required after quarantine")
    return clean, report


def split_btc(frame: pd.DataFrame) -> tuple[pd.DataFrame, pd.DataFrame]:
    train, validation = train_test_split(
        frame, test_size=0.2, stratify=frame.label, random_state=2026
    )
    assert_disjoint(train, validation, "normalized")
    return train, validation


def assert_disjoint(train: pd.DataFrame, validation: pd.DataFrame, column: str) -> None:
    if set(train[column]) & set(validation[column]):
        raise ValueError(f"Cross-partition overlap in {column}")


def near_overlap(train: pd.DataFrame, validation: pd.DataFrame) -> dict[str, object]:

    vectorizer = TfidfVectorizer(analyzer="char", ngram_range=(3, 5), dtype=np.float64)
    matrix = vectorizer.fit_transform(
        list(train.audit_normalized) + list(validation.audit_normalized)
    )
    index = NearestNeighbors(
        n_neighbors=1, metric="cosine", algorithm="brute", n_jobs=1
    ).fit(matrix[: len(train)])
    distance, neighbors = index.kneighbors(matrix[len(train) :])
    pairs = [
        {
            "train_id": str(train.iloc[int(neighbors[i, 0])].source_id),
            "validation_id": str(validation.iloc[i].source_id),
            "cosine": float(1 - distance[i, 0]),
        }
        for i in range(len(validation))
        if 1 - distance[i, 0] >= 0.9
    ]
    return {
        "method": "char TF-IDF 3-5, nearest training neighbor cosine >= 0.9",
        "threshold": 0.9,
        "validation_rows_at_or_above_threshold": len(pairs),
        "pairs": pairs,
        "scope": "audit_only; not model features; BTC split preserved",
    }


def prepare(root: Path, dataset: Path) -> dict[str, object]:
    frame, quality = load_btc(dataset)
    train, validation = split_btc(frame)
    manifest = {
        "dataset_id": DATASET_ID,
        "source_sha256": sha256(dataset),
        "split_id": SPLIT_ID,
        "seed": 2026,
        "grouping": "BTC strip/lower dedup; conflicting groups quarantined",
        "encoding": "latin-1 (verbatim BTC parser compatibility)",
        "train": list(train.source_id),
        "validation": list(validation.source_id),
        "test": [],
        "test_status": "No hidden test per supplied DOCX 2.5. Development validation only.",
        "counts": {"train": len(train), "validation": len(validation), "test": 0},
        "support": {
            name: {str(k): int(v) for k, v in part.label.value_counts().items()}
            for name, part in (("train", train), ("validation", validation))
        },
    }
    destination = root / "data/manifests/english_split.json"
    write_json(destination, manifest, immutable=True)
    quality.update(
        {
            "dataset_id": DATASET_ID,
            "source_sha256": sha256(dataset),
            "split_sha256": sha256(destination),
            "btc_normalized_cross_split": 0,
            "strict_normalized_cross_split": len(
                set(train.audit_normalized) & set(validation.audit_normalized)
            ),
            "near_duplicate_audit": near_overlap(train, validation),
            "published_count": 5574,
            "published_count_matches": quality["source_rows"] == 5574,
        }
    )
    write_json(root / "data/manifests/data_quality.json", quality)
    source_manifest = root / "data/manifests/sources.json"
    provenance = (
        json.loads(source_manifest.read_text()) if source_manifest.exists() else {}
    )
    for name, key in (
        ("Baseline.zip", "baseline_zip_sha256"),
        ("Đề thi chung kết cuộc thi Olympic AI.docx", "docx_sha256"),
    ):
        if (root / name).is_file():
            provenance[key] = sha256(root / name)
    provenance.update(
        {
            "dataset_id": DATASET_ID,
            "source_sha256": sha256(dataset),
            "baseline_code_sha256": sha256(root / "vendor/btc/AI05/train.py"),
            "source_kind": "public_UCI_download_using_BTC_source; not BTC-distributed bytes",
            "license_review_status": "BTC SOURCES.md declares UCI CC BY 4.0; original readme restrictions retained in data/raw/readme; no dataset redistribution in package",
        }
    )
    write_json(root / "data/manifests/sources.json", provenance, immutable=True)
    return quality


def main() -> None:
    parser = argparse.ArgumentParser(
        description="BTC-compatible English input audit; no text is emitted in evidence."
    )
    parser.add_argument("--data", type=Path, default=Path("data/raw/SMSSpamCollection"))
    args = parser.parse_args()
    quality = prepare(Path.cwd(), args.data)
    print(
        json.dumps(
            {
                key: value
                for key, value in quality.items()
                if key != "near_duplicate_audit"
            },
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
