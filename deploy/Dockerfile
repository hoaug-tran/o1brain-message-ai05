FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 O1BRAIN_COMMUNITY_DATA=/data
WORKDIR /app
COPY requirements.txt ./
RUN pip install --no-cache-dir --require-hashes -r requirements.txt
COPY pyproject.toml ./
COPY README.md ./
COPY licenses ./licenses
COPY src ./src
COPY ml ./ml
COPY artifacts ./artifacts
COPY data/manifests ./data/manifests
COPY results ./results
COPY migrations ./migrations
COPY alembic.ini ./
COPY scripts/community_admin.py ./scripts/community_admin.py
ENV PYTHONPATH=/app/src:/app
USER 1000:1000
EXPOSE 8080
CMD ["sh", "-c", "python -m alembic upgrade head && exec python -m uvicorn ai05.interfaces.http:app --host 0.0.0.0 --port 8080 --workers 1 --no-access-log --no-proxy-headers --limit-concurrency 32"]
