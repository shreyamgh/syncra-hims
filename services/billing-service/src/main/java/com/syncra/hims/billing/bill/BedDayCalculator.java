package com.syncra.hims.billing.bill;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Bed-days by <b>midnight census</b>, the usual hospital billing convention: a bed
 * is charged once for every midnight (hospital time) the patient spends in it.
 *
 * <ul>
 *   <li>Admitted 10:00 on the 10th, discharged 11:00 on the 13th: 3 midnights → 3 days.</li>
 *   <li>A transfer splits the days: each bed is charged for the midnights spent in it,
 *       at that bed's ward class.</li>
 *   <li>A stay with no midnight (same-day or day-care) is charged one day, to the bed
 *       the patient spent longest in.</li>
 * </ul>
 */
public final class BedDayCalculator {

    /** One period in one bed. {@code releasedAt} null means still occupied. */
    public record Stay(UUID assignmentId, String bedCode, String bedCategory, Instant assignedAt, Instant releasedAt) {
    }

    /** Chargeable days for one stay; {@code firstDay}/{@code lastDay} are the census dates covered. */
    public record Charge(UUID assignmentId, String bedCode, String bedCategory, int days, LocalDate firstDay,
                         LocalDate lastDay) {
    }

    private BedDayCalculator() {
    }

    /**
     * @param stays   the admission's bed assignments
     * @param stayEnd end of the stay to bill up to: the discharge time, or "now" for an
     *                interim bill (applies to stays not yet released)
     */
    public static List<Charge> calculate(List<Stay> stays, Instant stayEnd, ZoneId zone) {
        List<Stay> ordered = stays.stream().sorted(Comparator.comparing(Stay::assignedAt)).toList();
        List<Charge> charges = new ArrayList<>();
        for (Stay stay : ordered) {
            Instant end = stay.releasedAt() == null || stay.releasedAt().isAfter(stayEnd) ? stayEnd : stay.releasedAt();
            LocalDate from = stay.assignedAt().atZone(zone).toLocalDate();
            LocalDate to = end.atZone(zone).toLocalDate();
            int midnights = end.isAfter(stay.assignedAt()) ? (int) ChronoUnit.DAYS.between(from, to) : 0;
            charges.add(new Charge(stay.assignmentId(), stay.bedCode(), stay.bedCategory(), midnights,
                    from, midnights > 0 ? to.minusDays(1) : from));
        }
        if (!charges.isEmpty() && charges.stream().allMatch(c -> c.days() == 0)) {
            int longest = 0;
            Duration best = Duration.ZERO;
            for (int i = 0; i < ordered.size(); i++) {
                Stay s = ordered.get(i);
                Instant end = s.releasedAt() == null || s.releasedAt().isAfter(stayEnd) ? stayEnd : s.releasedAt();
                Duration d = Duration.between(s.assignedAt(), end);
                if (d.compareTo(best) > 0) {
                    best = d;
                    longest = i;
                }
            }
            Charge c = charges.get(longest);
            charges.set(longest, new Charge(c.assignmentId(), c.bedCode(), c.bedCategory(), 1, c.firstDay(), c.firstDay()));
        }
        return charges;
    }
}
