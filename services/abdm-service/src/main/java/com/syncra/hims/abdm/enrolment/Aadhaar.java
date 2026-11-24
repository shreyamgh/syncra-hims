package com.syncra.hims.abdm.enrolment;

/**
 * Aadhaar number checks done before anything leaves the hospital: 12 digits, not starting with
 * 0 or 1, with a valid Verhoeff check digit (catches every single-digit typo and adjacent swap).
 * The number itself is only ever held in memory, encrypted for ABHA, and shown masked.
 */
public final class Aadhaar {

    private static final int[][] MULTIPLY = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, {1, 2, 3, 4, 0, 6, 7, 8, 9, 5}, {2, 3, 4, 0, 1, 7, 8, 9, 5, 6},
            {3, 4, 0, 1, 2, 8, 9, 5, 6, 7}, {4, 0, 1, 2, 3, 9, 5, 6, 7, 8}, {5, 9, 8, 7, 6, 0, 4, 3, 2, 1},
            {6, 5, 9, 8, 7, 1, 0, 4, 3, 2}, {7, 6, 5, 9, 8, 2, 1, 0, 4, 3}, {8, 7, 6, 5, 9, 3, 2, 1, 0, 4},
            {9, 8, 7, 6, 5, 4, 3, 2, 1, 0}};
    private static final int[][] PERMUTE = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, {1, 5, 7, 6, 2, 8, 3, 0, 9, 4}, {5, 8, 0, 3, 7, 9, 6, 1, 4, 2},
            {8, 9, 1, 6, 0, 4, 3, 5, 2, 7}, {9, 4, 5, 3, 1, 2, 6, 8, 7, 0}, {4, 2, 8, 6, 5, 7, 3, 9, 0, 1},
            {2, 7, 9, 3, 8, 0, 6, 4, 1, 5}, {7, 0, 4, 6, 9, 1, 3, 2, 5, 8}};

    private Aadhaar() {
    }

    /** The 12 digits with spaces and hyphens removed, or null when it cannot be an Aadhaar number. */
    public static String normalise(String value) {
        if (value == null) {
            return null;
        }
        String digits = value.replaceAll("[\\s-]", "");
        return digits.matches("^[2-9][0-9]{11}$") && verhoeffValid(digits) ? digits : null;
    }

    /** {@code XXXX-XXXX-1234}. */
    public static String masked(String digits) {
        return "XXXX-XXXX-" + digits.substring(digits.length() - 4);
    }

    static boolean verhoeffValid(String digits) {
        int check = 0;
        for (int i = 0; i < digits.length(); i++) {
            int digit = digits.charAt(digits.length() - 1 - i) - '0';
            check = MULTIPLY[check][PERMUTE[i % 8][digit]];
        }
        return check == 0;
    }

    /** The Verhoeff check digit for {@code digits} (for building test numbers). */
    static int verhoeffDigit(String digits) {
        int check = 0;
        for (int i = 0; i < digits.length(); i++) {
            int digit = digits.charAt(digits.length() - 1 - i) - '0';
            check = MULTIPLY[check][PERMUTE[(i + 1) % 8][digit]];
        }
        int[] inverse = {0, 4, 3, 2, 1, 5, 6, 7, 8, 9};
        return inverse[check];
    }
}
