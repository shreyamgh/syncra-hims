package com.syncra.hims.abdm.fhir;

/** DICOM modality codes used by the hospital's service catalogue, with their DCM display names. */
final class Modality {

    private Modality() {
    }

    static String display(String code) {
        return switch (code) {
            case "CR" -> "Computed Radiography";
            case "DX" -> "Digital Radiography";
            case "CT" -> "Computed Tomography";
            case "MR" -> "Magnetic Resonance";
            case "US" -> "Ultrasound";
            case "MG" -> "Mammography";
            case "XA" -> "X-Ray Angiography";
            case "RF" -> "Radio Fluoroscopy";
            case "NM" -> "Nuclear Medicine";
            case "PT" -> "Positron emission tomography";
            default -> "Other";
        };
    }
}
