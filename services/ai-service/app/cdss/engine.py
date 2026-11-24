"""Deterministic CDSS checks over a draft prescription.

Every alert names the knowledge-base rule that raised it (``rule_code``) and the
knowledge-base version, so the prescriber's override can be recorded against exactly
what they were shown. Nothing is stored and nothing leaves the hospital network.

Checks
    * drug-drug interactions between lines;
    * duplicate therapy: the same generic twice, or two drugs of a duplicate-prone class;
    * allergies: the patient's recorded allergies against each line (direct and cross-reactive);
    * dose range: daily dose against adult, paediatric (mg/kg/day) and older-adult limits;
    * older-adult cautions (Beers criteria);
    * renal guard: creatinine clearance (Cockcroft-Gault) against renal rules.

Dose and renal checks need inputs that are not always available (a parsable strength and
frequency; age, sex, weight and serum creatinine); when they are missing the response says
which checks were not done instead of guessing.
"""

import re
from itertools import combinations
from typing import Literal

from pydantic import BaseModel, Field

from app.cdss.knowledge import SEVERITY_RANK, KnowledgeBase, Severity
from app.cdss.resolver import Ingredient, Resolver

AlertType = Literal["DRUG_DRUG", "DRUG_ALLERGY", "DUPLICATE_THERAPY", "DOSE_RANGE", "CONTRAINDICATION"]

PER_DAY = {"OD": 1, "BD": 2, "TDS": 3, "QID": 4, "HS": 1, "STAT": 1, "Q4H": 6, "Q6H": 4, "Q8H": 3,
           "WEEKLY": 1 / 7, "ALT_DAYS": 0.5}
ADULT_AGE = 18
OLDER_ADULT_AGE = 65


class DrugLine(BaseModel):
    drug_text: str = Field(min_length=1, max_length=200)
    dose: str | None = Field(default=None, max_length=40)
    frequency: str | None = Field(default=None, max_length=12)
    duration_days: int | None = Field(default=None, ge=1, le=365)


class Allergy(BaseModel):
    allergen: str = Field(min_length=1, max_length=200)
    drug_class: str | None = Field(default=None, max_length=100)


class Patient(BaseModel):
    age_years: int | None = Field(default=None, ge=0, le=130)
    sex: Literal["M", "F", "O", "U"] | None = None
    weight_kg: float | None = Field(default=None, gt=0.3, le=400)
    serum_creatinine_mg_dl: float | None = Field(default=None, gt=0.05, le=30)


class CheckRequest(BaseModel):
    prescriptions: list[DrugLine] = Field(max_length=30)
    allergies: list[Allergy] = Field(default_factory=list, max_length=50)
    patient: Patient = Field(default_factory=Patient)


class Alert(BaseModel):
    alert_type: AlertType
    severity: Severity
    rule_code: str
    message: str
    management: str | None = None
    source: str | None = None
    #: indexes into the request's prescription lines
    lines: list[int]
    #: allergen text from the request, for allergy alerts
    allergen: str | None = None


class CheckResponse(BaseModel):
    kb_version: str
    kb_reviewed: bool
    alerts: list[Alert]
    #: generics recognised per line (empty when a drug is not in the knowledge base)
    recognised: list[list[str]]
    creatinine_clearance_ml_min: float | None = None
    not_checked: list[str] = Field(default_factory=list)


