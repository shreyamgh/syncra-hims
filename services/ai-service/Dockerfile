# syntax=docker/dockerfile:1.7
# -----------------------------------------------------------------------------
# Syncra HIMS — AI service image (FastAPI + uvicorn)
#   deps stage builds an isolated virtualenv; runtime copies only the venv and
#   application code and runs as an unprivileged user on a read-only rootfs.
# -----------------------------------------------------------------------------
FROM python:3.12-slim AS base
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

FROM base AS deps
RUN python -m venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"
COPY requirements.txt .
RUN pip install -r requirements.txt

FROM base AS runtime
LABEL org.opencontainers.image.title="syncra-ai-service" \
      org.opencontainers.image.vendor="Syncra Technologies"

RUN groupadd --system syncra \
 && useradd --system --gid syncra --home-dir /app --shell /usr/sbin/nologin syncra \
 && mkdir -p /models && chown syncra:syncra /models

COPY --from=deps /opt/venv /opt/venv
# Model downloads/caches live on the persistent /models volume (the root fs is read-only).
ENV PATH="/opt/venv/bin:$PATH" \
    HF_HOME=/models/huggingface \
    HF_HUB_DISABLE_TELEMETRY=1

WORKDIR /app
COPY --chown=syncra:syncra app ./app

USER syncra
EXPOSE 8000

# One worker per container: dictation sessions hold in-memory model state.
# Scale horizontally with replicas behind the gateway instead.
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", \
     "--no-server-header", "--timeout-graceful-shutdown", "25", "--ws-max-size", "262144"]
