"""REST access to clinical extraction (e.g. after the doctor edits a transcript)."""

from fastapi import APIRouter
from pydantic import BaseModel, Field

from app.clinical.extraction import ClinicalExtraction, extract

router = APIRouter(prefix="/api/v1/ai", tags=["clinical"])


class ExtractRequest(BaseModel):
    text: str = Field(max_length=20_000)


@router.post("/extract", response_model=ClinicalExtraction)
async def extract_findings(request: ExtractRequest) -> ClinicalExtraction:
    """Re-runs extraction over free text. Stateless; the text is not stored or logged."""
    return extract(request.text)
