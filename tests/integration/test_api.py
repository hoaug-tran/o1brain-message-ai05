from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from ai05.interfaces.http import create_app
from ai05.interfaces.schemas import ComparisonResponse, PredictionResponse


@pytest.fixture
def client():
    with TestClient(create_app()) as session:
        yield session


def test_real_prediction_compare_and_demo(client: TestClient) -> None:
    assert client.get("/health/ready").status_code == 200
    response = client.post(
        "/api/v1/demo/predict", json={"text": "Hello friend, see you tomorrow."}
    )
    assert response.status_code == 200
    prediction = PredictionResponse.model_validate(response.json())
    assert prediction.classification.label in {"ham", "spam"}
    assert prediction.classification.score_kind == "decision_margin"
    assert prediction.model.version
    assert prediction.policy.destructive_action_allowed is False
    compared = client.post(
        "/api/v1/demo/compare", json={"text": "Claim your free prize now"}
    )
    assert compared.status_code == 200
    results = ComparisonResponse.model_validate(compared.json()).comparisons
    assert len({item.model.id for item in results}) == 2
    assert client.get("/").status_code == 200
    assert "Kiểm tra tin nhắn" in client.get("/").text
    assert client.get("/assets/app.js").status_code == 200
    assert client.get("/figures/confusion_matrices.png").status_code == 200
    assert client.get("/api/v1/demo/metrics").json()["reports"]["english_baseline"][
        "confusion"
    ] == {"tn": 904, "fp": 0, "fn": 31, "tp": 97}


@pytest.mark.parametrize(
    "payload,status",
    [
        ({"text": " "}, 400),
        ({"text": "a" * 2001}, 422),
        ({"text": "x", "language_hint": "vi"}, 400),
        ({"text": "Thông báo học phí"}, 400),
        ({"text": "x", "model_id": "../../secret"}, 422),
        ({"text": "x", "extra": "secret"}, 422),
        ({"text": 123}, 422),
        ({"text": "abc\u0000"}, 400),
    ],
)
def test_bad_input_no_echo(client: TestClient, payload: dict, status: int) -> None:
    response = client.post("/api/v1/demo/predict", json=payload)
    assert response.status_code == status
    assert response.json()["error"]["request_id"]
    assert "secret" not in response.text


def test_payload_quota_missing_artifact(tmp_path: Path) -> None:
    with TestClient(create_app(quota=1)) as session:
        assert (
            session.post("/api/v1/demo/predict", json={"text": "hello"}).status_code
            == 200
        )
        assert (
            session.post("/api/v1/demo/predict", json={"text": "hello"}).status_code
            == 429
        )
        assert (
            session.post("/api/v1/demo/predict", content=b"x" * 8193).status_code == 413
        )
    with TestClient(create_app(registry_path=tmp_path / "absent")) as session:
        assert session.get("/health/live").status_code == 200
        assert session.get("/health/ready").status_code == 503
        response = session.post("/api/v1/demo/predict", json={"text": "hello"})
        assert response.status_code == 503
        assert "classification" not in response.json()


def test_openapi_contract() -> None:
    schema = create_app().openapi()
    assert (
        schema["components"]["schemas"]["PredictionRequest"]["additionalProperties"]
        is False
    )
    assert {
        "/api/v1/demo/predict",
        "/api/v1/demo/compare",
        "/api/v1/sms-events",
    } <= schema["paths"].keys()
    assert schema["components"]["schemas"]["ClassificationDTO"]["properties"]["label"][
        "enum"
    ] == ["ham", "spam"]


def test_research_report_is_public_but_only_allowlisted_assets(
    client: TestClient,
) -> None:
    response = client.get("/research/")
    assert response.status_code == 200
    assert "UCI SMS Spam Collection" in response.text
    assert "tập huấn luyện" in response.text
    assert "<style>" not in response.text
    assert client.get("/research/style.css").status_code == 200
    assert len(client.get("/api/v1/demo/metrics").json()["research_reports"]) == 6
    assert client.get("/research/figures/09_confusion.svg").status_code == 200
    for label in ("ham", "spam"):
        cloud = client.get(f"/research/figures/06_{label}_wordcloud.png")
        assert cloud.status_code == 200 and cloud.headers["content-type"] == "image/png"
    assert (
        client.get("/research/figures/01_classes.png").headers["content-type"]
        == "image/png"
    )
    assert client.get("/research/figures/plan.json").status_code == 404
    assert client.get("/research/figures/phone.key").status_code == 404
    assert client.get("/research/split.json").status_code == 404
