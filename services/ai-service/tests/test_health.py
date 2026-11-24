from fastapi.testclient import TestClient

from tests.conftest import FakeRedis


def test_liveness(client: TestClient) -> None:
    response = client.get("/health/live")
    assert response.status_code == 200
    assert response.json() == {"status": "UP"}


def test_readiness_up_when_redis_reachable(client: TestClient) -> None:
    response = client.get("/health/ready")
    assert response.status_code == 200
    assert response.json() == {"status": "UP", "checks": {"redis": "UP", "speech_engine": "UP"}}


def test_readiness_down_when_redis_unreachable(client: TestClient) -> None:
    client.app.state.redis = FakeRedis(healthy=False)
    response = client.get("/health/ready")
    assert response.status_code == 503
    assert response.json()["checks"]["redis"] == "DOWN"


def test_readiness_down_while_speech_model_loads(client: TestClient) -> None:
    from app.stt.engine import EngineStatus
    from tests.conftest import FakeEngine

    client.app.state.speech_engine = FakeEngine(EngineStatus.LOADING)
    response = client.get("/health/ready")
    assert response.status_code == 503
    assert response.json()["checks"]["speech_engine"] == "LOADING"
