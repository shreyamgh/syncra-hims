"""The CDSS knowledge base: a versioned JSON file, validated when loaded.

Rules refer to a generic by id (``"warfarin"``) or to a class (``"class:nsaid"``). Loading
fails fast if a rule names something the file does not define, so a typo cannot silently
disable a safety rule.
"""

import json
from functools import lru_cache
from pathlib import Path
from typing import Literal

from pydantic import BaseModel, Field, model_validator

Severity = Literal["CONTRAINDICATED", "MAJOR", "MODERATE", "MINOR"]
SEVERITY_RANK: dict[str, int] = {"CONTRAINDICATED": 0, "MAJOR": 1, "MODERATE": 2, "MINOR": 3}
BUNDLED = Path(__file__).with_name("knowledge.json")


class Generic(BaseModel):
    id: str
    name: str
    classes: list[str] = Field(default_factory=list)
    synonyms: list[str] = Field(default_factory=list)


class Interaction(BaseModel):
    code: str
    a: list[str]
    b: list[str]
    severity: Severity
    effect: str
    management: str
    source: str


class DuplicateClass(BaseModel):
    class_: str = Field(alias="class")
    label: str
    severity: Severity


class CrossReaction(BaseModel):
    ref: str
    severity: Severity
    note: str


class AllergyGroup(BaseModel):
    code: str
    terms: list[str]
    contraindicated: list[str] = Field(default_factory=list)
    cross: list[CrossReaction] = Field(default_factory=list)


class DoseLimit(BaseModel):
    generic: str
    severity: Severity
    source: str
    adult_max_mg_day: float | None = None
    paediatric_max_mg_kg_day: float | None = None
    geriatric_max_mg_day: float | None = None
    geriatric_note: str | None = None


class AgeCaution(BaseModel):
    ref: str
    severity: Severity
    note: str
    source: str


class RenalRule(BaseModel):
    ref: str
    crcl_below: float
    severity: Severity
    note: str
    source: str


class KnowledgeBase(BaseModel):
    version: str
    reviewed: bool
    review_note: str
    references: dict[str, str]
    generics: list[Generic]
    brands: dict[str, dict[str, float | None]]
    interactions: list[Interaction]
    duplicate_classes: list[DuplicateClass]
    allergy_groups: list[AllergyGroup]
    doses: list[DoseLimit]
    geriatric: list[AgeCaution]
    renal: list[RenalRule]

    @model_validator(mode="after")
    def _references_resolve(self) -> "KnowledgeBase":
        ids = {g.id for g in self.generics}
        classes = {c for g in self.generics for c in g.classes}

        def check(ref: str, where: str) -> None:
            known = ref[6:] in classes if ref.startswith("class:") else ref in ids
            if not known:
                raise ValueError(f"{where} refers to unknown {ref!r}")

        for rule in self.interactions:
            for ref in rule.a + rule.b:
                check(ref, rule.code)
        for brand, parts in self.brands.items():
            for generic in parts:
                check(generic, f"brand {brand}")
        for group in self.allergy_groups:
            for ref in group.contraindicated + [c.ref for c in group.cross]:
                check(ref, f"allergy group {group.code}")
        for dose in self.doses:
            check(dose.generic, "dose limit")
        for caution in self.geriatric:
            check(caution.ref, "geriatric caution")
        for renal in self.renal:
            check(renal.ref, "renal rule")
        for duplicate in self.duplicate_classes:
            check("class:" + duplicate.class_, "duplicate class")
        if len({r.code for r in self.interactions}) != len(self.interactions):
            raise ValueError("duplicate interaction codes")
        return self

    def generic(self, generic_id: str) -> Generic:
        return next(g for g in self.generics if g.id == generic_id)


def load(path: Path | None = None) -> KnowledgeBase:
    return KnowledgeBase.model_validate(json.loads((path or BUNDLED).read_text(encoding="utf-8")))


@lru_cache(maxsize=4)
def cached(path: str | None = None) -> KnowledgeBase:
    return load(Path(path) if path else None)
