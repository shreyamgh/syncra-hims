"""Liveness/readiness probes (internal; not routed through the gateway)."""

import asyncio
import logging

from fastapi import APIRouter, Request, Response, status
from redis.exceptions import RedisError

from app.stt.engine import EngineStatus

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/health", tags=["health"])

_DEPENDENCY_TIMEOUT_S = 2.0


@router.get("/live")
async def live() -> dict[str, str]:
    """Process is up and the event loop is responsive."""
    return {"status": "UP"}


@router.get("/ready")
async def ready(request: Request, response: Response) -> dict[str, object]:
    """Dependencies required to serve traffic are reachable."""
    checks: dict[str, str] = {}
    try:
        await asyncio.wait_for(request.app.state.redis.ping(), timeout=_DEPENDENCY_TIMEOUT_S)
        checks["redis"] = "UP"
    except (RedisError, OSError, TimeoutError) as exc:
        logger.warning("readiness: redis unavailable (%s)", type(exc).__name__)
        checks["redis"] = "DOWN"

    engine = request.app.state.speech_engine
    checks["speech_engine"] = "UP" if engine.status in (EngineStatus.READY, EngineStatus.DISABLED) else engine.status

    overall = "UP" if all(v == "UP" for v in checks.values()) else "DOWN"
    if overall != "UP":
        response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
    return {"status": overall, "checks": checks}
