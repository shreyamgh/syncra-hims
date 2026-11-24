package com.syncra.hims.patient.opd;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * OPD token lifecycle.
 *
 * <pre>
 * WAITING ──call──▶ CALLED ──start──▶ IN_CONSULTATION ──complete──▶ COMPLETED
 *    │  ▲             │  │                   │
 *    │  └──requeue────┘  │                   └──hold (sent for tests)──▶ ON_HOLD ──requeue/call──▶ …
 *    └──hold / no-show / cancel ─────────────┘
 * </pre>
 */
public enum QueueStatus {
    WAITING, CALLED, IN_CONSULTATION, ON_HOLD, COMPLETED, NO_SHOW, CANCELLED;

    private static final Map<QueueStatus, Set<QueueStatus>> ALLOWED = Map.of(
            WAITING, EnumSet.of(CALLED, ON_HOLD, NO_SHOW, CANCELLED),
            CALLED, EnumSet.of(IN_CONSULTATION, WAITING, ON_HOLD, NO_SHOW, CANCELLED),
            ON_HOLD, EnumSet.of(WAITING, CALLED, CANCELLED),
            IN_CONSULTATION, EnumSet.of(COMPLETED, ON_HOLD),
            COMPLETED, EnumSet.noneOf(QueueStatus.class),
            NO_SHOW, EnumSet.noneOf(QueueStatus.class),
            CANCELLED, EnumSet.noneOf(QueueStatus.class));

    public boolean canMoveTo(QueueStatus target) {
        return ALLOWED.get(this).contains(target);
    }

    /** Holds a token in today's queue (matches the opd.visit_one_live_token index). */
    public boolean isLive() {
        return this == WAITING || this == CALLED || this == IN_CONSULTATION || this == ON_HOLD;
    }

    /** Display order on the queue board: in progress first, finished last. */
    public int displayOrder() {
        return switch (this) {
            case IN_CONSULTATION -> 0;
            case CALLED -> 1;
            case WAITING -> 2;
            case ON_HOLD -> 3;
            case COMPLETED -> 4;
            case NO_SHOW -> 5;
            case CANCELLED -> 6;
        };
    }
}
