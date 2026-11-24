package com.syncra.hims.abdm.gateway;

/** HTTP headers of the ABDM V3 APIs. */
public final class AbdmHeaders {

    /** Unique id of every request (uuid); echoed as {@code response.requestId} in the asynchronous reply. */
    public static final String REQUEST_ID = "REQUEST-ID";
    /** ISO-8601 UTC time the request was made. */
    public static final String TIMESTAMP = "TIMESTAMP";
    /** Consent manager: {@code sbx} or {@code abdm}. */
    public static final String CM_ID = "X-CM-ID";
    /** Facility (HFR) id of the HIP. */
    public static final String HIP_ID = "X-HIP-ID";
    /** Facility (HFR) id of the HIU (this hospital, when it asks for records held elsewhere). */
    public static final String HIU_ID = "X-HIU-ID";
    /** Patient-scoped token authorising HIP-initiated care-context linking. */
    public static final String LINK_TOKEN = "X-LINK-TOKEN";
    /** ABHA user token returned by an ABHA login, for profile calls. */
    public static final String ABHA_USER_TOKEN = "X-token";

    private AbdmHeaders() {
    }
}
