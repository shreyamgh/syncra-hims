package com.syncra.hims.abdm.abha;

import java.util.regex.Pattern;

/** ABHA number formats: stored as 14 digits, shown and sent to ABDM as {@code 91-1234-5678-9012}. */
public final class AbhaNumbers {

    private static final Pattern DIGITS = Pattern.compile("^[0-9]{14}$");
    private static final Pattern ADDRESS = Pattern.compile("^[A-Za-z0-9._-]+@[a-z]+$");

    private AbhaNumbers() {
    }

    /** 14 digits with separators removed, or null when the value is not an ABHA number. */
    public static String digits(String value) {
        if (value == null) {
            return null;
        }
        String digits = value.replaceAll("[\\s-]", "");
        return DIGITS.matcher(digits).matches() ? digits : null;
    }

    public static String hyphenated(String digits) {
        return digits.substring(0, 2) + "-" + digits.substring(2, 6) + "-" + digits.substring(6, 10) + "-"
                + digits.substring(10);
    }

    /** Lower-cased ABHA address, or null when the value is not one. */
    public static String address(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim().toLowerCase();
        return ADDRESS.matcher(trimmed).matches() ? trimmed : null;
    }

    /** {@code 91-XXXX-XXXX-9012}: enough for staff to recognise, safe for screens and logs. */
    public static String masked(String digits) {
        return digits == null ? null : digits.substring(0, 2) + "-XXXX-XXXX-" + digits.substring(10);
    }
}
