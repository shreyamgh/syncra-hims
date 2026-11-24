"""FastAPI application factory for the Syncra HIMS AI service."""

import asyncio
import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI
from redis.asyncio import Redis

from app import __version__
from app.api import health
from app.cdss import router as cdss
from app.clinical import router as clinical
from app.core.config import get_settings
from app.core.logging import configure_logging
from app.dictation import router as dictation
from app.stt.engine import WhisperEngine, build_engine

logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    settings = get_settings()
    redis = Redis(
        host=settings.redis_host,
        port=settings.redis_port,
        db=settings.redis_db,
        password=settings.redis_password.get_secret_value() if settings.redis_password else None,
        socket_connect_timeout=2,
        socket_timeout=2,
        health_check_interval=30,
    )
    app.state.redis = redis
    # Load and validate the CDSS knowledge base now: a broken rule file must stop startup.
    cdss.engine()
    engine = build_engine(settings)
    app.state.speech_engine = engine
    loader = asyncio.create_task(engine.start_loading()) if isinstance(engine, WhisperEngine) else None
    logger.info("ai-service started (version=%s, environment=%s)", __version__, settings.environment)
    try:
        yield
    finally:
        if loader and not loader.done():
            loader.cancel()
        await redis.aclose()
        logger.info("ai-service stopped")


def create_app() -> FastAPI:
    settings = get_settings()
    configure_logging(settings.log_level)

    # Interactive docs only outside production; served under the gateway prefix.
    docs_enabled = not settings.is_production
    app = FastAPI(
        title="Syncra HIMS AI Service",
        version=__version__,
        lifespan=lifespan,
        docs_url="/api/v1/ai/docs" if docs_enabled else None,
        redoc_url=None,
        openapi_url="/api/v1/ai/openapi.json" if docs_enabled else None,
    )
    app.include_router(health.router)
    app.include_router(dictation.router)
    app.include_router(clinical.router)
    app.include_router(cdss.router)
    return app


app = create_app()
