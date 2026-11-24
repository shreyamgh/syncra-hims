package com.syncra.hims.patient.emr.orders;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class LabRangeTest {

    static BigDecimal n(String value) {
        return new BigDecimal(value);
    }

    static final LabRange.Analyte CREAT = new LabRange.Analyte("CREAT", "Serum creatinine", "mg/dL", 2,
            n("0.7"), n("1.3"), n("0.6"), n("1.1"), null, null);
    static final LabRange.Analyte POTASSIUM = new LabRange.Analyte("POTASSIUM", "Potassium", "mmol/L", 1,
            n("3.5"), n("5.1"), null, null, n("2.8"), n("6.2"));

    @Test
    void choosesTheIntervalBySexForAdultsOnly() {
        assertThat(LabRange.forPatient(CREAT, "M", 40)).get().extracting(LabRange::text).isEqualTo("0.7-1.3");
        assertThat(LabRange.forPatient(CREAT, "F", 40)).get().extracting(LabRange::text).isEqualTo("0.6-1.1");
        // A sex-specific parameter has no interval for other or unknown sex.
        assertThat(LabRange.forPatient(CREAT, "U", 40)).isEmpty();
        assertThat(LabRange.forPatient(POTASSIUM, "U", 40)).isPresent();
        // Children and unknown ages are flagged by hand.
        assertThat(LabRange.forPatient(POTASSIUM, "M", 17)).isEmpty();
        assertThat(LabRange.forPatient(POTASSIUM, "M", null)).isEmpty();
        assertThat(LabRange.forPatient(POTASSIUM, "M", 18)).isPresent();
    }

    @Test
    void flagsOutsideTheIntervalAndBeyondCriticalLimitsWithInclusiveBounds() {
        LabRange k = LabRange.forPatient(POTASSIUM, "F", 60).orElseThrow();
        assertThat(k.flag(n("3.5"))).isEqualTo("N");
        assertThat(k.flag(n("5.1"))).isEqualTo("N");
        assertThat(k.flag(n("5.2"))).isEqualTo("H");
        assertThat(k.flag(n("3.4"))).isEqualTo("L");
        assertThat(k.flag(n("6.2"))).isEqualTo("H");
        assertThat(k.flag(n("6.3"))).isEqualTo("C");
        assertThat(k.flag(n("2.7"))).isEqualTo("C");
    }

    @Test
    void anEnteredFlagCanRaiseButNeverLowerTheComputedOne() {
        assertThat(LabRange.merge("C", "N")).isEqualTo("C");
        assertThat(LabRange.merge("H", null)).isEqualTo("H");
        assertThat(LabRange.merge("H", "C")).isEqualTo("C");
        assertThat(LabRange.merge("N", "A")).isEqualTo("A");
        assertThat(LabRange.merge("H", "L")).isEqualTo("H");
        assertThat(LabRange.merge(null, "L")).isEqualTo("L");
    }

    @Test
    void writesOneSidedRangesReadably() {
        assertThat(new LabRange(null, n("40.0"), null, null).text()).isEqualTo("< 40");
        assertThat(new LabRange(n("3.50"), null, null, null).text()).isEqualTo("> 3.5");
        assertThat(new LabRange(null, null, n("2.8"), null).text()).isNull();
    }
}
