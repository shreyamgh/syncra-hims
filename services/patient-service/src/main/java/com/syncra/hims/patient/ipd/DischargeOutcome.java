package com.syncra.hims.patient.ipd;

/** How an inpatient stay ended. */
public enum DischargeOutcome {
    DISCHARGED, LAMA, ABSCONDED, EXPIRED, TRANSFERRED_OUT;

    public AdmissionStatus toStatus() {
        return AdmissionStatus.valueOf(name());
    }
}
