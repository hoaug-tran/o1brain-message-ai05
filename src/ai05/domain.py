from dataclasses import dataclass


class InvalidMessage(ValueError):
    pass


class UnsupportedLanguage(ValueError):
    pass


class ModelUnavailable(RuntimeError):
    pass


def validate_text(text: str, language: str) -> None:
    if any(0xD800 <= ord(char) <= 0xDFFF for char in text):
        raise InvalidMessage("Invalid Unicode scalar")
    if not text.strip() or len(text) > 2000 or len(text.encode("utf-8")) > 6000:
        raise InvalidMessage(
            "Text must contain 1–2000 characters and at most 6000 UTF-8 bytes"
        )
    if any(ord(char) < 32 and char not in "\t\n\r" for char in text):
        raise InvalidMessage("Unsupported control character")

    if language != "en" or any(
        "\u1ea0" <= char <= "\u1ef9" or char in "đĐ" for char in text
    ):
        raise UnsupportedLanguage(
            "Only English evaluated; mixed/other languages not supported"
        )


@dataclass(frozen=True)
class Classification:
    label: str
    score: float
    score_kind: str


def warning_policy(label: str) -> dict[str, object]:
    if label not in {"ham", "spam"}:
        raise InvalidMessage("Unknown classifier label")
    return {
        "status": "suspected_spam" if label == "spam" else "no_spam_signal",
        "reason_codes": ["model_prediction"],
        "policy_version": "warning-only-v1",
        "destructive_action_allowed": False,
    }
