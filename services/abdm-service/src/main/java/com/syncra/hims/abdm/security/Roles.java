package com.syncra.hims.abdm.security;

/**
 * Role matrix for the staff API, as {@code @PreAuthorize} expressions. Role names match
 * {@code core.staff.staff_role} and the JWT {@code roles} claim.
 */
public final class Roles {

    /** Verify a patient's ABHA and serve the Scan &amp; Share queue. */
    public static final String ABHA_DESK =
            "hasAnyRole('RECEPTIONIST','NURSE','DOCTOR','MRD_OFFICER','ADMINISTRATOR')";

    /** Create care contexts for encounters and link them to the patient's ABHA. */
    public static final String RECORD_LINKING =
            "hasAnyRole('RECEPTIONIST','NURSE','DOCTOR','MRD_OFFICER','ADMINISTRATOR')";

    /** Generate and download a patient's FHIR records. */
    public static final String RECORD_EXPORT = "hasAnyRole('DOCTOR','MRD_OFFICER','ADMINISTRATOR')";

    /** See consents received and what was shared under them. */
    public static final String CONSENT_VIEW = "hasAnyRole('DOCTOR','MRD_OFFICER','ADMINISTRATOR')";

    /** Ask a patient's consent for, and read, their records held by other facilities (care management). */
    public static final String EXTERNAL_RECORDS = "hasRole('DOCTOR')";

    private Roles() {
    }
}
