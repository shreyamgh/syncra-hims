package com.syncra.hims.abdm.linking;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Decides which patient, if any, a PHR-app user is when they search this hospital for records
 * ("discovery"). The rules lean towards "not found": records are only offered when exactly one
 * patient matches, and linking still needs an OTP sent to the patient's registered mobile.
 *
 * <ol>
 *   <li>A patient whose <em>verified</em> ABHA number or address is the searcher's matches outright.</li>
 *   <li>Otherwise the ABDM-verified mobile must equal the registered mobile, and gender, year of
 *       birth (±{@value #ESTIMATED_DOB_TOLERANCE} years when the date of birth is an estimate) and
 *       name (trigram similarity ≥ {@value #NAME_SIMILARITY}) must agree. Patients who already hold
 *       a different verified ABHA are never offered.</li>
 *   <li>Several candidates: the UHID the user typed (unverified) may single one out; if it does
 *       not, the search is ambiguous.</li>
 * </ol>
 */
public final class PatientMatcher {

    public static final double NAME_SIMILARITY = 0.4;
    public static final int ESTIMATED_DOB_TOLERANCE = 2;

    /** What ABDM tells us about the searcher. Verified identifiers are vouched for by ABDM. */
    public record Query(String abhaAddress, String verifiedAbhaNumber, String verifiedMobile, String uhid,
                        String name, String gender, Integer yearOfBirth) {
    }

    /** A patient record that shares an identifier with the query; {@code nameScore} from pg_trgm. */
    public record Candidate(UUID patientId, String uhid, String gender, LocalDate dateOfBirth, boolean dobEstimated,
                            String mobile, String abhaNumber, String abhaAddress, boolean abhaVerified,
                            double nameScore) {
    }

    public sealed interface Result permits Matched, NotFound, Ambiguous {
    }

    /** @param matchedBy identifier types reported back to ABDM ({@code MOBILE}, {@code MR}, ...) */
    public record Matched(Candidate patient, List<String> matchedBy) implements Result {
    }

    public record NotFound() implements Result {
    }

    public record Ambiguous(int candidates) implements Result {
    }

    private PatientMatcher() {
    }

    public static Result match(Query query, List<Candidate> candidates) {
        List<Candidate> byAbha = candidates.stream().filter(c -> c.abhaVerified() && sameAbha(c, query)).toList();
        if (byAbha.size() == 1) {
            Candidate c = byAbha.get(0);
            boolean number = query.verifiedAbhaNumber() != null && query.verifiedAbhaNumber().equals(c.abhaNumber());
            return new Matched(c, List.of(number ? "ABHA_NUMBER" : "ABHA_ADDRESS"));
        }
        if (byAbha.size() > 1) {
            return new Ambiguous(byAbha.size());
        }

        List<Candidate> demographic = candidates.stream()
                .filter(c -> !c.abhaVerified())
                .filter(c -> query.verifiedMobile() != null && query.verifiedMobile().equals(c.mobile()))
                .filter(c -> sameGender(c, query) && sameYearOfBirth(c, query))
                .filter(c -> c.nameScore() >= NAME_SIMILARITY)
                .toList();
        if (demographic.isEmpty()) {
            return new NotFound();
        }
        if (demographic.size() == 1) {
            Candidate c = demographic.get(0);
            return new Matched(c, c.uhid().equalsIgnoreCase(String.valueOf(query.uhid()))
                    ? List.of("MOBILE", "MR") : List.of("MOBILE"));
        }
        List<Candidate> byUhid = query.uhid() == null ? List.of()
                : demographic.stream().filter(c -> c.uhid().equalsIgnoreCase(query.uhid())).toList();
        return byUhid.size() == 1 ? new Matched(byUhid.get(0), List.of("MOBILE", "MR")) : new Ambiguous(demographic.size());
    }

    private static boolean sameAbha(Candidate c, Query q) {
        return q.verifiedAbhaNumber() != null && q.verifiedAbhaNumber().equals(c.abhaNumber())
                || q.abhaAddress() != null && c.abhaAddress() != null && q.abhaAddress().equalsIgnoreCase(c.abhaAddress());
    }

    private static boolean sameGender(Candidate c, Query q) {
        return q.gender() != null && !"U".equals(q.gender()) && q.gender().equals(c.gender());
    }

    private static boolean sameYearOfBirth(Candidate c, Query q) {
        if (q.yearOfBirth() == null || c.dateOfBirth() == null) {
            return false;
        }
        int difference = Math.abs(c.dateOfBirth().getYear() - q.yearOfBirth());
        return difference <= (c.dobEstimated() ? ESTIMATED_DOB_TOLERANCE : 0);
    }
}
