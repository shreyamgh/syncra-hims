import json

import pytest

from app.cdss.engine import CheckRequest, Engine, creatinine_clearance
from app.cdss.knowledge import BUNDLED, KnowledgeBase, load
from app.cdss.resolver import Resolver

KB = load()
ENGINE = Engine(KB)


def check(*lines, allergies=(), **patient):
    request = CheckRequest(
        prescriptions=[line if isinstance(line, dict) else {"drug_text": line} for line in lines],
        allergies=[{"allergen": a} for a in allergies],
        patient=patient,
    )
    return ENGINE.check(request)


def codes(result):
    return {(a.rule_code, a.severity) for a in result.alerts}


# --------------------------------------------------------------------------- knowledge base

def test_bundled_knowledge_base_is_valid_and_marked_for_review():
    assert KB.version
    assert KB.reviewed is False, "the starter set must not claim clinical sign-off"
    assert len(KB.interactions) >= 30


def test_a_rule_naming_an_unknown_drug_stops_loading():
    data = json.loads(BUNDLED.read_text(encoding="utf-8"))
    data["interactions"][0]["b"] = ["class:nsiad"]  # typo
    with pytest.raises(ValueError, match="unknown 'class:nsiad'"):
        KnowledgeBase.model_validate(data)


# --------------------------------------------------------------------------- recognising drugs

@pytest.mark.parametrize(
    ("text", "expected"),
    [
        ("Tab Dolo 650", {"paracetamol": 650}),
        ("Tab Paracetamol 500 mg", {"paracetamol": 500}),
        ("Tab Combiflam", {"ibuprofen": 400, "paracetamol": 325}),
        ("Inj Ceftriaxone 1 g", {"ceftriaxone": 1000}),
        ("Tab Pan 40", {"pantoprazole": 40}),
        ("Syrup Paracetamol 250 mg/5 ml", {"paracetamol": 250}),
        ("Tab Ultracet", {"tramadol": 37.5, "paracetamol": 325}),
    ],
)
def test_generics_and_strengths_are_recognised_from_generic_and_brand_names(text, expected):
    found = {i.generic: i.strength_mg for i in Resolver(KB).ingredients(text)}
    assert found == expected


def test_words_inside_other_words_do_not_match():
    assert Resolver(KB).generics_in("Tab Pantoprazole 40") == ["pantoprazole"]
    assert Resolver(KB).generics_in("Syrup Ascoril") == []


# --------------------------------------------------------------------------- interactions and duplicates

def test_classic_interactions_are_flagged_by_generic_or_brand():
    assert ("DDI-VKA-NSAID", "MAJOR") in codes(check("Tab Warfarin 5 mg", "Tab Brufen 400"))
    assert ("DDI-PDE5-NITRATE", "CONTRAINDICATED") in codes(check("Tab Sildenafil 50 mg", "Tab Monotrate 20"))
    assert ("DDI-SIMVASTATIN-CYP3A4", "CONTRAINDICATED") in codes(check("Tab Simvastatin 20 mg", "Tab Claribid 500"))
    assert ("DDI-CLOPIDOGREL-PPI", "MODERATE") in codes(check("Tab Clopilet 75", "Cap Omez 20"))
    result = check("Tab Warfarin 5 mg", "Tab Brufen 400")
    assert result.alerts[0].lines == [0, 1]
    assert "bleeding" in result.alerts[0].message


def test_safe_combinations_raise_nothing():
    result = check("Tab Paracetamol 650 mg", "Cap Amoxicillin 500 mg", "Tab Pan 40")
    assert result.alerts == []


def test_duplicate_therapy_across_lines():
    assert ("DUP-PARACETAMOL", "MAJOR") in codes(check("Tab Dolo 650", "Tab Combiflam"))
    assert ("DUP-CLASS-NSAID", "MAJOR") in codes(check("Tab Ibuprofen 400 mg", "Tab Voveran 50"))


# --------------------------------------------------------------------------- allergies

def test_allergies_direct_by_class_and_cross_reactive():
    assert ("ALG-AMOXICILLIN", "CONTRAINDICATED") in codes(check("Tab Augmentin 625", allergies=["Penicillin"]))
    assert ("ALG-PENICILLIN-CEFIXIME", "MODERATE") in codes(check("Tab Zifi 200", allergies=["Penicillin"]))
    assert ("ALG-COTRIMOXAZOLE", "CONTRAINDICATED") in codes(check("Tab Septran DS", allergies=["Sulpha drugs"]))
    assert ("ALG-PARACETAMOL", "CONTRAINDICATED") in codes(check("Tab Dolo 650", allergies=["Paracetamol"]))
    assert ("ALG-NSAID-DICLOFENAC", "MAJOR") in codes(check("Tab Voveran 50", allergies=["Aspirin"]))
    assert check("Tab Dolo 650", allergies=["Peanuts"]).alerts == []


