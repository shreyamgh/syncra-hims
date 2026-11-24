package com.syncra.hims.billing;

/** Billing access matrix ({@code @PreAuthorize} expressions over JWT roles). */
public final class Roles {

    /** Issue OPD slips at the registration/billing counter. */
    public static final String OPD_BILLING = "hasAnyRole('RECEPTIONIST','BILLING_CLERK','ADMINISTRATOR')";

    /** Maintain IPD bills: refresh charges, add services, finalise at discharge. */
    public static final String IPD_BILLING = "hasAnyRole('BILLING_CLERK','ADMINISTRATOR')";

    /** Take money: settlements, refunds, IPD advances. */
    public static final String CASHIER = "hasAnyRole('RECEPTIONIST','BILLING_CLERK','ADMINISTRATOR')";

    /** See bills and tariffs. */
    public static final String VIEW = "hasAnyRole('RECEPTIONIST','BILLING_CLERK','ADMINISTRATOR','MRD_OFFICER','PHARMACIST')";

    /** Bill what the pharmacy dispensed, and take the money at the pharmacy counter. */
    public static final String PHARMACY_SALES = "hasAnyRole('PHARMACIST','BILLING_CLERK','ADMINISTRATOR')";

    /** Discounts, cancellations and voids. */
    public static final String SUPERVISOR = "hasRole('ADMINISTRATOR')";

    public static final String DISCOUNT_APPROVER_ROLE = "ADMINISTRATOR";

    private Roles() {
    }
}
