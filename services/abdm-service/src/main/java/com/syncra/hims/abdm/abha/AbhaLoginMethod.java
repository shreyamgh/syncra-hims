package com.syncra.hims.abdm.abha;

import java.util.List;

/**
 * How the patient proves they own the ABHA: an OTP to the Aadhaar-linked mobile or to the
 * mobile registered with ABHA. The scope must be the same in the OTP request and its verification.
 */
public enum AbhaLoginMethod {

    AADHAAR_OTP(List.of("abha-login", "aadhaar-verify"), "aadhaar"),
    MOBILE_OTP(List.of("abha-login", "mobile-verify"), "abdm");

    private final List<String> scope;
    private final String otpSystem;

    AbhaLoginMethod(List<String> scope, String otpSystem) {
        this.scope = scope;
        this.otpSystem = otpSystem;
    }

    public List<String> scope() {
        return scope;
    }

    public String otpSystem() {
        return otpSystem;
    }
}
