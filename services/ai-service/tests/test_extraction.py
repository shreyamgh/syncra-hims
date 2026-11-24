import pytest

from app.clinical.extraction import extract

OPD_NOTE = (
    "Patient complains of fever for 3 days and dry cough since 1 week. "
    "BP 130/80, pulse 96, temperature 101 F, SpO2 97%, RR 18. Weight 68 kg. "
    "Tab Paracetamol 650 mg 1-0-1 after food for 5 days. "
    "Cap Amoxicillin 500 mg TDS for 7 days. Syrup Ascoril 10 ml TDS."
)


def _rx(result, name):
    return next(line for line in result.prescriptions if line.drug_name == name)


def test_typical_opd_dictation_fills_all_three_sections():
    result = extract(OPD_NOTE)

    v = result.vitals
    assert (v.bp_systolic, v.bp_diastolic) == (130, 80)
    assert v.pulse_bpm == 96
    assert v.temperature_c == 38.3  # 101 F
    assert v.spo2_percent == 97
    assert v.respiratory_rate == 18
    assert v.weight_kg == 68

    assert [(c.description, c.duration_value, c.duration_unit) for c in result.complaints] == [
        ("Fever", 3, "DAYS"),
        ("Dry cough", 1, "WEEKS"),
    ]

    pcm = _rx(result, "Paracetamol")
    assert pcm.drug_text == "Tab Paracetamol 650 mg"
    assert (pcm.dose, pcm.frequency, pcm.timing, pcm.duration_days, pcm.route) == (
        "1 tab",
        "BD",
        "AFTER_FOOD",
        5,
        "ORAL",
    )
    amox = _rx(result, "Amoxicillin")
    assert (amox.dosage_form, amox.strength, amox.frequency, amox.duration_days) == ("CAPSULE", "500 mg", "TDS", 7)
    syrup = _rx(result, "Ascoril")
    assert (syrup.drug_text, syrup.dose, syrup.frequency, syrup.strength) == ("Syp Ascoril", "10 ml", "TDS", None)
    assert result.warnings == []


@pytest.mark.parametrize(
    ("phrase", "expected"),
    [
        ("BP is 150 by 90", (150, 90)),
        ("blood pressure 110 over 70 mm of Hg", (110, 70)),
        ("bp 120/80", (120, 80)),
    ],
)
def test_blood_pressure_phrasings(phrase, expected):
    v = extract(phrase).vitals
    assert (v.bp_systolic, v.bp_diastolic) == expected


def test_temperature_units_and_glucose():
    assert extract("temp 38.5").vitals.temperature_c == 38.5
    assert extract("temperature is 99.5 degrees").vitals.temperature_c == 37.5  # > 45 means Fahrenheit
    assert extract("random blood sugar 210 mg/dl").vitals.blood_glucose_mg_dl == 210
    assert extract("RBS 140").vitals.blood_glucose_mg_dl == 140
    assert extract("saturating 94 percent on room air").vitals.spo2_percent == 94


def test_last_mention_wins_and_implausible_values_are_dropped():
    result = extract("pulse 88, sorry pulse 92. BP 80/120. heart rate 400")
    assert result.vitals.pulse_bpm is None  # the final mention (400) is out of range
    assert result.vitals.bp_systolic is None
    assert any("blood pressure" in w for w in result.warnings)
    assert any("pulse" in w for w in result.warnings)
    assert extract("pulse 88, sorry pulse 92").vitals.pulse_bpm == 92


def test_complaints_with_and_without_durations():
    result = extract("c/o headache, vomiting 2 days and loose motions since two days. On examination pallor.")
    assert [(c.description, c.duration_value, c.duration_unit) for c in result.complaints] == [
        ("Headache", None, None),
        ("Vomiting", 2, "DAYS"),
        ("Loose motions", 2, "DAYS"),
    ]
    assert extract("patient is having pain abdomen for 6 hours").complaints[0].duration_unit == "HOURS"


