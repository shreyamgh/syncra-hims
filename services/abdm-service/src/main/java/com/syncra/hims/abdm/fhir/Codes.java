package com.syncra.hims.abdm.fhir;

/** Code systems and identifier namespaces used in the records. */
final class Codes {

    static final String SNOMED = "http://snomed.info/sct";
    static final String LOINC = "http://loinc.org";
    static final String UCUM = "http://unitsofmeasure.org";
    static final String ICD10 = "http://hl7.org/fhir/sid/icd-10";
    static final String V2_IDENTIFIER_TYPE = "http://terminology.hl7.org/CodeSystem/v2-0203";
    static final String V2_DIAGNOSTIC_SERVICE = "http://terminology.hl7.org/CodeSystem/v2-0074";
    static final String ACT_CODE = "http://terminology.hl7.org/CodeSystem/v3-ActCode";
    static final String CONFIDENTIALITY = "http://terminology.hl7.org/CodeSystem/v3-Confidentiality";
    static final String TIMING_ABBREVIATION = "http://terminology.hl7.org/CodeSystem/v3-GTSAbbreviation";
    static final String OBSERVATION_CATEGORY = "http://terminology.hl7.org/CodeSystem/observation-category";
    static final String CONDITION_CATEGORY = "http://terminology.hl7.org/CodeSystem/condition-category";
    static final String CONDITION_CLINICAL = "http://terminology.hl7.org/CodeSystem/condition-clinical";
    static final String CONDITION_VERIFICATION = "http://terminology.hl7.org/CodeSystem/condition-ver-status";
    static final String ALLERGY_CLINICAL = "http://terminology.hl7.org/CodeSystem/allergyintolerance-clinical";
    static final String ALLERGY_VERIFICATION = "http://terminology.hl7.org/CodeSystem/allergyintolerance-verification";
    static final String DIAGNOSIS_ROLE = "http://terminology.hl7.org/CodeSystem/diagnosis-role";
    static final String DISCHARGE_DISPOSITION = "http://terminology.hl7.org/CodeSystem/discharge-disposition";
    static final String DICOM = "http://dicom.nema.org/resources/ontology/DCM";
    static final String V2_PERFORMER_FUNCTION = "http://terminology.hl7.org/CodeSystem/v2-0443";
    static final String RECOMMENDATION_STATUS = "http://terminology.hl7.org/CodeSystem/immunization-recommendation-status";
    static final String OBSERVATION_INTERPRETATION = "http://terminology.hl7.org/CodeSystem/v3-ObservationInterpretation";
    /** NRCES billing and price-component code systems (InvoiceRecord). */
    static final String BILLING = "https://nrces.in/ndhm/fhir/r4/CodeSystem/ndhm-billing-codes";
    static final String PRICE_COMPONENT = "https://nrces.in/ndhm/fhir/r4/CodeSystem/ndhm-price-components";

    /** NRCES (ABDM's FHIR implementation guide) profiles. */
    static final String NRCES = "https://nrces.in/ndhm/fhir/r4/StructureDefinition/";
    /** ABDM registries, as identifier systems. */
    static final String ABHA = "https://healthid.ndhm.gov.in";
    static final String HPR = "https://doctor.ndhm.gov.in";
    static final String HFR = "https://facility.ndhm.gov.in";

    private Codes() {
    }
}
