package com.syncra.hims.abdm.linking;

import static org.assertj.core.api.Assertions.assertThat;

import com.syncra.hims.abdm.linking.PatientMatcher.Ambiguous;
import com.syncra.hims.abdm.linking.PatientMatcher.Candidate;
import com.syncra.hims.abdm.linking.PatientMatcher.Matched;
import com.syncra.hims.abdm.linking.PatientMatcher.NotFound;
import com.syncra.hims.abdm.linking.PatientMatcher.Query;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PatientMatcherTest {

    static final Query SUNIL = new Query("sunil.p@sbx", null, "9822012345", null, "Sunil Patil", "M", 1982);

    static Candidate candidate(String uhid, String gender, int year, boolean estimated, String mobile, double name) {
        return new Candidate(UUID.randomUUID(), uhid, gender, LocalDate.of(year, 1, 1), estimated, mobile, null, null,
                false, name);
    }

    @Test
    void verifiedMobileGenderYearAndNameTogetherMatch() {
        Candidate sunil = candidate("SYNC-20260101-0001", "M", 1982, false, "9822012345", 0.8);
        assertThat(PatientMatcher.match(SUNIL, List.of(sunil)))
                .isEqualTo(new Matched(sunil, List.of("MOBILE")));
    }

    @Test
    void anyDisagreementMeansNotFound() {
        assertThat(PatientMatcher.match(SUNIL, List.of(candidate("U1", "F", 1982, false, "9822012345", 0.8))))
                .isInstanceOf(NotFound.class);
        assertThat(PatientMatcher.match(SUNIL, List.of(candidate("U1", "M", 1983, false, "9822012345", 0.8))))
                .isInstanceOf(NotFound.class);
        assertThat(PatientMatcher.match(SUNIL, List.of(candidate("U1", "M", 1982, false, "9822099999", 0.8))))
                .isInstanceOf(NotFound.class);
        assertThat(PatientMatcher.match(SUNIL, List.of(candidate("U1", "M", 1982, false, "9822012345", 0.2))))
                .isInstanceOf(NotFound.class);
        Query unverifiedOnly = new Query("sunil.p@sbx", null, null, "U1", "Sunil Patil", "M", 1982);
        assertThat(PatientMatcher.match(unverifiedOnly, List.of(candidate("U1", "M", 1982, false, "9822012345", 0.9))))
                .as("a typed UHID alone is not evidence").isInstanceOf(NotFound.class);
    }

    @Test
    void estimatedDatesOfBirthGetTwoYearsLeeway() {
        assertThat(PatientMatcher.match(SUNIL, List.of(candidate("U1", "M", 1980, true, "9822012345", 0.8))))
                .isInstanceOf(Matched.class);
        assertThat(PatientMatcher.match(SUNIL, List.of(candidate("U1", "M", 1979, true, "9822012345", 0.8))))
                .isInstanceOf(NotFound.class);
    }

    @Test
    void familyMembersSharingAMobileNeedTheUhidToTellApart() {
        Candidate one = candidate("SYNC-20260101-0001", "M", 1982, false, "9822012345", 0.9);
        Candidate two = candidate("SYNC-20260101-0002", "M", 1982, false, "9822012345", 0.9);
        assertThat(PatientMatcher.match(SUNIL, List.of(one, two))).isEqualTo(new Ambiguous(2));
        Query withUhid = new Query("sunil.p@sbx", null, "9822012345", "SYNC-20260101-0002", "Sunil Patil", "M", 1982);
        assertThat(PatientMatcher.match(withUhid, List.of(one, two))).isEqualTo(new Matched(two, List.of("MOBILE", "MR")));
    }

    @Test
    void aVerifiedAbhaDecidesBothWays() {
        Candidate own = new Candidate(UUID.randomUUID(), "U1", "M", null, false, null, "91111122223333", "sunil.p@sbx",
                true, 0.0);
        assertThat(PatientMatcher.match(SUNIL, List.of(own))).isEqualTo(new Matched(own, List.of("ABHA_ADDRESS")));

        Candidate someoneElsesAbha = new Candidate(UUID.randomUUID(), "U2", "M", LocalDate.of(1982, 1, 1), false,
                "9822012345", "91999988887777", "other@sbx", true, 0.9);
        assertThat(PatientMatcher.match(SUNIL, List.of(someoneElsesAbha)))
                .as("a patient with another verified ABHA is never offered").isInstanceOf(NotFound.class);
    }
}
