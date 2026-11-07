import time
from typing import Protocol
from uuid import uuid4

from ai05.domain import Classification, validate_text, warning_policy


class Classifier(Protocol):
    active: str

    def classify(self, model_id: str, text: str) -> Classification: ...

    def public_info(self, model_id: str) -> dict[str, str]: ...


def predict(
    classifier: Classifier, text: str, language: str, model_id: str | None = None
) -> dict:
    validate_text(text, language)
    selected = model_id or classifier.active
    started = time.perf_counter()
    classification = classifier.classify(selected, text)
    return {
        "prediction_id": str(uuid4()),
        "task": "sms_spam_binary",
        "language": "en",
        "supported_scope": "competition_english",
        "model": classifier.public_info(selected),
        "classification": {
            "label": classification.label,
            "score": classification.score,
            "score_kind": classification.score_kind,
        },
        "policy": warning_policy(classification.label),
        "latency_ms": (time.perf_counter() - started) * 1000,
        "warning": "English research prototype. Uncalibrated score; not proof of fraud. Other/mixed languages not evaluated. Text not retained.",
    }
