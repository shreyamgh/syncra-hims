package com.syncra.hims.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.syncra.hims.billing.bill.BedDayCalculator;
import com.syncra.hims.billing.bill.BedDayCalculator.Charge;
import com.syncra.hims.billing.bill.BedDayCalculator.Stay;
import com.syncra.hims.billing.bill.BillingSupport;
import com.syncra.hims.billing.print.AmountInWords;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure billing rules: bed-day census, rounding and amounts in words. */
class BillingRulesTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static Instant ist(String localDateTime) {
        return OffsetDateTime.parse(localDateTime + "+05:30").toInstant();
    }

    private static Stay stay(String bed, String category, String from, String to) {
        return new Stay(UUID.randomUUID(), bed, category, ist(from), to == null ? null : ist(to));
    }

    @Test
    void oneDayPerMidnightSpentInBed() {
        List<Charge> charges = BedDayCalculator.calculate(
                List.of(stay("GMW-01", "GENERAL", "2026-11-10T10:00", "2026-11-13T11:00")),
                ist("2026-11-13T11:00"), IST);
        assertThat(charges).singleElement().satisfies(c -> {
            assertThat(c.days()).isEqualTo(3);
            assertThat(c.firstDay()).isEqualTo(LocalDate.of(2026, 11, 10));
            assertThat(c.lastDay()).isEqualTo(LocalDate.of(2026, 11, 12));
        });
    }

    @Test
    void transfersSplitDaysBetweenBedsAtTheirOwnWardClass() {
        List<Charge> charges = BedDayCalculator.calculate(List.of(
                        stay("PVT-01", "PRIVATE", "2026-11-12T09:00", "2026-11-13T11:00"),
                        stay("GMW-01", "GENERAL", "2026-11-10T10:00", "2026-11-12T09:00")),
                ist("2026-11-13T11:00"), IST);
        assertThat(charges).extracting(Charge::bedCode, Charge::bedCategory, Charge::days)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("GMW-01", "GENERAL", 2),
                        org.assertj.core.groups.Tuple.tuple("PVT-01", "PRIVATE", 1));
    }

    @Test
    void sameDayStayIsChargedOneDayToTheLongestBed() {
        List<Charge> charges = BedDayCalculator.calculate(List.of(
                        stay("ICU-01", "ICU", "2026-11-10T08:00", "2026-11-10T09:00"),
                        stay("GMW-01", "GENERAL", "2026-11-10T09:00", "2026-11-10T18:00")),
                ist("2026-11-10T18:00"), IST);
        assertThat(charges).extracting(Charge::days).containsExactly(0, 1);
    }

    @Test
    void openStayIsBilledUpToTheGivenEnd() {
        List<Charge> charges = BedDayCalculator.calculate(
                List.of(stay("SPV-01", "SEMI_PRIVATE", "2026-11-10T23:30", null)), ist("2026-11-12T00:15"), IST);
        assertThat(charges.get(0).days()).isEqualTo(2);
        assertThat(BedDayCalculator.calculate(List.of(), Instant.now(), IST)).isEmpty();
    }

    @Test
    void roundsToTheNearestRupee() {
        assertThat(BillingSupport.roundOff(new BigDecimal("549.50"))).isEqualByComparingTo("0.50");
        assertThat(BillingSupport.roundOff(new BigDecimal("549.49"))).isEqualByComparingTo("-0.49");
        assertThat(BillingSupport.roundOff(new BigDecimal("350.00"))).isEqualByComparingTo("0");
    }

    @Test
    void amountsInWordsUseLakhAndCrore() {
        assertThat(AmountInWords.rupees(new BigDecimal("350"))).isEqualTo("Rupees Three Hundred Fifty Only");
        assertThat(AmountInWords.rupees(new BigDecimal("12345678.50"))).isEqualTo(
                "Rupees One Crore Twenty Three Lakh Forty Five Thousand Six Hundred Seventy Eight and Fifty Paise Only");
        assertThat(AmountInWords.rupees(new BigDecimal("100000"))).isEqualTo("Rupees One Lakh Only");
        assertThat(AmountInWords.rupees(BigDecimal.ZERO)).isEqualTo("Rupees Zero Only");
        assertThat(AmountInWords.rupees(new BigDecimal("1011.05"))).isEqualTo("Rupees One Thousand Eleven and Five Paise Only");
    }

    @Test
    void indianDigitGrouping() {
        assertThat(AmountInWords.grouped(new BigDecimal("1234567.5"))).isEqualTo("12,34,567.50");
        assertThat(AmountInWords.grouped(new BigDecimal("999"))).isEqualTo("999.00");
        assertThat(AmountInWords.grouped(new BigDecimal("1000"))).isEqualTo("1,000.00");
        assertThat(AmountInWords.grouped(new BigDecimal("100000"))).isEqualTo("1,00,000.00");
        assertThat(AmountInWords.grouped(new BigDecimal("-6200"))).isEqualTo("-6,200.00");
    }
}
