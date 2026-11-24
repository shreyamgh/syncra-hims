package com.syncra.hims.patient.ipd;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Operational state of a bed. OCCUPIED and CLEANING are set only by the database
 * when a bed is assigned or released; staff move beds between the other states.
 */
public enum BedStatus {
    AVAILABLE, RESERVED, OCCUPIED, CLEANING, MAINTENANCE, BLOCKED;

    /** Manual (housekeeping) transitions: target state -> states it may be reached from. */
    private static final Map<BedStatus, Set<BedStatus>> MANUAL_FROM = Map.of(
            AVAILABLE, EnumSet.of(CLEANING, MAINTENANCE, BLOCKED, RESERVED),
            RESERVED, EnumSet.of(AVAILABLE),
            MAINTENANCE, EnumSet.of(AVAILABLE, CLEANING, BLOCKED),
            BLOCKED, EnumSet.of(AVAILABLE, CLEANING, MAINTENANCE));

    public boolean isManuallySettable() {
        return MANUAL_FROM.containsKey(this);
    }

    public Set<BedStatus> manuallyReachableFrom() {
        return MANUAL_FROM.getOrDefault(this, EnumSet.noneOf(BedStatus.class));
    }
}
