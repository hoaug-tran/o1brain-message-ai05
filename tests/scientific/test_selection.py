from sklearn.feature_extraction.text import TfidfVectorizer

from ml.benchmark import candidates, select_on_train


def test_tuning_matches_full_pipeline_oof_and_fits_only_train_folds(monkeypatch):
    from sklearn.base import clone
    from sklearn.model_selection import StratifiedKFold, cross_val_predict

    from ml.benchmark import tune_on_train
    from ml.metrics import evaluate

    texts = [f"friendly hello meeting library token{i}" for i in range(12)] + [
        f"claim free prize cash token{i}" for i in range(6)
    ]
    labels = ["ham"] * 12 + ["spam"] * 6
    models = candidates()
    grids = {
        name: [{"C": 0.1, "class_weight": None}, {"C": 1.0, "class_weight": "balanced"}]
        for name in models
    }
    original = TfidfVectorizer.fit_transform
    fits = []

    def checked(self, documents, *args, **kwargs):
        documents = list(documents)
        assert set(documents) < set(texts)
        assert len(documents) == 9
        fits.append(len(documents))
        return original(self, documents, *args, **kwargs)

    monkeypatch.setattr(TfidfVectorizer, "fit_transform", checked)
    selected, trials = tune_on_train(texts, labels, models, grids, folds=2)
    assert fits == [9, 9, 9, 9]
    for name, model in models.items():
        for row in trials[name]:
            pipeline = clone(model)
            pipeline[-1].set_params(**row["parameters"])
            predicted = cross_val_predict(
                pipeline,
                texts,
                labels,
                cv=StratifiedKFold(n_splits=2, shuffle=True, random_state=2026),
            ).tolist()
            assert row["oof_predictions"] == predicted
            assert row["report"] == evaluate(labels, predicted)
        expected = max(
            trials[name],
            key=lambda row: (
                row["report"]["metrics"]["spam_f1"],
                -row["report"]["metrics"]["ham_fpr"],
            ),
        )
        assert selected[name] == expected


def test_selection_fits_preprocessing_only_inside_train_folds(monkeypatch):
    texts = [f"Hello friend meeting tomorrow library {i}" for i in range(10)] + [
        f"FREE prize WIN cash claim today {i}" for i in range(10)
    ]
    labels = ["ham"] * 10 + ["spam"] * 10
    observed = []
    original = TfidfVectorizer.fit_transform

    def checked(self, raw_documents, *args, **kwargs):
        documents = list(raw_documents)
        assert set(documents) <= set(texts)
        assert len(documents) < len(texts)
        observed.append(len(documents))
        return original(self, documents, *args, **kwargs)

    monkeypatch.setattr(TfidfVectorizer, "fit_transform", checked)
    selected, reports = select_on_train(texts, labels, candidates(), folds=2)
    assert selected in reports and len(reports) == 2
    assert observed == [10, 10, 10, 10]
    assert all(
        report["support"] == {"ham": 10, "spam": 10} for report in reports.values()
    )
