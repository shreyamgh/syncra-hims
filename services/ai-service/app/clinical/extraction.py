"""Rule-based extraction of vitals, complaints and prescriptions from dictation.

Deterministic and auditable: every value comes from an explicit pattern over the
transcript, nothing leaves the hospital network, and out-of-range values are
dropped with a warning rather than guessed. Output enums mirror the database
(``clinical.vital_sign``, ``clinical.complaint``, ``clinical.prescription_item``)
so the Doctor Desk can save extracted rows without translation.

Recognised phrasing follows common Indian OPD dictation, e.g.::

    "c/o fever for 3 days and cough since 1 week. BP 130/80, pulse 96,
     temp 101 F, SpO2 97%. Tab Paracetamol 650 mg 1-0-1 after food x 5 days."
"""

import re
from typing import Literal

from pydantic import BaseModel, Field

from app.clinical import icd10

DurationUnit = Literal["HOURS", "DAYS", "WEEKS", "MONTHS", "YEARS"]


class Vitals(BaseModel):
    temperature_c: float | None = None
    pulse_bpm: int | None = None
    respiratory_rate: int | None = None
    bp_systolic: int | None = None
    bp_diastolic: int | None = None
    spo2_percent: int | None = None
    weight_kg: float | None = None
    height_cm: float | None = None
    blood_glucose_mg_dl: int | None = None


class Complaint(BaseModel):
    description: str
    duration_value: int | None = None
    duration_unit: DurationUnit | None = None


class PrescriptionLine(BaseModel):
    drug_text: str
    drug_name: str
    dosage_form: str | None = None
    strength: str | None = None
    dose: str | None = None
    route: str | None = None
    frequency: str | None = None
    timing: str | None = None
    duration_days: int | None = None


class Diagnosis(BaseModel):
    description: str
    diagnosis_type: Literal["PROVISIONAL", "FINAL"] = "PROVISIONAL"
    #: suggested from the ICD-10 look-up table; the doctor confirms it
    icd10_code: str | None = None
    icd10_title: str | None = None


class ClinicalExtraction(BaseModel):
    vitals: Vitals = Field(default_factory=Vitals)
    complaints: list[Complaint] = Field(default_factory=list)
    diagnoses: list[Diagnosis] = Field(default_factory=list)
    prescriptions: list[PrescriptionLine] = Field(default_factory=list)
    warnings: list[str] = Field(default_factory=list)


# --------------------------------------------------------------------------- numbers
_WORD_NUMBERS = {
    "a": 1,
    "an": 1,
    "one": 1,
    "two": 2,
    "three": 3,
    "four": 4,
    "five": 5,
    "six": 6,
    "seven": 7,
    "eight": 8,
    "nine": 9,
    "ten": 10,
    "eleven": 11,
    "twelve": 12,
    "fourteen": 14,
    "fifteen": 15,
    "twenty": 20,
    "thirty": 30,
}
_NUM = r"(\d{1,3}|" + "|".join(sorted(_WORD_NUMBERS, key=len, reverse=True)) + r")"


def _number(token: str) -> int:
    return int(token) if token.isdigit() else _WORD_NUMBERS[token.lower()]


def _normalise(text: str) -> str:
    t = text.replace("°", " ").replace("–", "-").replace("—", "-").replace("×", " x ")
    t = re.sub(r"\bper\s*cent\b|\bpercent\b", "%", t, flags=re.IGNORECASE)
    t = re.sub(r"\bmm\s*(?:of\s*)?hg\b", "", t, flags=re.IGNORECASE)
    return re.sub(r"\s+", " ", t).strip()


# --------------------------------------------------------------------------- vitals
_GAP = r"[^\d\n]{0,15}?"  # "is", "of", ":", "was", "around" between label and value
_I = re.IGNORECASE

