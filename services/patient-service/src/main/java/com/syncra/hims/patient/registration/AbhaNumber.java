package com.syncra.hims.patient.registration;

/**
 * ABHA (Ayushman Bharat Health Account) number helpers. Stored as 14 digits,
 * displayed as {@code XX-XXXX-XXXX-XXXX}.
 */
public final class AbhaNumber {

    private AbhaNumber() {
    }

    /** Strips hyphens/spaces; returns null for blank input. */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String digits = raw.replaceAll("[\\s-]", "");
        if (!digits.matches("\\d{14}")) {
            throw new IllegalArgumentException("ABHA number must have 14 digits");
        }
        return digits;
    }

    public static String format(String digits) {
        if (digits == null) {
            return null;
        }
        return digits.substring(0, 2) + "-" + digits.substring(2, 6) + "-"
                + digits.substring(6, 10) + "-" + digits.substring(10);
    }
}
