package com.syncra.hims.abdm.gateway;

/**
 * ABDM V3 endpoints, relative to the HIE-CM gateway base URL (outbound) or to this
 * service (inbound callbacks). Kept in one place: when ABDM revises a path, change it here.
 */
public final class AbdmPaths {

    // ---- outbound, HIE-CM gateway
    public static final String SESSIONS = "/gateway/v3/sessions";
    public static final String CERTS = "/gateway/v3/certs";
    public static final String GENERATE_LINK_TOKEN = "/v3/token/generate-token";
    public static final String LINK_CARE_CONTEXT = "/hip/v3/link/carecontext";
    public static final String CONSENT_ON_NOTIFY = "/consent/v3/request/hip/on-notify";
    public static final String HEALTH_INFORMATION_ON_REQUEST = "/data-flow/v3/health-information/hip/on-request";
    public static final String HEALTH_INFORMATION_NOTIFY = "/data-flow/v3/health-information/notify";
    public static final String PATIENT_SHARE_ON_SHARE = "/patient-share/v3/on-share";
    public static final String ON_DISCOVER = "/user-initiated-linking/v3/patient/care-context/on-discover";
    public static final String ON_LINK_INIT = "/user-initiated-linking/v3/link/care-context/on-init";
    public static final String ON_LINK_CONFIRM = "/user-initiated-linking/v3/link/care-context/on-confirm";

    // ---- outbound, HIE-CM gateway, as HIU (Milestone 3)
    public static final String HIU_CONSENT_REQUEST_INIT = "/consent/v3/request/init";
    public static final String HIU_CONSENT_ON_NOTIFY = "/consent/v3/request/hiu/on-notify";
    public static final String HIU_CONSENT_FETCH = "/consent/v3/fetch";
    public static final String HIU_HEALTH_INFORMATION_REQUEST = "/data-flow/v3/health-information/request";

    // ---- outbound, ABHA service
    public static final String ABHA_PUBLIC_CERTIFICATE = "/v3/profile/public/certificate";
    public static final String ABHA_LOGIN_REQUEST_OTP = "/v3/profile/login/request/otp";
    public static final String ABHA_LOGIN_VERIFY = "/v3/profile/login/verify";
    public static final String ABHA_PROFILE = "/v3/profile/account";
    public static final String ABHA_CARD = "/v3/profile/account/abha-card";
    public static final String ABHA_ENROL_REQUEST_OTP = "/v3/enrollment/request/otp";
    public static final String ABHA_ENROL_BY_AADHAAR = "/v3/enrollment/enrol/byAadhaar";
    public static final String ABHA_ENROL_AUTH_BY_ABDM = "/v3/enrollment/auth/byAbdm";
    public static final String ABHA_ENROL_ADDRESS_SUGGESTIONS = "/v3/enrollment/enrol/suggestion";
    public static final String ABHA_ENROL_ADDRESS = "/v3/enrollment/enrol/abha-address";

    // ---- inbound callbacks (registered as this bridge's URL + path)
    public static final String CALLBACK_ROOT = "/api/v3";
    public static final String ON_GENERATE_TOKEN = "/api/v3/hip/token/on-generate-token";
    public static final String ON_LINK_CARE_CONTEXT = "/api/v3/link/on_carecontext";
    public static final String CONSENT_NOTIFY = "/api/v3/consent/request/hip/notify";
    public static final String HEALTH_INFORMATION_REQUEST = "/api/v3/hip/health-information/request";
    public static final String PATIENT_SHARE = "/api/v3/hip/patient/share";
    public static final String DISCOVER = "/api/v3/hip/patient/care-context/discover";
    public static final String LINK_INIT = "/api/v3/hip/link/care-context/init";
    public static final String LINK_CONFIRM = "/api/v3/hip/link/care-context/confirm";
    public static final String HIU_CONSENT_ON_INIT = "/api/v3/hiu/consent/request/on-init";
    public static final String HIU_CONSENT_NOTIFY = "/api/v3/hiu/consent/request/notify";
    public static final String HIU_CONSENT_ON_FETCH = "/api/v3/hiu/consent/on-fetch";
    public static final String HIU_HEALTH_INFORMATION_ON_REQUEST = "/api/v3/hiu/health-information/on-request";

    // ---- inbound from other HIPs (not the gateway): the data-push URL given in our requests
    public static final String HIU_DATA_PUSH_ROOT = "/hiu/v3/data/push";

    private AbdmPaths() {
    }
}
