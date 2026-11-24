"""CDSS and coding APIs (stateless; nothing is stored or logged)."""

from fastapi import APIRouter, Query
from pydantic import BaseModel

from app.cdss.engine import CheckRequest, CheckResponse, Engine
from app.cdss.knowledge import cached
from app.clinical import icd10
from app.core.config import get_settings

router = APIRouter(prefix="/api/v1/ai", tags=["cdss"])


def engine() -> Engine:
    return _engine(get_settings().cdss_knowledge_path)


_ENGINES: dict[str | None, Engine] = {}


def _engine(path: str | None) -> Engine:
    if path not in _ENGINES:
        _ENGINES[path] = Engine(cached(path))
    return _ENGINES[path]


class KnowledgeInfo(BaseModel):
    version: str
    reviewed: bool
    review_note: str
    references: dict[str, str]
    interactions: int
    generics: int


class CodeSuggestion(BaseModel):
    code: str
    title: str


@router.post("/cdss/check", response_model=CheckResponse)
async def check(request: CheckRequest) -> CheckResponse:
    """Interactions, duplicates, allergies, dose range and renal checks for a draft prescription."""
    return engine().check(request)


@router.get("/cdss/knowledge", response_model=KnowledgeInfo)
async def knowledge() -> KnowledgeInfo:
    kb = engine().kb
    return KnowledgeInfo(version=kb.version, reviewed=kb.reviewed, review_note=kb.review_note,
                         references=kb.references, interactions=len(kb.interactions), generics=len(kb.generics))


@router.get("/icd10", response_model=list[CodeSuggestion])
async def icd10_search(q: str = Query(min_length=2, max_length=100)) -> list[CodeSuggestion]:
    """ICD-10 pick-list search over the hospital's common-diagnosis table."""
    return [CodeSuggestion(code=code, title=title) for code, title in icd10.search(q)]
