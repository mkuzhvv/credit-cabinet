FROM python:3.12.14-slim-bookworm

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1

WORKDIR /app

COPY ml/scoring/requirements.txt ./
RUN --mount=type=cache,target=/root/.cache/pip pip install -r requirements.txt

COPY ml/scoring/server.py ml/scoring/engine.py ml/scoring/validation.py ./
COPY ml/scoring/policy.json ml/scoring/catalog.json ml/scoring/schema.json ./

USER 10001:10001

EXPOSE 8000

CMD ["python", "server.py", "--host", "0.0.0.0", "--port", "8000"]
