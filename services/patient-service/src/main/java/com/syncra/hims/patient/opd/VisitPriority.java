package com.syncra.hims.patient.opd;

/**
 * Queue priority. Ranks match {@code opd.v_queue_today.priority_rank}: emergencies
 * first, then priority groups (seniors, pregnant women, differently-abled, under-5s)
 * in token order, then everyone else.
 */
public enum VisitPriority {
    EMERGENCY(0), SENIOR_CITIZEN(1), PREGNANT(1), DIFFERENTLY_ABLED(1), CHILD_UNDER_5(1), NORMAL(2);

    private final int rank;

    VisitPriority(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    /** Default when the desk does not choose: emergencies, then age-based priority. */
    public static VisitPriority defaultFor(VisitType type, Integer ageYears) {
        if (type == VisitType.EMERGENCY) {
            return EMERGENCY;
        }
        if (ageYears != null && ageYears >= 60) {
            return SENIOR_CITIZEN;
        }
        if (ageYears != null && ageYears < 5) {
            return CHILD_UNDER_5;
        }
        return NORMAL;
    }
}
