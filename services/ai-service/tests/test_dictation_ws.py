import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

PATH = "/ws/ai/dictation"
START = {"type": "start", "encounter_id": "ENC-20261120-0001", "language": "en-IN"}
ONE_TENTH_SECOND = b"\x00\x00" * 1600  # 1 600 PCM16 samples at 16 kHz


def test_full_session_returns_final_message(client: TestClient) -> None:
    with client.websocket_connect(PATH) as ws:
        ws.send_json(START)
        ready = ws.receive_json()
        assert ready["type"] == "ready"
        assert ready["engine"] == "none"

        for _ in range(5):
            ws.send_bytes(ONE_TENTH_SECOND)
        ws.send_json({"type": "stop"})

        final = ws.receive_json()
        assert final["type"] == "final"
        assert final["session_id"] == ready["session_id"]
        assert (final["text"], final["audio_seconds"], final["engine"], final["language"]) == ("", 0.5, "none", "en-IN")
        assert final["extraction"]["prescriptions"] == []
        with pytest.raises(WebSocketDisconnect) as closed:
            ws.receive_text()
        assert closed.value.code == 1000


def _expect_error(ws, code: str, close_code: int) -> None:
    error = ws.receive_json()
    assert error["type"] == "error"
    assert error["code"] == code
    with pytest.raises(WebSocketDisconnect) as closed:
        ws.receive_text()
    assert closed.value.code == close_code


def test_audio_before_start_is_rejected(client: TestClient) -> None:
    with client.websocket_connect(PATH) as ws:
        ws.send_bytes(ONE_TENTH_SECOND)
        _expect_error(ws, "start_required", 1008)


@pytest.mark.parametrize(
    "start",
    [
        {**START, "encounter_id": "../../etc/passwd"},
        {**START, "sample_rate": 44100},
        {**START, "unexpected": True},
        {"type": "begin"},
    ],
)
def test_invalid_start_is_rejected(client: TestClient, start: dict) -> None:
    with client.websocket_connect(PATH) as ws:
        ws.send_json(start)
        _expect_error(ws, "invalid_message", 1008)


def test_odd_length_frame_is_rejected(client: TestClient) -> None:
    with client.websocket_connect(PATH) as ws:
        ws.send_json(START)
        ws.receive_json()
        ws.send_bytes(b"\x00\x00\x00")
        _expect_error(ws, "invalid_frame", 1008)


def test_oversized_frame_is_rejected(client: TestClient) -> None:
    with client.websocket_connect(PATH) as ws:
        ws.send_json(START)
        ws.receive_json()
        ws.send_bytes(b"\x00" * (64 * 1024 + 2))
        _expect_error(ws, "frame_too_large", 1009)


def test_second_start_is_rejected(client: TestClient) -> None:
    with client.websocket_connect(PATH) as ws:
        ws.send_json(START)
        ws.receive_json()
        ws.send_json(START)
        _expect_error(ws, "already_started", 1008)
