from datetime import datetime
from typing import Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field


class PredictionRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    text: str = Field(min_length=1, max_length=2000)
    language_hint: Literal["en", "vi", "mixed", "unknown"] = "en"
    model_id: Literal["english_baseline", "english_char_svm", "english_improved"] = (
        "english_improved"
    )


class ModelInfo(BaseModel):
    id: str
    version: str
    artifact_sha256_prefix: str
    language: Literal["en"]
    validation_status: str


class ClassificationDTO(BaseModel):
    label: Literal["ham", "spam"]
    score: float
    score_kind: Literal["decision_margin", "uncalibrated_score"]


class PolicyDTO(BaseModel):
    status: Literal["suspected_spam", "no_spam_signal"]
    reason_codes: list[str]
    policy_version: str
    destructive_action_allowed: Literal[False]


class PredictionResponse(BaseModel):
    prediction_id: UUID
    task: Literal["sms_spam_binary"]
    language: Literal["en"]
    supported_scope: Literal["expanded_english_experiment"]
    model: ModelInfo
    classification: ClassificationDTO
    policy: PolicyDTO
    latency_ms: float
    warning: str


class ComparisonResponse(BaseModel):
    input_id: UUID
    dataset_scope: Literal["expanded_english_experiment"] = (
        "expanded_english_experiment"
    )
    comparisons: list[PredictionResponse]
    note: str


class EventRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    event_id: UUID
    source_kind: Literal["synthetic_demo", "android_receiver", "trusted_gateway"]
    message_origin: Literal["synthetic", "real_test"]
    received_at_utc: datetime
    text: str = Field(min_length=1, max_length=2000, strict=True)
    language_hint: Literal["en", "vi", "mixed", "unknown"] = "en"