_BP = re.compile(r"\b(?:bp|b\.p\.?|blood pressure)" + _GAP + r"(\d{2,3})\s*(?:/|by|over|upon)\s*(\d{2,3})\b", _I)
_SIMPLE_VITALS: dict[str, tuple[re.Pattern[str], type, float, float]] = {
    "pulse_bpm": (re.compile(r"\b(?:pulse(?: rate)?|heart rate|hr)\b" + _GAP + r"(\d{2,3})\b", _I), int, 20, 250),
    "respiratory_rate": (
        re.compile(r"\b(?:resp(?:iratory)? rate|rr|respirations?)\b" + _GAP + r"(\d{1,2})\b", _I),
        int,
        4,
        80,
    ),
    "spo2_percent": (
        re.compile(
            r"\b(?:spo2|sp o2|sp02|oxygen saturation|o2 sat(?:uration)?|saturation|saturating|sats?)\b"
            + _GAP
            + r"(\d{2,3})\b",
            _I,
        ),
        int,
        30,
        100,
    ),
    "weight_kg": (
        re.compile(r"\b(?:weight|wt|weighs|weighing)\b" + _GAP + r"(\d{1,3}(?:\.\d{1,2})?)", _I),
        float,
        0.3,
        400,
    ),
    "height_cm": (re.compile(r"\b(?:height|ht)\b" + _GAP + r"(\d{2,3}(?:\.\d)?)", _I), float, 20, 250),
    "blood_glucose_mg_dl": (
        re.compile(
            r"\b(?:(?:random|fasting|post ?prandial|capillary)\s+)?(?:blood\s+)?"
            r"(?:sugar|glucose|rbs|fbs|ppbs|grbs|cbg)\b" + _GAP + r"(\d{2,4})\b",
            _I,
        ),
        int,
        10,
        1500,
    ),
}
_TEMPERATURE = re.compile(
    r"\b(?:temperature|temp|febrile)\b"
    + _GAP
    + r"(\d{2,3}(?:\.\d{1,2})?)\s*(?:degrees?\s*)?(fahrenheit|celsius|centigrade|f|c)?\b",
    _I,
)


def _extract_vitals(text: str, warnings: list[str]) -> Vitals:
    vitals = Vitals()

    # The last mention wins, so a doctor can correct themselves ("pulse 88, sorry, 92").
    if bp := _last(_BP, text):
        systolic, diastolic = int(bp.group(1)), int(bp.group(2))
        if 40 <= systolic <= 300 and 20 <= diastolic <= 200 and systolic > diastolic:
            vitals.bp_systolic, vitals.bp_diastolic = systolic, diastolic
        else:
            warnings.append(f"Ignored implausible blood pressure {systolic}/{diastolic}")

    for field, (pattern, cast, low, high) in _SIMPLE_VITALS.items():
        if match := _last(pattern, text):
            value = cast(float(match.group(1)))
            if low <= value <= high:
                setattr(vitals, field, value)
            else:
                warnings.append(f"Ignored out-of-range {field.replace('_', ' ')} {match.group(1)}")

    if match := _last(_TEMPERATURE, text):
        value = float(match.group(1))
        unit = (match.group(2) or "").lower()
        fahrenheit = unit.startswith("f") or (not unit and value > 45)
        celsius = round((value - 32) * 5 / 9, 1) if fahrenheit else value
        if 25 <= celsius <= 45:
            vitals.temperature_c = celsius
        else:
            warnings.append(f"Ignored out-of-range temperature {match.group(1)}")
    return vitals


def _last(pattern: re.Pattern[str], text: str) -> re.Match[str] | None:
    match = None
    for match in pattern.finditer(text):  # noqa: B007 - we want the final match
        pass
    return match


