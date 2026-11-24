import os

os.environ.setdefault("SYNCRA_AI_ENVIRONMENT", "development")
os.environ.setdefault("SYNCRA_AI_REDIS_HOST", "127.0.0.1")
os.environ.setdefault("SYNCRA_AI_STT_ENGINE", "none")  # tests never download a model

from collections.abc import Iterator  # noqa: E402

import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402
from redis.exceptions import ConnectionError as RedisConnectionError  # noqa: E402

from app.core.config import get_settings  # noqa: E402
from app.main import create_app  # noqa: E402
from app.stt.engine import EngineStatus  # noqa: E402


class FakeRedis:
    def __init__(self, healthy: bool = True) -> None:
        self.healthy = healthy

    async def ping(self) -> bool:
        if not self.healthy:
            raise RedisConnectionError("connection refused")
        return True

    async def aclose(self) -> None:
        return None


class FakeEngine:
    """Stands in for Whisper: records each segment and returns scripted text."""

    name = "fake-whisper"

    def __init__(self, status: EngineStatus = EngineStatus.READY, replies: list[str] | None = None) -> None:
        self._status = status
        self.replies = list(replies or [])
        self.calls: list[tuple[int, str, str]] = []

    @property
    def status(self) -> EngineStatus:
        return self._status

    async def transcribe(self, audio, language: str, prompt: str) -> str:
        self.calls.append((int(audio.size), language, prompt))
        return self.replies.pop(0) if self.replies else ""


@pytest.fixture
def fake_redis() -> FakeRedis:
    return FakeRedis()


@pytest.fixture
def client(fake_redis: FakeRedis) -> Iterator[TestClient]:
    get_settings.cache_clear()
    app = create_app()
    with TestClient(app) as test_client:
        app.state.redis = fake_redis
        yield test_client
