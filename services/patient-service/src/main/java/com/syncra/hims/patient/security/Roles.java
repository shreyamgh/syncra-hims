package com.syncra.hims.patient.security;

/**
 * Role-based access matrix, as {@code @PreAuthorize} expressions. Role names
 * match {@code core.staff.staff_role} and the JWT {@code roles} claim.
 */
public final class Roles {

    /** Any member of hospital staff. */
    public static final String ANY_STAFF = "hasAnyRole('DOCTOR','NURSE','RECEPTIONIST','BILLING_CLERK',"
            + "'PHARMACIST','LAB_TECHNICIAN','RADIOGRAPHER','RADIOLOGIST','MRD_OFFICER','ADMINISTRATOR')";

    /** Register patients and edit demographics. */
    public static final String REGISTRATION =
            "hasAnyRole('RECEPTIONIST','NURSE','DOCTOR','MRD_OFFICER','ADMINISTRATOR')";

    /** Issue OPD tokens and manage the waiting queue. */
    public static final String OPD_DESK = "hasAnyRole('RECEPTIONIST','NURSE','DOCTOR','ADMINISTRATOR')";

    /** Start and complete consultations; sign complaints and prescriptions. */
    public static final String CONSULTATION = "hasRole('DOCTOR')";

    /** Read the clinical record and record vital signs. */
    public static final String CLINICAL = "hasAnyRole('DOCTOR','NURSE')";

    /** View the OPD queue. */
    public static final String OPD_VIEW =
            "hasAnyRole('RECEPTIONIST','NURSE','DOCTOR','BILLING_CLERK','ADMINISTRATOR')";

    /** Admit patients. */
    public static final String ADMISSION = "hasAnyRole('RECEPTIONIST','NURSE','DOCTOR','ADMINISTRATOR')";

    /** Transfer and discharge inpatients. */
    public static final String INPATIENT_CARE = "hasAnyRole('NURSE','DOCTOR','ADMINISTRATOR')";

    /** Change bed housekeeping state. */
    public static final String HOUSEKEEPING = "hasAnyRole('NURSE','ADMINISTRATOR')";

    /** View wards, beds and admissions. */
    public static final String IPD_VIEW =
            "hasAnyRole('RECEPTIONIST','NURSE','DOCTOR','BILLING_CLERK','MRD_OFFICER','ADMINISTRATOR')";

    /** Collect samples for laboratory orders. */
    public static final String SAMPLE_COLLECTION = "hasAnyRole('NURSE','LAB_TECHNICIAN')";

    /** Log that a critical result reached the treating team (who, how, read back). */
    public static final String CRITICAL_COMMUNICATION = "hasAnyRole('LAB_TECHNICIAN','NURSE')";

    /** Enter laboratory (and procedure) results. */
    public static final String RESULT_ENTRY = "hasAnyRole('LAB_TECHNICIAN','DOCTOR')";

    /** LIS / RIS worklists. */
    public static final String WORKLIST =
            "hasAnyRole('LAB_TECHNICIAN','RADIOGRAPHER','RADIOLOGIST','NURSE','DOCTOR')";

    /** Report imaging studies. */
    public static final String RADIOLOGY_REPORTING = "hasRole('RADIOLOGIST')";

    /** Match a PACS study to its order. */
    public static final String IMAGING_DESK = "hasAnyRole('RADIOGRAPHER','RADIOLOGIST')";

    private Roles() {
    }
}