# --------------------------------------------------------------------------- complaints
_COMPLAINT_TRIGGER = re.compile(
    r"\b(?:complain(?:s|ing|ts)?\s+of|c/o|presenting\s+with|presented\s+with|presents\s+with|came\s+with|"
    r"chief\s+complaints?(?:\s+(?:are|is|of))?:?|(?:is\s+)?having)\s+",
    _I,
)
_COMPLAINT_END = re.compile(
    r"(?:(?<!\d)\.(?!\d)|[;\n]|\b(?:on examination|o/e|examination|vitals?|bp|blood pressure|pulse|temperature|"
    r"temp|spo2|weight|advised?|prescrib\w*|rx|plan|diagnosis|impression|tab|tablet|cap|capsule|syrup|inj)\b)",
    _I,
)
_COMPLAINT_SPLIT = re.compile(r",|\band\b|\balong with\b|\bassociated with\b|\bwith\b", _I)
# Spelled-out amounts ("a week", "two days") only count after a preposition; digits always do.
_DURATION = re.compile(
    r"\b(?:(?:for|since|from|x)\s+(?:the\s+)?(?:(?:past|last)\s+)?" + _NUM + r"|(\d{1,3}))"
    r"\s*(hours?|hrs?|days?|weeks?|wks?|months?|years?|yrs?)\b",
    _I,
)
_UNIT = {"h": "HOURS", "d": "DAYS", "w": "WEEKS", "m": "MONTHS", "y": "YEARS"}
_FILLER = re.compile(r"^(?:of|a|an|the|some|mild|having|since|for)\s+|\s+(?:since|for|from)$", _I)


def _extract_complaints(text: str) -> list[Complaint]:
    complaints: dict[str, Complaint] = {}
    for trigger in _COMPLAINT_TRIGGER.finditer(text):
        rest = text[trigger.end() :]
        end = _COMPLAINT_END.search(rest)
        segment = rest[: end.start()] if end else rest
        for item in _COMPLAINT_SPLIT.split(segment):
            complaint = _parse_complaint(item)
            if complaint:
                complaints[complaint.description.lower()] = complaint
    return list(complaints.values())


def _parse_complaint(item: str) -> Complaint | None:
    duration = _DURATION.search(item)
    value = unit = None
    if duration:
        value = _number(duration.group(1) or duration.group(2))
        unit = _UNIT[duration.group(3)[0].lower()]
        item = item[: duration.start()] + " " + item[duration.end() :]
    description = re.sub(r"\s+", " ", item).strip(" ,.-:")
    for _ in range(3):
        description = _FILLER.sub("", description).strip(" ,.-:")
    if len(description) < 3 or not re.search(r"[a-z]", description, _I):
        return None
    return Complaint(description=description[0].upper() + description[1:], duration_value=value, duration_unit=unit)


# --------------------------------------------------------------------------- diagnoses
_DIAGNOSIS_TRIGGER = re.compile(
    r"\b(?:(provisional|final|working|clinical)\s+)?(?:diagnosis|impression|dx|diagnosed\s+(?:with|as))"
    r"\s*(?:is|of|:|-)?\s*",
    _I,
)
_DIAGNOSIS_END = re.compile(
    r"(?:(?<!\d)\.(?!\d)|[;\n]|\b(?:advised?|prescrib\w*|rx|plan|tab|tablet|cap|capsule|syrup|inj|review|"
    r"follow[- ]?up|bp|pulse|temp|spo2|investigations?)\b)",
    _I,
)


def _extract_diagnoses(text: str) -> list[Diagnosis]:
    found: dict[str, Diagnosis] = {}
    for trigger in _DIAGNOSIS_TRIGGER.finditer(text):
        rest = text[trigger.end() :]
        end = _DIAGNOSIS_END.search(rest)
        segment = rest[: end.start()] if end else rest
        kind = "FINAL" if (trigger.group(1) or "").lower() == "final" else "PROVISIONAL"
        for item in re.split(r",|\band\b", segment, flags=_I):
            description = re.sub(r"\s+", " ", item).strip(" ,.-:")
            description = re.sub(r"^(?:of|a|an|the|likely|probable|possible)\s+", "", description, flags=_I)
            if len(description) < 3 or not re.search(r"[a-z]", description, _I):
                continue
            code = icd10.suggest(description)
            found[description.lower()] = Diagnosis(
                description=description[0].upper() + description[1:], diagnosis_type=kind,
                icd10_code=code[0] if code else None, icd10_title=code[1] if code else None)
    return list(found.values())


