import argparse
import json
from pathlib import Path

from ai05.infrastructure.models import ModelRegistry
from ml.common import sha256, write_json


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Export selected char LinearSVC without refitting or modifying evidence/SMS."
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("android/app/src/main/assets/english_char_svm.json"),
    )
    args = parser.parse_args()
    registry = ModelRegistry(Path("artifacts/registry.json"))
    if not registry.ready or registry.active != "english_char_svm":
        raise ValueError("Verified selected char SVM required")
    model = registry.models[registry.active]
    vectorizer, classifier = model.steps[0][1], model.steps[1][1]
    params = vectorizer.get_params()
    expected = {
        "analyzer": "char",
        "ngram_range": (3, 5),
        "sublinear_tf": True,
        "norm": "l2",
        "lowercase": True,
        "strip_accents": None,
        "binary": False,
    }
    if any(params[key] != value for key, value in expected.items()) or list(
        classifier.classes_
    ) != ["ham", "spam"]:
        raise ValueError("Unsupported portable preprocessing or labels")
    vocabulary = vectorizer.get_feature_names_out().tolist()
    export = {
        "format_version": 1,
        "model_id": registry.active,
        "model_version": registry.metadata[registry.active]["version"],
        "source_artifact_sha256": registry.metadata[registry.active]["artifact_sha256"],
        "dataset_sha256": registry.metadata[registry.active]["dataset_sha256"],
        "split_sha256": registry.metadata[registry.active]["split_sha256"],
        "labels": ["ham", "spam"],
        "score_kind": "decision_margin",
        "threshold": 0,
        "preprocessing": "sklearn char 3-5, Unicode lowercase, collapse consecutive Python whitespace, sublinear TF, IDF, L2",
        "vocabulary": vocabulary,
        "idf": vectorizer.idf_.tolist(),
        "weights": classifier.coef_[0].tolist(),
        "intercept": float(classifier.intercept_[0]),
    }
    write_json(args.output, export)

    fixtures = [
        "Hello friend, see you tomorrow.",
        "Congratulations claim your free prize now!",
        "",
        " ",
        "A",
        "a\t\tB\n\nC",
        "Café cafe\u0301 Straße İ ΣΟΣ",
        "emoji 😀 🚀 hello",
        "fullwidth ＦＲＥＥ zero\u200bwidth",
        "No links fetched: https://example.invalid/verify",
        "Line\r\n one\u001c\u001d two",
        "Xin chào bạn, mô hình chỉ hỗ trợ tiếng Anh.",
        "123456 " * 100,
        "🙂" * 300,
    ]
    cases = [
        {
            "text": text,
            "margin": float(model.decision_function([text])[0]),
            "label": str(model.predict([text])[0]),
        }
        for text in fixtures
    ]
    write_json(
        Path("android/app/src/test/resources/parity.json"),
        {
            "tolerance": 1e-9,
            "model_version": export["model_version"],
            "fixtures": cases,
        },
    )
    write_json(
        Path("android/app/src/androidTest/assets/parity.json"),
        {
            "tolerance": 1e-9,
            "model_version": export["model_version"],
            "fixtures": cases,
        },
    )
    for name in ("user-diagnostic-parity.json", "ten-sms-parity.json"):
        path = Path("android/app/src/androidTest/assets") / name
        diagnostics = json.loads(path.read_text(encoding="utf-8"))
        for case in diagnostics["cases"]:
            case["actual_margin"] = float(model.decision_function([case["text"]])[0])
            case["actual_label"] = str(model.predict([case["text"]])[0])
        diagnostics["model_version"] = export["model_version"]
        write_json(path, diagnostics)
    args.output.parent.parent.joinpath("model.sha256").write_text(
        sha256(args.output) + "\n"
    )
    print(
        json.dumps(
            {
                "features": len(vocabulary),
                "bytes": args.output.stat().st_size,
                "sha256": sha256(args.output),
                "synthetic_parity_cases": len(cases),
            }
        )
    )


if __name__ == "__main__":
    main()
