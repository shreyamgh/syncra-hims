package com.syncra.hims.billing.print;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Rupee amounts in words using the Indian numbering system, as printed on bills:
 * 1,23,45,678.50 → "Rupees One Crore Twenty Three Lakh Forty Five Thousand Six
 * Hundred Seventy Eight and Fifty Paise Only".
 */
public final class AmountInWords {

    private static final String[] ONES = {"", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine",
            "Ten", "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen",
            "Nineteen"};
    private static final String[] TENS = {"", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty",
            "Ninety"};

    private AmountInWords() {
    }

    public static String rupees(BigDecimal amount) {
        BigDecimal value = amount.abs().setScale(2, RoundingMode.HALF_UP);
        long rupees = value.longValue();
        int paise = value.subtract(BigDecimal.valueOf(rupees)).movePointRight(2).intValue();
        StringBuilder words = new StringBuilder(amount.signum() < 0 ? "Minus Rupees " : "Rupees ");
        words.append(rupees == 0 ? "Zero" : indian(rupees));
        if (paise > 0) {
            words.append(" and ").append(belowHundred(paise)).append(" Paise");
        }
        return words.append(" Only").toString();
    }

    /** Indian digit grouping with two decimals: 1234567.5 → "12,34,567.50". */
    public static String grouped(BigDecimal amount) {
        BigDecimal value = amount.setScale(2, RoundingMode.HALF_UP);
        String plain = value.abs().toPlainString();
        String whole = plain.substring(0, plain.indexOf('.'));
        String fraction = plain.substring(plain.indexOf('.'));
        StringBuilder out = new StringBuilder();
        int length = whole.length();
        for (int i = 0; i < length; i++) {
            out.append(whole.charAt(i));
            int remaining = length - i - 1;
            if (remaining > 0 && (remaining == 3 || (remaining > 3 && (remaining - 3) % 2 == 0))) {
                out.append(',');
            }
        }
        return (value.signum() < 0 ? "-" : "") + out + fraction;
    }

    static String indian(long n) {
        StringBuilder out = new StringBuilder();
        long crore = n / 10_000_000;
        int lakh = (int) (n / 100_000 % 100);
        int thousand = (int) (n / 1_000 % 100);
        int hundred = (int) (n / 100 % 10);
        int rest = (int) (n % 100);
        if (crore > 0) {
            append(out, indian(crore) + " Crore");
        }
        if (lakh > 0) {
            append(out, belowHundred(lakh) + " Lakh");
        }
        if (thousand > 0) {
            append(out, belowHundred(thousand) + " Thousand");
        }
        if (hundred > 0) {
            append(out, ONES[hundred] + " Hundred");
        }
        if (rest > 0) {
            append(out, belowHundred(rest));
        }
        return out.toString();
    }

    private static String belowHundred(int n) {
        return n < 20 ? ONES[n] : TENS[n / 10] + (n % 10 > 0 ? " " + ONES[n % 10] : "");
    }

    private static void append(StringBuilder out, String part) {
        if (!out.isEmpty()) {
            out.append(' ');
        }
        out.append(part);
    }
}