# --------------------------------------------------------------------------- prescriptions
_FORMS = {
    "tab": "TABLET",
    "tabs": "TABLET",
    "tablet": "TABLET",
    "tablets": "TABLET",
    "cap": "CAPSULE",
    "caps": "CAPSULE",
    "capsule": "CAPSULE",
    "capsules": "CAPSULE",
    "syrup": "SYRUP",
    "syp": "SYRUP",
    "syr": "SYRUP",
    "suspension": "SUSPENSION",
    "susp": "SUSPENSION",
    "inj": "INJECTION",
    "injection": "INJECTION",
    "drops": "DROPS",
    "drop": "DROPS",
    "ointment": "OINTMENT",
    "oint": "OINTMENT",
    "cream": "CREAM",
    "gel": "GEL",
    "lotion": "LOTION",
    "inhaler": "INHALER",
    "sachet": "POWDER",
    "powder": "POWDER",
    "spray": "SPRAY",
    "nebulization": "NEBULE",
    "nebulisation": "NEBULE",
    "neb": "NEBULE",
}
_FORM_LABEL = {
    "TABLET": "Tab",
    "CAPSULE": "Cap",
    "SYRUP": "Syp",
    "SUSPENSION": "Susp",
    "INJECTION": "Inj",
    "DROPS": "Drops",
    "OINTMENT": "Oint",
    "CREAM": "Cream",
    "GEL": "Gel",
    "LOTION": "Lotion",
    "INHALER": "Inhaler",
    "POWDER": "Sachet",
    "SPRAY": "Spray",
    "NEBULE": "Neb",
}
_FORM_ROUTE = {
    "TABLET": "ORAL",
    "CAPSULE": "ORAL",
    "SYRUP": "ORAL",
    "SUSPENSION": "ORAL",
    "POWDER": "ORAL",
    "OINTMENT": "TOPICAL",
    "CREAM": "TOPICAL",
    "GEL": "TOPICAL",
    "LOTION": "TOPICAL",
    "INHALER": "INHALATION",
    "NEBULE": "INHALATION",
}
_FORM_RE = re.compile(r"\b(" + "|".join(sorted(_FORMS, key=len, reverse=True)) + r")\b\.?", _I)
_SENTENCE_END = re.compile(r"(?<!\d)\.(?!\d)|[;\n]")
_QUANTITY_BEFORE = re.compile(r"(?:\d|\bhalf|\bone|\btwo|\bthree)\s*$", _I)
_NAME_STOP = re.compile(
    r"\d|\b(?:od|bd|bid|tds|tid|qid|hs|sos|stat|prn|once|twice|thrice|daily|every|each|at|after|before|with|"
    r"on|for|x|morning|night|bedtime|empty|half|one|two|three|as|when|weekly|alternate|in|to|apply|iv|im|sc|"
    r"intravenous|intramuscular|subcutaneous|sublingual|eye|ear|nasal|puffs?|mg|ml|mcg|and|then|also|plus|"
    r"till|until|q\d+h)\b",
    _I,
)
_BARE_NUMBER = re.compile(
    r"\s*(\d{1,4})\b(?!\s*(?:-|/|\.\d|mg|mcg|g\b|gm|ml|iu|units?|%|days?|weeks?|months?|tab|cap|times|puffs?|"
    r"drops?|tsp|teaspoons?|sachets?))",
    _I,
)
_STRENGTH = re.compile(r"(\d+(?:\.\d+)?)\s*(mg|mcg|gm|g|iu|units?|%)(?:\s*/\s*(\d+(?:\.\d+)?)\s*(ml))?(?![a-z])", _I)
_DOSE = re.compile(
    r"\b(half|one|two|three|1/2|\d+(?:\.\d+)?)\s*(tablets?|tabs?|capsules?|caps?|ml|puffs?|drops?|"
    r"teaspoons?|tsp|sachets?)\b",
    _I,
)
_PART = r"([0-2](?:\.5)?)"  # one slot of a "1-0-1" dosing matrix
_MATRIX = re.compile(
    r"(?<![\d.])" + _PART + r"\s*-\s*" + _PART + r"\s*-\s*" + _PART + r"(?:\s*-\s*" + _PART + r")?(?![\d.])"
)
_FREQUENCIES: list[tuple[re.Pattern[str], str]] = [
    (re.compile(p, _I), code)
    for p, code in [
        (r"\b(?:qid|q\.i\.d|(?:four|4) times(?: a day| per day| daily)?)\b", "QID"),
        (r"\b(?:tds|tid|t\.d\.s|thrice(?: a day| daily)?|(?:three|3) times(?: a day| per day| daily)?)\b", "TDS"),
        (r"\b(?:bd|bid|b\.d|twice(?: a day| daily)?|(?:two|2) times(?: a day| per day| daily)?)\b", "BD"),
        (r"\b(?:sos|prn|as (?:and when )?(?:needed|required)|when (?:needed|required))\b", "SOS"),
        (r"\b(?:stat|immediately)\b", "STAT"),
        (r"\b(?:every|q)\s*4\s*(?:hours?|hrly|h)\b", "Q4H"),
        (r"\b(?:every|q)\s*6\s*(?:hours?|hrly|h)\b", "Q6H"),
        (r"\b(?:every|q)\s*8\s*(?:hours?|hrly|h)\b", "Q8H"),
        (r"\b(?:once a week|weekly)\b", "WEEKLY"),
        (r"\b(?:alternate days?|every other day)\b", "ALT_DAYS"),
        (r"\b(?:hs|at bedtime|bedtime|at night|night time)\b", "HS"),
        (
            r"\b(?:od|o\.d|once(?: a day| daily)?|(?:one|1) times? (?:a|per) day|daily|every day|every morning|"
            r"in the morning)\b",
            "OD",
        ),
    ]
]
_TIMINGS: list[tuple[re.Pattern[str], str]] = [
    (re.compile(p, _I), code)
    for p, code in [
        (r"\bempty stomach\b", "EMPTY_STOMACH"),
        (r"\bafter (?:food|meals?|eating|lunch|dinner|breakfast)\b", "AFTER_FOOD"),
        (r"\bbefore (?:food|meals?|eating|lunch|dinner|breakfast)\b", "BEFORE_FOOD"),
        (r"\bwith (?:food|meals?|milk)\b", "WITH_FOOD"),
        (r"\b(?:at bedtime|bedtime|at night)\b", "BEDTIME"),
    ]
]
_ROUTES: list[tuple[re.Pattern[str], str]] = [
    (re.compile(p, _I), code)
    for p, code in [
        (r"\b(?:iv|intravenous(?:ly)?)\b", "IV"),
        (r"\b(?:im|intramuscular(?:ly)?)\b", "IM"),
        (r"\b(?:sc|s/c|subcutaneous(?:ly)?|subcut)\b", "SC"),
        (r"\bsublingual(?:ly)?\b", "SUBLINGUAL"),
        (r"\beyes?\b", "OPHTHALMIC"),
        (r"\bears?\b", "OTIC"),
        (r"\b(?:nasal|nose|nostrils?)\b", "NASAL"),
        (r"\b(?:rectal|per rectum)\b", "RECTAL"),
    ]
]
# "for 7 days", "x 5 days", "for a week" — or a bare "5 days". A bare "a day" is never a
# duration: in "3 times a day" it is part of the frequency.
_DAYS_PREFIXED = re.compile(r"\b(?:for|x|next|till)\s*" + _NUM + r"\s*(days?|weeks?|months?)\b", _I)
_DAYS_BARE = re.compile(r"\b(\d{1,3})\s*(days?|weeks?|months?)\b", _I)
_DAYS_PER_UNIT = {"d": 1, "w": 7, "m": 30}