@pytest.mark.parametrize(
    ("dictation", "name", "expected"),
    [
        (
            "Tab Pan 40 once daily before breakfast for 2 weeks",
            "Pan 40",
            {"frequency": "OD", "timing": "BEFORE_FOOD", "duration_days": 14, "route": "ORAL"},
        ),
        (
            "Tab Cetirizine 10 mg at bedtime x 5 days",
            "Cetirizine",
            {"frequency": "HS", "timing": "BEDTIME", "duration_days": 5, "strength": "10 mg"},
        ),
        (
            "Inj Ceftriaxone 1 g IV BD",
            "Ceftriaxone",
            {"route": "IV", "frequency": "BD", "strength": "1 g", "dosage_form": "INJECTION"},
        ),
        (
            "eye drops Moxifloxacin 1 drop QID",
            "Moxifloxacin",
            {"route": "OPHTHALMIC", "frequency": "QID", "dose": "1 drop"},
        ),
        ("Tab Dolo 650 SOS for fever", "Dolo 650", {"frequency": "SOS", "strength": None}),
        (
            "Tab Metformin 500 mg 1-0-0 after breakfast",
            "Metformin",
            {"frequency": "OD", "dose": "1 tab", "timing": "AFTER_FOOD"},
        ),
        ("Tab Pan D 0-0-1", "Pan D", {"frequency": "HS"}),
        (
            "Inhaler Budecort 2 puffs twice daily",
            "Budecort",
            {"frequency": "BD", "dose": "2 puffs", "route": "INHALATION"},
        ),
        (
            "Syrup Amoxyclav 228 mg/5 ml 5 ml BD for 5 days",
            "Amoxyclav",
            {"strength": "228 mg/5 ml", "dose": "5 ml", "duration_days": 5},
        ),
    ],
)
def test_prescription_phrasings(dictation, name, expected):
    line = _rx(extract(dictation), name)
    for field, value in expected.items():
        assert getattr(line, field) == value, field


def test_numeric_frequencies_are_not_mistaken_for_durations():
    # Verbatim Whisper output for a synthesised Indian-English dictation.
    line = _rx(extract("Capsule Amoxicillin 500 mg 3 times a day for 7 days."), "Amoxicillin")
    assert (line.frequency, line.duration_days) == ("TDS", 7)
    line = _rx(extract("Tab Vitamin C 2 times a day"), "Vitamin C")
    assert (line.frequency, line.duration_days) == ("BD", None)
    assert _rx(extract("Tab Folic Acid 5 mg once a day for a month"), "Folic Acid").duration_days == 30
    assert extract("c/o cough for a week").complaints[0].duration_unit == "WEEKS"


def test_multiple_drugs_in_one_sentence_are_split():
    result = extract("Start tab Azithromycin 500 mg OD for 3 days and tab Montelukast 10 mg HS")
    assert [line.drug_name for line in result.prescriptions] == ["Azithromycin", "Montelukast"]
    assert _rx(result, "Montelukast").frequency == "HS"


def test_empty_and_unrelated_text():
    assert extract("").model_dump() == extract("   ").model_dump()
    result = extract("Discussed diet and exercise. Review after two weeks.")
    assert result.prescriptions == [] and result.complaints == []
    assert result.vitals.model_dump(exclude_none=True) == {}


def test_diagnoses_are_extracted_with_suggested_icd10_codes():
    result = extract(
        "Fever for 3 days. Diagnosis: acute upper respiratory tract infection. "
        "Tab Paracetamol 650 mg TDS for 5 days."
    )
    assert [(d.description, d.icd10_code, d.diagnosis_type) for d in result.diagnoses] == [
        ("Acute upper respiratory tract infection", "J06.9", "PROVISIONAL")]


def test_several_diagnoses_and_final_diagnosis():
    result = extract("Impression viral fever and dehydration. Final diagnosis dengue fever; advised fluids.")
    assert [(d.description, d.icd10_code, d.diagnosis_type) for d in result.diagnoses] == [
        ("Viral fever", "B34.9", "PROVISIONAL"),
        ("Dehydration", None, "PROVISIONAL"),
        ("Dengue fever", "A90", "FINAL"),
    ]
