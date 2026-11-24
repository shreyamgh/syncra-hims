"""Service configuration, loaded from ``SYNCRA_AI_*`` environment variables."""

from functools import lru_cache
from typing import Literal

from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Typed, validated runtime settings. Invalid values abort startup."""

    model_config = SettingsConfigDict(env_prefix="SYNCRA_AI_", extra="ignore")

    service_name: str = "syncra-ai-service"
    environment: Literal["development", "staging", "production"] = "production"
    log_level: Literal["DEBUG", "INFO", "WARNING", "ERROR"] = "INFO"

    # Redis — separate fields (not a URL) so secrets need no URL-escaping.
    redis_host: str = "redis"
    redis_port: int = Field(default=6379, ge=1, le=65535)
    redis_password: SecretStr | None = None
    redis_db: int = Field(default=1, ge=0, le=15)

    # Location of downloaded speech/CDSS models (a persistent volume in compose).
    model_dir: str = "/models"

    # Dictation session guard-rails. 16 kHz mono PCM16 = 32 000 bytes/second.
    dictation_handshake_timeout_s: float = Field(default=10.0, gt=0, le=60)
    dictation_max_session_s: int = Field(default=900, ge=10, le=3600)
    dictation_max_frame_bytes: int = Field(default=64 * 1024, ge=1024)
    dictation_max_audio_bytes: int = Field(default=32 * 1024 * 1024, ge=64 * 1024)

    # Speech-to-text engine. "whisper" = faster-whisper (CTranslate2) running on CPU,
    # fully on-premises: audio never leaves the hospital network. "none" disables STT.
    stt_engine: Literal["whisper", "none"] = "whisper"
    # Model name (downloaded once into model_dir) or a local path for air-gapped sites.
    whisper_model: str = "small"
    whisper_compute_type: Literal["int8", "int8_float32", "float32"] = "int8"
    whisper_cpu_threads: int = Field(default=4, ge=1, le=64)
    whisper_beam_size: int = Field(default=1, ge=1, le=10)
    # Concurrent transcriptions (one per active dictation segment).
    stt_max_concurrency: int = Field(default=2, ge=1, le=16)

    # Pause-based segmentation of the live stream: a segment is transcribed once
    # the speaker pauses, or when it reaches the maximum length.
    segment_pause_ms: int = Field(default=700, ge=200, le=5000)
    segment_min_s: float = Field(default=0.6, gt=0, le=10)
    segment_max_s: float = Field(default=20.0, ge=2, le=30)
    # RMS level (0-1 full scale) below which audio counts as silence (~ -40 dBFS).
    silence_rms_threshold: float = Field(default=0.01, gt=0, lt=1)

    # CDSS knowledge base: the bundled starter file unless a hospital-reviewed one is mounted.
    cdss_knowledge_path: str | None = None

    @property
    def is_production(self) -> bool:
        return self.environment == "production"


@lru_cache
def get_settings() -> Settings:
    return Settings()
