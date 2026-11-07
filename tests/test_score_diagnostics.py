import json
from pathlib import Path

import pytest

from ai05.domain import ModelUnavailable
from ai05.infrastructure.models import ModelRegistry

ROOT = Path(__file__).resolve().parents[1]
CASES = json.loads((ROOT / "tests/fixtures/user_sms_15.json").read_text())["cases"]


@pytest.fixture(scope="module")
def registry() -> ModelRegistry:
    registry = ModelRegistry(ROOT / "artifacts/registry.json")
    assert registry.ready
    return registry


@pytest.mark.parametrize("case", CASES, ids=[row["id"] for row in CASES])
def test_user_case_score_and_label_agree(registry: ModelRegistry, case: dict) -> None:
    prediction = registry.classify(registry.active, case["text"])
    threshold = registry.metadata[registry.active]["threshold"]
    assert prediction.label == ("spam" if prediction.score > threshold else "ham")
    expected = registry.models[registry.active].predict([case["text"]])[0]
    assert prediction.label == expected


def test_mismatched_margin_and_label_fail_closed(registry: ModelRegistry) -> None:
    class InconsistentModel:
        def predict(self, texts: list[str]) -> list[str]:
            return ["spam"]

        def decision_function(self, texts: list[str]) -> list[float]:
            return [-1.1352]

    original = registry.models[registry.active]
    try:
        registry.models[registry.active] = InconsistentModel()
        with pytest.raises(ModelUnavailable):
            registry.classify(registry.active, "Synthetic contract only")
    finally:
        registry.models[registry.active] = original
