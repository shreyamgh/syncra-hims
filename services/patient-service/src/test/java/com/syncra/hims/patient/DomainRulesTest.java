package com.syncra.hims.patient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syncra.hims.common.web.ApiException;
import com.syncra.hims.patient.ipd.BedStatus;
import com.syncra.hims.patient.opd.QueueStatus;
import com.syncra.hims.patient.opd.VisitPriority;
import com.syncra.hims.patient.opd.VisitType;
import com.syncra.hims.patient.registration.AbhaNumber;
import com.syncra.hims.patient.registration.Uhid;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Pure domain rules (no Spring, no database). */
class DomainRulesTest {

    @Test
    void uhidExposesRegistrationDateAndSerial() {
        Uhid uhid = Uhid.parse(" sync-20261120-0042 ");
        assertThat(uhid.value()).isEqualTo("SYNC-20261120-0042");
        assertThat(uhid.registrationDate()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(uhid.dailySerial()).isEqualTo(42);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SYNC-20261120-0000", "SYNC-20261320-0001", "SYNC-2026112-0001", "UHID-20261120-0001", ""})
    void rejectsMalformedUhids(String raw) {
        assertThat(Uhid.looksLike(raw) && isValid(raw)).isFalse();
        assertThatThrownBy(() -> Uhid.parse(raw)).isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("INVALID_UHID");
    }

    @Test
    void abhaNumbersNormaliseAndFormat() {
        assertThat(AbhaNumber.normalize("91-1234-5678-9012")).isEqualTo("91123456789012");
        assertThat(AbhaNumber.format("91123456789012")).isEqualTo("91-1234-5678-9012");
        assertThat(AbhaNumber.normalize("  ")).isNull();
        assertThatThrownBy(() -> AbhaNumber.normalize("12-3456")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void queueTransitionsFollowTheLifecycle() {
        assertThat(QueueStatus.WAITING.canMoveTo(QueueStatus.CALLED)).isTrue();
        assertThat(QueueStatus.CALLED.canMoveTo(QueueStatus.IN_CONSULTATION)).isTrue();
        assertThat(QueueStatus.IN_CONSULTATION.canMoveTo(QueueStatus.ON_HOLD)).isTrue();
        assertThat(QueueStatus.ON_HOLD.canMoveTo(QueueStatus.WAITING)).isTrue();
        assertThat(QueueStatus.WAITING.canMoveTo(QueueStatus.COMPLETED)).isFalse();
        assertThat(QueueStatus.IN_CONSULTATION.canMoveTo(QueueStatus.NO_SHOW)).isFalse();
        for (QueueStatus terminal : new QueueStatus[] {QueueStatus.COMPLETED, QueueStatus.NO_SHOW, QueueStatus.CANCELLED}) {
            assertThat(terminal.isLive()).isFalse();
            for (QueueStatus any : QueueStatus.values()) {
                assertThat(terminal.canMoveTo(any)).isFalse();
            }
        }
    }

    @Test
    void priorityDefaultsFromVisitTypeAndAge() {
        assertThat(VisitPriority.defaultFor(VisitType.EMERGENCY, 30)).isEqualTo(VisitPriority.EMERGENCY);
        assertThat(VisitPriority.defaultFor(VisitType.NEW, 60)).isEqualTo(VisitPriority.SENIOR_CITIZEN);
        assertThat(VisitPriority.defaultFor(VisitType.NEW, 4)).isEqualTo(VisitPriority.CHILD_UNDER_5);
        assertThat(VisitPriority.defaultFor(VisitType.NEW, 35)).isEqualTo(VisitPriority.NORMAL);
        assertThat(VisitPriority.defaultFor(VisitType.FOLLOW_UP, null)).isEqualTo(VisitPriority.NORMAL);
        assertThat(VisitPriority.EMERGENCY.rank()).isLessThan(VisitPriority.SENIOR_CITIZEN.rank());
        assertThat(VisitPriority.SENIOR_CITIZEN.rank()).isLessThan(VisitPriority.NORMAL.rank());
    }

    @Test
    void occupiedAndCleaningAreNeverSetByHand() {
        assertThat(BedStatus.OCCUPIED.isManuallySettable()).isFalse();
        assertThat(BedStatus.CLEANING.isManuallySettable()).isFalse();
        assertThat(BedStatus.AVAILABLE.manuallyReachableFrom()).contains(BedStatus.CLEANING)
                .doesNotContain(BedStatus.OCCUPIED);
    }

    private static boolean isValid(String raw) {
        try {
            Uhid.parse(raw);
            return true;
        } catch (ApiException e) {
            return false;
        }
    }
}
