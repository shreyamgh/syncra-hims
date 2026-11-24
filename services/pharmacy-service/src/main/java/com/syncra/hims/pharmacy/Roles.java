package com.syncra.hims.pharmacy;

/** Who may do what in the pharmacy ({@code @PreAuthorize} expressions). */
public final class Roles {

    private Roles() {
    }

    /** Receive, transfer, dispense and write off stock. */
    public static final String PHARMACIST = "hasRole('PHARMACIST')";

    /** See stock, alerts and the registers. */
    public static final String VIEW = "hasAnyRole('PHARMACIST','ADMINISTRATOR')";
}