class Engine:
    def __init__(self, kb: KnowledgeBase) -> None:
        self.kb = kb
        self.resolver = Resolver(kb)
        self._classes = {g.id: set(g.classes) for g in kb.generics}

    # ------------------------------------------------------------------ public

    def check(self, request: CheckRequest) -> CheckResponse:
        lines = [self.resolver.ingredients(line.drug_text) for line in request.prescriptions]
        alerts: list[Alert] = []
        not_checked: list[str] = []
        alerts += self._interactions(lines)
        alerts += self._duplicates(lines)
        alerts += self._allergies(lines, request.allergies)
        alerts += self._doses(lines, request, not_checked)
        alerts += self._older_adult(lines, request.patient)
        crcl = creatinine_clearance(request.patient)
        if crcl is None:
            if any(lines):
                not_checked.append("renal: needs age, sex, weight and serum creatinine")
        else:
            alerts += self._renal(lines, crcl)
        unknown = [i for i, found in enumerate(lines) if not found]
        if unknown:
            not_checked.append("not in the knowledge base: line " + ", ".join(str(i + 1) for i in unknown))
        alerts.sort(key=lambda a: (SEVERITY_RANK[a.severity], a.lines, a.rule_code))
        return CheckResponse(kb_version=self.kb.version, kb_reviewed=self.kb.reviewed, alerts=alerts,
                             recognised=[[i.generic for i in found] for found in lines],
                             creatinine_clearance_ml_min=None if crcl is None else round(crcl, 1),
                             not_checked=not_checked)

    # ------------------------------------------------------------------ rules

    def _matches(self, generic: str, ref: str) -> bool:
        return ref[6:] in self._classes.get(generic, set()) if ref.startswith("class:") else generic == ref

    def _interactions(self, lines: list[list[Ingredient]]) -> list[Alert]:
        alerts = []
        for (i, first), (j, second) in combinations(enumerate(lines), 2):
            for rule in self.kb.interactions:
                hit = any(self._pair(rule.a, rule.b, x.generic, y.generic) for x in first for y in second)
                if hit:
                    alerts.append(Alert(alert_type="DRUG_DRUG", severity=rule.severity, rule_code=rule.code,
                                        message=rule.effect, management=rule.management, source=rule.source,
                                        lines=[i, j]))
        return alerts

    def _pair(self, a: list[str], b: list[str], x: str, y: str) -> bool:
        def side(refs: list[str], generic: str) -> bool:
            return any(self._matches(generic, ref) for ref in refs)
        return (side(a, x) and side(b, y)) or (side(a, y) and side(b, x))

    def _duplicates(self, lines: list[list[Ingredient]]) -> list[Alert]:
        alerts = []
        seen_generic: dict[str, list[int]] = {}
        for index, found in enumerate(lines):
            for ingredient in found:
                seen_generic.setdefault(ingredient.generic, []).append(index)
        for generic, indexes in seen_generic.items():
            if len(set(indexes)) > 1:
                name = self.kb.generic(generic).name
                alerts.append(Alert(alert_type="DUPLICATE_THERAPY", severity="MAJOR", rule_code=f"DUP-{generic.upper()}",
                                    message=f"{name} is prescribed in more than one line (cumulative dose).",
                                    management="Keep one line, or check the combined daily dose.",
                                    lines=sorted(set(indexes))))
        for duplicate in self.kb.duplicate_classes:
            indexes = sorted({i for i, found in enumerate(lines)
                              for x in found if duplicate.class_ in self._classes.get(x.generic, set())})
            generics = {x.generic for i in indexes for x in lines[i] if duplicate.class_ in self._classes.get(x.generic, set())}
            if len(indexes) > 1 and len(generics) > 1:
                alerts.append(Alert(alert_type="DUPLICATE_THERAPY", severity=duplicate.severity,
                                    rule_code=f"DUP-CLASS-{duplicate.class_.upper()}",
                                    message=f"More than one of the {duplicate.label} is prescribed.",
                                    management="Usually only one is needed; review.", lines=indexes))
        return alerts

    def _allergies(self, lines: list[list[Ingredient]], allergies: list[Allergy]) -> list[Alert]:
        alerts: list[Alert] = []
        for allergy in allergies:
            text = " ".join(filter(None, [allergy.allergen, allergy.drug_class])).lower()
            words = set(re.findall(r"[a-z0-9-]+", text))
            direct = set(self.resolver.generics_in(text))
            groups = [g for g in self.kb.allergy_groups if words & set(g.terms)]
            for index, found in enumerate(lines):
                for ingredient in found:
                    name = self.kb.generic(ingredient.generic).name
                    if ingredient.generic in direct or any(
                            self._matches(ingredient.generic, ref) for g in groups for ref in g.contraindicated):
                        alerts.append(Alert(alert_type="DRUG_ALLERGY", severity="CONTRAINDICATED",
                                            rule_code=f"ALG-{ingredient.generic.upper()}",
                                            message=f"{name}: the patient has a recorded allergy ({allergy.allergen}).",
                                            management="Do not give; choose an alternative.",
                                            lines=[index], allergen=allergy.allergen))
                        continue
                    for group in groups:
                        cross = next((c for c in group.cross if self._matches(ingredient.generic, c.ref)), None)
                        if cross:
                            alerts.append(Alert(alert_type="DRUG_ALLERGY", severity=cross.severity,
                                                rule_code=f"ALG-{group.code}-{ingredient.generic.upper()}",
                                                message=f"{name}: possible cross-reaction with the recorded "
                                                        f"allergy ({allergy.allergen}). {cross.note}",
                                                management="Check the history of the reaction before giving.",
                                                lines=[index], allergen=allergy.allergen))
                            break
        return _unique(alerts)

    def _doses(self, lines: list[list[Ingredient]], request: CheckRequest, not_checked: list[str]) -> list[Alert]:
        patient = request.patient
        alerts = []
        limits = {d.generic: d for d in self.kb.doses}
        for index, (line, found) in enumerate(zip(request.prescriptions, lines, strict=True)):
            for ingredient in found:
                limit = limits.get(ingredient.generic)
                if limit is None:
                    continue
                daily = daily_dose_mg(line, ingredient)
                name = self.kb.generic(ingredient.generic).name
                if daily is None:
                    not_checked.append(f"dose of {name} (line {index + 1}): strength, dose or frequency not clear")
                    continue
                code = f"DOSE-{ingredient.generic.upper()}"
                if patient.age_years is not None and patient.age_years < ADULT_AGE:
                    if limit.paediatric_max_mg_kg_day and patient.weight_kg:
                        per_kg = daily / patient.weight_kg
                        cap = limit.adult_max_mg_day
                        if per_kg > limit.paediatric_max_mg_kg_day or (cap and daily > cap):
                            alerts.append(Alert(
                                alert_type="DOSE_RANGE", severity=limit.severity, rule_code=code + "-PAEDIATRIC",
                                message=f"{name} {daily:g} mg/day is {per_kg:.1f} mg/kg/day for {patient.weight_kg:g} kg "
                                        f"(maximum {limit.paediatric_max_mg_kg_day:g} mg/kg/day).",
                                management="Recalculate the dose by weight.", source=limit.source, lines=[index]))
                    elif limit.paediatric_max_mg_kg_day:
                        not_checked.append(f"paediatric dose of {name}: needs the child's weight")
                    continue
                if limit.adult_max_mg_day and daily > limit.adult_max_mg_day:
                    alerts.append(Alert(alert_type="DOSE_RANGE", severity=limit.severity, rule_code=code + "-MAX",
                                        message=f"{name} {daily:g} mg/day exceeds the maximum of "
                                                f"{limit.adult_max_mg_day:g} mg/day.",
                                        management="Reduce the dose or frequency.", source=limit.source, lines=[index]))
                elif (limit.geriatric_max_mg_day and patient.age_years is not None
                      and patient.age_years >= OLDER_ADULT_AGE and daily > limit.geriatric_max_mg_day):
                    alerts.append(Alert(alert_type="DOSE_RANGE", severity="MODERATE", rule_code=code + "-OLDER-ADULT",
                                        message=f"{name} {daily:g} mg/day in a patient aged {patient.age_years}. "
                                                f"{limit.geriatric_note or ''}".strip(),
                                        source=limit.source, lines=[index]))
        return alerts

    def _older_adult(self, lines: list[list[Ingredient]], patient: Patient) -> list[Alert]:
        if patient.age_years is None or patient.age_years < OLDER_ADULT_AGE:
            return []
        alerts = []
        for index, found in enumerate(lines):
            for ingredient in found:
                for caution in self.kb.geriatric:
                    if self._matches(ingredient.generic, caution.ref):
                        name = self.kb.generic(ingredient.generic).name
                        alerts.append(Alert(alert_type="CONTRAINDICATION", severity=caution.severity,
                                            rule_code=f"GERI-{ingredient.generic.upper()}",
                                            message=f"{name} in a patient aged {patient.age_years}: {caution.note}",
                                            source=caution.source, lines=[index]))
        return alerts

    def _renal(self, lines: list[list[Ingredient]], crcl: float) -> list[Alert]:
        alerts = []
        for index, found in enumerate(lines):
            for ingredient in found:
                applicable = [r for r in self.kb.renal
                              if self._matches(ingredient.generic, r.ref) and crcl < r.crcl_below]
                if not applicable:
                    continue
                rule = min(applicable, key=lambda r: (SEVERITY_RANK[r.severity], r.crcl_below))
                name = self.kb.generic(ingredient.generic).name
                alerts.append(Alert(alert_type="CONTRAINDICATION", severity=rule.severity,
                                    rule_code=f"RENAL-{ingredient.generic.upper()}-{int(rule.crcl_below)}",
                                    message=f"{name} with creatinine clearance {crcl:.0f} mL/min: {rule.note}",
                                    source=rule.source, lines=[index]))
        return alerts


