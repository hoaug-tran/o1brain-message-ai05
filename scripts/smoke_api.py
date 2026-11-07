import argparse

import httpx


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Real local HTTP smoke; uses synthetic text only."
    )
    parser.add_argument("--url", default="http://127.0.0.1:8000")
    args = parser.parse_args()
    with httpx.Client(
        base_url=args.url, timeout=10, headers={"User-Agent": "O1BrainQA/0.4.0"}
    ) as client:
        for path in (
            "/",
            "/health/live",
            "/health/ready",
            "/api/v1/models",
            "/api/v1/demo/metrics",
            "/figures/class_distribution.png",
            "/figures/confusion_matrices.png",
            "/research/",
            "/research/style.css",
            "/research/figures/01_classes.png",
            "/research/figures/06_ham_wordcloud.png",
            "/research/figures/06_spam_wordcloud.png",
            "/research/figures/08_models.png",
            "/research/figures/09_confusion.svg",
        ):
            response = client.get(path)
            response.raise_for_status()
            print(path, response.status_code)
        reports = client.get("/api/v1/demo/metrics").json()["research_reports"]
        assert len(reports) == 6
        for report in reports.values():
            c = report["confusion"]
            assert report["support"] == {"ham": 904, "spam": 128}
            assert report["metrics"]["ham_fpr"] == c["fp"] / (c["fp"] + c["tn"])
        for path in ("/research/figures/phone.key", "/research/split.json"):
            assert client.get(path).status_code == 404
        for text in (
            "Hello friend, see you tomorrow.",
            "Congratulations claim a free prize now",
        ):
            response = client.post("/api/v1/demo/compare", json={"text": text})
            response.raise_for_status()
            predictions = response.json()["comparisons"]
            assert len({item["model"]["id"] for item in predictions}) == 2
            assert all(
                item["classification"]["label"] in {"ham", "spam"}
                for item in predictions
            )
        assert (
            client.post("/api/v1/demo/predict", json={"text": " "}).status_code == 400
        )
        print("Real HTTP comparison and negative-input smoke passed")


if __name__ == "__main__":
    main()
