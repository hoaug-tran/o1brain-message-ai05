from sklearn.base import clone
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.model_selection import StratifiedKFold, cross_val_predict
from sklearn.pipeline import make_pipeline
from sklearn.svm import LinearSVC

from ml.metrics import evaluate


def parameter_grid() -> dict[str, list[dict]]:
    weights = [None, "balanced"]
    return {
        **{
            name: [{"alpha": alpha} for alpha in (0.1, 0.5, 1.0)]
            for name in ("english_tuned_nb", "english_complement_nb")
        },
        **{
            name: [
                {"C": c, "class_weight": weight}
                for c in (0.1, 1.0, 4.0, 10.0)
                for weight in weights
            ]
            for name in ("english_logreg", "english_word_svm", "english_char_svm")
        },
    }


def tune_on_train(
    texts: list[str],
    labels: list[str],
    models: dict,
    grids: dict[str, list[dict]],
    folds: int = 5,
) -> tuple[dict, dict]:
    if set(grids) != set(models) or any(not rows for rows in grids.values()):
        raise ValueError("Every model requires a nonempty parameter grid")
    splits = list(
        StratifiedKFold(n_splits=folds, shuffle=True, random_state=2026).split(
            texts, labels
        )
    )
    predictions = {
        name: [[""] * len(texts) for _ in rows] for name, rows in grids.items()
    }
    groups: dict[str, list[str]] = {}
    for name, model in models.items():
        groups.setdefault(repr(model[0].get_params()), []).append(name)
    for group in groups.values():
        for fold, (training, held_out) in enumerate(splits):
            vectorizer = clone(models[group[0]][0])
            features = vectorizer.fit_transform([texts[i] for i in training])
            held_features = vectorizer.transform([texts[i] for i in held_out])
            for name in group:
                for index, parameters in enumerate(grids[name]):
                    classifier = clone(models[name][-1]).set_params(**parameters)
                    classifier.fit(features, [labels[i] for i in training])
                    predicted = classifier.predict(held_features)
                    for identity, label in zip(held_out, predicted, strict=True):
                        predictions[name][index][int(identity)] = str(label)
            print(f"CV {group}: fold {fold + 1}/{folds}", flush=True)
    selected: dict[str, dict] = {}
    trials: dict[str, list[dict]] = {}
    for name, rows in grids.items():
        trials[name] = [
            {
                "parameters": parameters,
                "report": evaluate(labels, predicted),
                "oof_predictions": predicted,
            }
            for parameters, predicted in zip(rows, predictions[name], strict=True)
        ]
        best = max(
            range(len(rows)),
            key=lambda index: (
                trials[name][index]["report"]["metrics"]["spam_f1"] or 0,
                -(trials[name][index]["report"]["metrics"]["ham_fpr"] or 0),
            ),
        )
        selected[name] = trials[name][best]
    return selected, trials


def candidates() -> dict:
    return {
        "english_logreg": make_pipeline(
            TfidfVectorizer(ngram_range=(1, 2), sublinear_tf=True),
            LogisticRegression(C=4, max_iter=2000, random_state=2026),
        ),
        "english_char_svm": make_pipeline(
            TfidfVectorizer(analyzer="char", ngram_range=(3, 5), sublinear_tf=True),
            LinearSVC(C=1, random_state=2026),
        ),
    }


def select_on_train(
    texts: list[str], labels: list[str], models: dict, folds: int = 5
) -> tuple[str, dict]:
    splitter = StratifiedKFold(n_splits=folds, shuffle=True, random_state=2026)
    reports = {
        name: evaluate(
            labels,
            cross_val_predict(model, texts, labels, cv=splitter, n_jobs=1).tolist(),
        )
        for name, model in models.items()
    }
    selected = max(
        sorted(reports),
        key=lambda name: (
            reports[name]["metrics"]["spam_f1"] or 0,
            -(reports[name]["metrics"]["ham_fpr"] or 0),
        ),
    )
    return selected, reports
