package com.syncra.hims.patient.ipd;

/** Inpatient stay status. LAMA = left against medical advice. */
public enum AdmissionStatus {
    ADMITTED, DISCHARGE_INITIATED, DISCHARGED, LAMA, ABSCONDED, EXPIRED, TRANSFERRED_OUT, CANCELLED;

    /** Still occupying (or entitled to) a bed. */
    public boolean isActive() {
        return this == ADMITTED || this == DISCHARGE_INITIATED;
    }
}