# ---------------------------------------------------------------------- helpers

_PER_ML = re.compile(r"(\d+(?:\.\d+)?)\s*(mg|mcg)\s*/\s*(\d+(?:\.\d+)?)\s*ml", re.IGNORECASE)
_UNITS = re.compile(r"^\s*(\d+(?:\.\d+)?|\d+\s*/\s*\d+|half|one|two|three)\s*(tab|tabs|tablet|tablets|cap|caps|capsule|"
                    r"capsules|ml|tsp|teaspoon|puff|puffs|drop|drops|sachet|unit|units)?\b", re.IGNORECASE)
_WORDS = {"half": 0.5, "one": 1, "two": 2, "three": 3}


def daily_dose_mg(line: DrugLine, ingredient: Ingredient) -> float | None:
    """Strength x units per dose x doses per day, or None when any part is not clear."""
    if line.frequency not in PER_DAY or ingredient.strength_mg is None:
        return None
    per_day = PER_DAY[line.frequency]
    concentration = _PER_ML.search(line.drug_text)
    dose = (line.dose or "1").strip()
    match = _UNITS.match(dose)
    if not match:
        return None
    raw, unit = match.group(1).lower(), (match.group(2) or "").lower()
    if "/" in raw:
        top, bottom = (float(x) for x in raw.split("/"))
        amount = top / bottom
    else:
        amount = _WORDS.get(raw) or float(raw)
    if unit in ("tsp", "teaspoon"):
        amount, unit = amount * 5, "ml"
    if unit == "ml":
        if not concentration:
            return None
        mg = float(concentration.group(1)) / (1000 if concentration.group(2).lower() == "mcg" else 1)
        per_unit = mg / float(concentration.group(3))
        return round(amount * per_unit * per_day, 2)
    if unit in ("puff", "puffs", "drop", "drops", "unit", "units"):
        return None
    if concentration:
        return None  # a liquid prescribed by tablets: not computable
    return round(ingredient.strength_mg * amount * per_day, 2)


def creatinine_clearance(patient: Patient) -> float | None:
    """Cockcroft-Gault, mL/min (x 0.85 for women); adults only."""
    if (patient.age_years is None or patient.age_years < ADULT_AGE or patient.weight_kg is None
            or patient.serum_creatinine_mg_dl is None or patient.sex not in ("M", "F")):
        return None
    clearance = (140 - patient.age_years) * patient.weight_kg / (72 * patient.serum_creatinine_mg_dl)
    return clearance * (0.85 if patient.sex == "F" else 1.0)


def _unique(alerts: list[Alert]) -> list[Alert]:
    seen: set[tuple[str, tuple[int, ...]]] = set()
    result = []
    for alert in alerts:
        key = (alert.rule_code, tuple(alert.lines))
        if key not in seen:
            seen.add(key)
            result.append(alert)
    return result
