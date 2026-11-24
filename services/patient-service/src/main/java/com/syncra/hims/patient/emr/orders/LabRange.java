package com.syncra.hims.patient.emr.orders;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/**
 * A lab parameter's reference interval and critical limits as they apply to one patient, and the
 * flag a numeric result gets from them: {@code C} beyond a critical limit, {@code L}/{@code H}
 * outside the interval, {@code N} within it.
 */
public record LabRange(BigDecimal low, BigDecimal high, BigDecimal criticalLow, BigDecimal criticalHigh) {

    /** Seeded intervals are for adults; children are flagged by hand from the lab's own charts. */
    public static final int ADULT_AGE = 18;

    private static final Map<String, Integer> SEVERITY = Map.of("N", 0, "A", 1, "H", 1, "L", 1, "C", 2);

    /** One parameter of a lab test (a row of {@code clinical.lab_analyte}). */
    public record Analyte(String code, String name, String unit, int decimals, BigDecimal low, BigDecimal high,
                          BigDecimal lowFemale, BigDecimal highFemale, BigDecimal criticalLow, BigDecimal criticalHigh) {

        boolean sexSpecific() {
            return lowFemale != null || highFemale != null;
        }
    }

    /**
     * The range for this patient, or empty when none applies: a child, an unknown age, or a
     * sex-specific parameter for a patient recorded as other or unknown sex.
     */
    public static Optional<LabRange> forPatient(Analyte analyte, String sex, Integer ageYears) {
        if (ageYears == null || ageYears < ADULT_AGE) {
            return Optional.empty();
        }
        BigDecimal low = analyte.low();
        BigDecimal high = analyte.high();
        if (analyte.sexSpecific()) {
            if ("F".equals(sex)) {
                low = analyte.lowFemale() != null ? analyte.lowFemale() : low;
                high = analyte.highFemale() != null ? analyte.highFemale() : high;
            } else if (!"M".equals(sex)) {
                return Optional.empty();
            }
        }
        if (low == null && high == null && analyte.criticalLow() == null && analyte.criticalHigh() == null) {
            return Optional.empty();
        }
        return Optional.of(new LabRange(low, high, analyte.criticalLow(), analyte.criticalHigh()));
    }

    public String flag(BigDecimal value) {
        if ((criticalLow != null && value.compareTo(criticalLow) < 0)
                || (criticalHigh != null && value.compareTo(criticalHigh) > 0)) {
            return "C";
        }
        if (low != null && value.compareTo(low) < 0) {
            return "L";
        }
        if (high != null && value.compareTo(high) > 0) {
            return "H";
        }
        return "N";
    }

    /** "0.7-1.3", "< 40" or "> 3.5", for the printed report. */
    public String text() {
        if (low != null && high != null) {
            return plain(low) + "-" + plain(high);
        }
        if (high != null) {
            return "< " + plain(high);
        }
        return low != null ? "> " + plain(low) : null;
    }

    /**
     * The flag to store: the computed one, unless the technician's entry is more severe (a value
     * the technician judges critical stays critical). An entered flag never downgrades a computed one.
     */
    public static String merge(String computed, String entered) {
        if (computed == null) {
            return entered;
        }
        if (entered == null) {
            return computed;
        }
        return SEVERITY.getOrDefault(entered, 0) > SEVERITY.getOrDefault(computed, 0) ? entered : computed;
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
