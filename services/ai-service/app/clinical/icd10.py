"""ICD-10 (WHO) codes for diagnoses common in an Indian secondary-care OPD.

A look-up table, not a full classification: dictated or typed diagnoses are matched on
whole words (longest synonym first) and offered as a *suggestion* the doctor confirms.
Extend the table, or replace :func:`suggest` with a terminology service, as needed.
"""

import re

# (code, WHO ICD-10 title, synonyms as said in clinic)
CODES: list[tuple[str, str, list[str]]] = [
    ("J06.9", "Acute upper respiratory infection, unspecified",
     ["upper respiratory tract infection", "upper respiratory infection", "urti", "uri", "common cold", "coryza"]),
    ("J02.9", "Acute pharyngitis, unspecified", ["pharyngitis", "sore throat"]),
    ("J03.9", "Acute tonsillitis, unspecified", ["tonsillitis"]),
    ("J20.9", "Acute bronchitis, unspecified", ["acute bronchitis", "bronchitis"]),
    ("J18.9", "Pneumonia, unspecified organism", ["pneumonia", "lrti", "lower respiratory tract infection"]),
    ("J45.9", "Asthma, unspecified", ["asthma", "bronchial asthma"]),
    ("J44.9", "Chronic obstructive pulmonary disease, unspecified", ["copd", "chronic obstructive pulmonary disease"]),
    ("J30.4", "Allergic rhinitis, unspecified", ["allergic rhinitis"]),
    ("H66.9", "Otitis media, unspecified", ["otitis media", "aom", "csom"]),
    ("A90", "Dengue fever [classical dengue]", ["dengue", "dengue fever"]),
    ("A01.0", "Typhoid fever", ["typhoid", "typhoid fever", "enteric fever"]),
    ("B54", "Unspecified malaria", ["malaria"]),
    ("B34.9", "Viral infection, unspecified", ["viral fever", "viral infection", "viral illness"]),
    ("R50.9", "Fever, unspecified", ["fever", "pyrexia", "pyrexia of unknown origin", "puo"]),
    ("A09", "Other gastroenteritis and colitis of infectious and unspecified origin",
     ["acute gastroenteritis", "gastroenteritis", "age", "diarrhoea", "diarrhea", "loose motions"]),
    ("K29.7", "Gastritis, unspecified", ["gastritis", "acid peptic disease", "apd"]),
    ("K21.9", "Gastro-oesophageal reflux disease without oesophagitis", ["gerd", "gord", "acid reflux", "reflux disease"]),
    ("N39.0", "Urinary tract infection, site not specified", ["urinary tract infection", "uti"]),
    ("N20.0", "Calculus of kidney", ["renal calculus", "kidney stone", "nephrolithiasis", "renal stone"]),
    ("I10", "Essential (primary) hypertension", ["hypertension", "htn", "high blood pressure"]),
    ("I25.9", "Chronic ischaemic heart disease, unspecified", ["ischaemic heart disease", "ischemic heart disease", "ihd", "cad",
                                                            "coronary artery disease"]),
    ("E11.9", "Type 2 diabetes mellitus without complications",
     ["type 2 diabetes mellitus", "type 2 diabetes", "t2dm", "diabetes mellitus", "diabetes", "dm"]),
    ("E03.9", "Hypothyroidism, unspecified", ["hypothyroidism"]),
    ("D64.9", "Anaemia, unspecified", ["anaemia", "anemia"]),
    ("G43.9", "Migraine, unspecified", ["migraine"]),
    ("R51", "Headache", ["headache", "cephalgia"]),
    ("M54.5", "Low back pain", ["low back pain", "lbp", "lumbago", "backache"]),
    ("L30.9", "Dermatitis, unspecified", ["dermatitis", "eczema"]),
]

_ENTRIES = sorted(((" " + s + " ", code, title) for code, title, synonyms in CODES for s in synonyms),
                  key=lambda e: -len(e[0]))


def _words(text: str) -> str:
    return " " + " ".join(re.findall(r"[a-z0-9]+", text.lower())) + " "


def suggest(diagnosis: str) -> tuple[str, str] | None:
    """The ICD-10 code and title best matching a diagnosis phrase, if any."""
    words = _words(diagnosis)
    for synonym, code, title in _ENTRIES:
        if synonym in words:
            return code, title
    return None


def search(query: str, limit: int = 10) -> list[tuple[str, str]]:
    """Codes whose code, title or synonyms contain the query (for a pick-list)."""
    q = query.strip().lower()
    if len(q) < 2:
        return []
    results = []
    for code, title, synonyms in CODES:
        if q in code.lower() or q in title.lower() or any(q in s for s in synonyms):
            results.append((code, title))
    return results[:limit]