# --------------------------------------------------------------------------- dose range

def test_adult_daily_maximum():
    line = {"drug_text": "Tab Paracetamol 650 mg", "dose": "2 tabs", "frequency": "QID"}
    assert ("DOSE-PARACETAMOL-MAX", "MAJOR") in codes(check(line, age_years=40))
    within = {"drug_text": "Tab Dolo 650", "dose": "1 tab", "frequency": "TDS"}
    assert check(within, age_years=40).alerts == []


def test_paediatric_dose_by_weight_from_a_syrup():
    syrup = {"drug_text": "Syrup Paracetamol 250 mg/5 ml", "dose": "10 ml", "frequency": "QID"}
    result = check(syrup, age_years=5, weight_kg=20)
    alert = next(a for a in result.alerts if a.rule_code == "DOSE-PARACETAMOL-PAEDIATRIC")
    assert "2000 mg/day is 100.0 mg/kg/day" in alert.message
    assert check({**syrup, "dose": "5 ml"}, age_years=5, weight_kg=20).alerts == []
    assert any("needs the child's weight" in n for n in check(syrup, age_years=5).not_checked)


def test_older_adults():
    line = {"drug_text": "Tab Paracetamol 1 g", "dose": "1 tab", "frequency": "QID"}
    assert ("DOSE-PARACETAMOL-OLDER-ADULT", "MODERATE") in codes(check(line, age_years=78))
    assert ("GERI-DIAZEPAM", "MODERATE") in codes(check("Tab Valium 5", age_years=72))
    assert check("Tab Valium 5", age_years=40).alerts == []


def test_unclear_doses_are_reported_not_guessed():
    result = check({"drug_text": "Tab Paracetamol 650 mg", "dose": "1 tab", "frequency": "SOS"}, age_years=40)
    assert result.alerts == []
    assert any("dose of Paracetamol" in n for n in result.not_checked)


# --------------------------------------------------------------------------- renal guard

def test_cockcroft_gault():
    from app.cdss.engine import Patient

    assert round(creatinine_clearance(Patient(age_years=70, sex="M", weight_kg=60, serum_creatinine_mg_dl=2.5)), 1) == 23.3
    assert round(creatinine_clearance(Patient(age_years=70, sex="F", weight_kg=60, serum_creatinine_mg_dl=2.5)), 1) == 19.8
    assert creatinine_clearance(Patient(age_years=70, sex="M", weight_kg=60)) is None


def test_renal_rules_pick_the_most_severe_band():
    severe = check("Tab Glycomet 500", "Tab Brufen 400", age_years=70, sex="M", weight_kg=60, serum_creatinine_mg_dl=2.5)
    assert ("RENAL-METFORMIN-30", "CONTRAINDICATED") in codes(severe)
    assert ("RENAL-IBUPROFEN-30", "MAJOR") in codes(severe)
    assert severe.creatinine_clearance_ml_min == 23.3
    moderate = check("Tab Glycomet 500", age_years=60, sex="M", weight_kg=60, serum_creatinine_mg_dl=1.8)
    assert codes(moderate) == {("RENAL-METFORMIN-45", "MODERATE")}
    unknown = check("Tab Glycomet 500", age_years=60)
    assert any(n.startswith("renal:") for n in unknown.not_checked)


def test_unknown_drugs_are_listed():
    result = check("Tab Dolo 650", "Tab Unheardofmab 10 mg")
    assert result.recognised == [["paracetamol"], []]
    assert "not in the knowledge base: line 2" in result.not_checked


def test_alerts_are_ordered_most_severe_first():
    result = check("Tab Sildenafil 50 mg", "Tab Monotrate 20", "Cap Omez 20", "Tab Clopilet 75")
    assert result.alerts[0].severity == "CONTRAINDICATED"


# --------------------------------------------------------------------------- HTTP

def test_check_endpoint(client):
    response = client.post("/api/v1/ai/cdss/check", json={
        "prescriptions": [{"drug_text": "Tab Warf 5"}, {"drug_text": "Tab Brufen 400"}],
        "allergies": [], "patient": {"age_years": 60}})
    assert response.status_code == 200
    body = response.json()
    assert body["kb_reviewed"] is False
    assert body["alerts"][0]["rule_code"] == "DDI-VKA-NSAID"


def test_knowledge_and_icd10_endpoints(client):
    info = client.get("/api/v1/ai/cdss/knowledge").json()
    assert info["reviewed"] is False and info["interactions"] >= 30
    assert client.get("/api/v1/ai/icd10", params={"q": "dengue"}).json() == [
        {"code": "A90", "title": "Dengue fever [classical dengue]"}]
    assert client.post("/api/v1/ai/cdss/check", json={"prescriptions": [{"drug_text": ""}]}).status_code == 422
