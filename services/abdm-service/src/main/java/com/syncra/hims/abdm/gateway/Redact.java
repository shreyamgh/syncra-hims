package com.syncra.hims.abdm.gateway;

import java.util.regex.Pattern;

/**
 * Masks identifiers in text that came from ABDM before it is stored or logged: error
 * messages can quote ABHA numbers, addresses or mobile numbers back at the caller.
 */
public final class Redact {

    private static final Pattern ADDRESS = Pattern.compile("[\\w.+-]+@[\\w.-]+");
    private static final Pattern DIGITS = Pattern.compile("\\d[\\d -]{2,}\\d");
    private static final int MAX_LENGTH = 300;

    private Redact() {
    }

    public static String text(String value) {
        if (value == null) {
            return null;
        }
        String masked = DIGITS.matcher(ADDRESS.matcher(value).replaceAll("***@***")).replaceAll("****");
        return masked.length() <= MAX_LENGTH ? masked : masked.substring(0, MAX_LENGTH);
    }
}
