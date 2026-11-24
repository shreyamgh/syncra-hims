package com.syncra.hims.abdm.fhir;

import org.hl7.fhir.r4.model.CodeableConcept;

/** Routes of administration as SNOMED CT. */
final class Route {

    private Route() {
    }

    static CodeableConcept concept(String route) {
        return switch (route) {
            case "ORAL" -> FhirDocument.concept(Codes.SNOMED, "26643006", "Oral route");
            case "IV" -> FhirDocument.concept(Codes.SNOMED, "47625008", "Intravenous route");
            case "IM" -> FhirDocument.concept(Codes.SNOMED, "78421000", "Intramuscular route");
            case "SC" -> FhirDocument.concept(Codes.SNOMED, "34206005", "Subcutaneous route");
            case "TOPICAL" -> FhirDocument.concept(Codes.SNOMED, "6064005", "Topical route");
            case "INHALATION" -> FhirDocument.concept(Codes.SNOMED, "447694001", "Respiratory tract route");
            case "NASAL" -> FhirDocument.concept(Codes.SNOMED, "46713006", "Nasal route");
            case "OPHTHALMIC" -> FhirDocument.concept(Codes.SNOMED, "54485002", "Ophthalmic route");
            case "OTIC" -> FhirDocument.concept(Codes.SNOMED, "10547007", "Otic route");
            case "RECTAL" -> FhirDocument.concept(Codes.SNOMED, "37161004", "Rectal route");
            case "VAGINAL" -> FhirDocument.concept(Codes.SNOMED, "16857009", "Vaginal route");
            case "SUBLINGUAL" -> FhirDocument.concept(Codes.SNOMED, "37839007", "Sublingual route");
            case "TRANSDERMAL" -> FhirDocument.concept(Codes.SNOMED, "45890007", "Transdermal route");
            default -> new CodeableConcept().setText("Other route");
        };
    }
}
