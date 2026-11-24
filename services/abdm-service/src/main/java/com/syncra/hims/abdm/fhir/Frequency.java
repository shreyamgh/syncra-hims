package com.syncra.hims.abdm.fhir;

import java.math.BigDecimal;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Dosage;
import org.hl7.fhir.r4.model.Timing;

/** Indian prescription frequency abbreviations (OD, BD, TDS, ...) as FHIR Timing. */
final class Frequency {

    private Frequency() {
    }

    static String label(String code) {
        return switch (code) {
            case "OD" -> "once a day";
            case "BD" -> "twice a day";
            case "TDS" -> "three times a day";
            case "QID" -> "four times a day";
            case "HS" -> "at bedtime";
            case "SOS" -> "when required";
            case "STAT" -> "immediately, once";
            case "Q4H" -> "every 4 hours";
            case "Q6H" -> "every 6 hours";
            case "Q8H" -> "every 8 hours";
            case "WEEKLY" -> "once a week";
            case "ALT_DAYS" -> "on alternate days";
            default -> "as directed";
        };
    }

    static void apply(String code, Timing timing, Dosage dosage) {
        Timing.TimingRepeatComponent repeat = timing.getRepeat();
        switch (code) {
            case "OD" -> perPeriod(timing, 1, 1, Timing.UnitsOfTime.D, "QD", "daily");
            case "BD" -> perPeriod(timing, 2, 1, Timing.UnitsOfTime.D, "BID", "BID");
            case "TDS" -> perPeriod(timing, 3, 1, Timing.UnitsOfTime.D, "TID", "TID");
            case "QID" -> perPeriod(timing, 4, 1, Timing.UnitsOfTime.D, "QID", "QID");
            case "HS" -> {
                perPeriod(timing, 1, 1, Timing.UnitsOfTime.D, null, null);
                repeat.addWhen(Timing.EventTiming.HS);
            }
            case "Q4H" -> perPeriod(timing, 1, 4, Timing.UnitsOfTime.H, "Q4H", "Q4H");
            case "Q6H" -> perPeriod(timing, 1, 6, Timing.UnitsOfTime.H, "Q6H", "Q6H");
            case "Q8H" -> perPeriod(timing, 1, 8, Timing.UnitsOfTime.H, null, null);
            case "WEEKLY" -> perPeriod(timing, 1, 1, Timing.UnitsOfTime.WK, "WK", "weekly");
            case "ALT_DAYS" -> perPeriod(timing, 1, 2, Timing.UnitsOfTime.D, "QOD", "QOD");
            case "STAT" -> repeat.setCount(1);
            case "SOS" -> dosage.setAsNeeded(new BooleanType(true));
            default -> { }
        }
    }

    private static void perPeriod(Timing timing, int frequency, int period, Timing.UnitsOfTime unit, String code,
                                  String display) {
        timing.getRepeat().setFrequency(frequency).setPeriod(BigDecimal.valueOf(period)).setPeriodUnit(unit);
        if (code != null) {
            timing.setCode(new CodeableConcept().addCoding(new Coding(Codes.TIMING_ABBREVIATION, code, display)));
        }
    }
}