def _extract_prescriptions(text: str) -> list[PrescriptionLine]:
    lines: dict[str, PrescriptionLine] = {}
    # A form word after a quantity is a dose ("1 drop", "2 tabs"), not the start of another drug.
    matches = [m for m in _FORM_RE.finditer(text) if not _QUANTITY_BEFORE.search(text[: m.start()])]
    for i, form_match in enumerate(matches):
        stop = matches[i + 1].start() if i + 1 < len(matches) else len(text)
        segment = text[form_match.end() : stop]
        if sentence_end := _SENTENCE_END.search(segment):
            segment = segment[: sentence_end.start()]
        # Words just before the form ("eye drops", "nasal spray") qualify the route.
        context = text[max(0, form_match.start() - 12) : form_match.start()] + segment
        line = _parse_line(_FORMS[form_match.group(1).lower()], segment, context)
        if line:
            lines[line.drug_name.lower()] = line
    return list(lines.values())


def _parse_line(form: str, segment: str, context: str) -> PrescriptionLine | None:
    stop = _NAME_STOP.search(segment)
    name = segment[: stop.start()] if stop else segment
    name = re.sub(r"\s+", " ", name).strip(" ,-:")
    rest = segment[len(segment) - len(segment.lstrip()) + len(name) :] if name else segment
    if bare := _BARE_NUMBER.match(rest):  # brand strengths written without units: "Pan 40", "Dolo 650"
        name = f"{name} {bare.group(1)}"
    words = name.split()[:4]
    if not words:
        return None
    name = " ".join(w[0].upper() + w[1:] for w in words)

    strength_match = _STRENGTH.search(segment)
    strength = None
    if strength_match:
        strength = f"{strength_match.group(1)} {strength_match.group(2).lower()}"
        if strength_match.group(3):
            strength += f"/{strength_match.group(3)} ml"

    frequency = None
    dose = None
    if matrix := _MATRIX.search(segment):
        parts = [p for p in matrix.groups() if p is not None]
        frequency = _matrix_frequency(parts)
        unit = {"TABLET": "tab", "CAPSULE": "cap"}.get(form)
        largest = max(parts, key=float)
        if unit and float(largest) > 0:
            dose = f"{largest.rstrip('0').rstrip('.') if '.' in largest else largest} {unit}"
    if frequency is None:
        frequency = next((code for pattern, code in _FREQUENCIES if pattern.search(segment)), None)

    dose_spans = [m for m in _DOSE.finditer(segment) if not (strength_match and _overlaps(m, strength_match))]
    if dose_spans:
        d = dose_spans[0]
        dose = f"{d.group(1).lower()} {d.group(2).lower()}"

    timing = next((code for pattern, code in _TIMINGS if pattern.search(segment)), None)
    route = next((code for pattern, code in _ROUTES if pattern.search(context)), _FORM_ROUTE.get(form))

    duration_days = None
    if days := _DAYS_PREFIXED.search(segment) or _DAYS_BARE.search(segment):
        duration_days = _number(days.group(1)) * _DAYS_PER_UNIT[days.group(2)[0].lower()]
        duration_days = duration_days if 1 <= duration_days <= 365 else None

    label = _FORM_LABEL[form]
    drug_text = f"{label} {name}" + (f" {strength}" if strength else "")
    return PrescriptionLine(
        drug_text=drug_text,
        drug_name=name,
        dosage_form=form,
        strength=strength,
        dose=dose,
        route=route,
        frequency=frequency,
        timing=timing,
        duration_days=duration_days,
    )


def _matrix_frequency(parts: list[str]) -> str:
    taken = [float(p) > 0 for p in parts]
    count = sum(taken)
    if len(parts) == 4 and count == 4:
        return "QID"
    if count == 3:
        return "TDS"
    if count == 2:
        return "BD"
    if count == 1:
        return "HS" if taken[-1] and len(parts) == 3 else "OD"
    return "OTHER"


def _overlaps(a: re.Match[str], b: re.Match[str]) -> bool:
    return a.start() < b.end() and b.start() < a.end()


# --------------------------------------------------------------------------- entry point
def extract(transcript: str) -> ClinicalExtraction:
    """Extracts structured findings from a (possibly partial) transcript."""
    text = _normalise(transcript or "")
    if not text:
        return ClinicalExtraction()
    warnings: list[str] = []
    return ClinicalExtraction(
        vitals=_extract_vitals(text, warnings),
        complaints=_extract_complaints(text),
        diagnoses=_extract_diagnoses(text),
        prescriptions=_extract_prescriptions(text),
        warnings=warnings,
    )
