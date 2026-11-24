package com.syncra.hims.abdm.support;

import com.syncra.hims.abdm.config.AbdmProperties;
import java.net.URI;
import java.time.Duration;
import java.util.List;

/** {@link AbdmProperties} for unit tests (no Spring). */
public final class TestProperties {

    public static final String HIP_ID = "IN2710000001";

    private TestProperties() {
    }

    public static AbdmProperties abdm(boolean enabled, boolean requireHttps) {
        return new AbdmProperties(enabled, "sbx", HIP_ID, "Syncra Test Hospital",
                new AbdmProperties.Gateway(URI.create("http://localhost:1/api/hiecm"), "client", "secret",
                        Duration.ofSeconds(2), Duration.ofSeconds(5)),
                new AbdmProperties.Abha(URI.create("http://localhost:1/abha/api"), "1.4"),
                new AbdmProperties.Callbacks(null, null, List.of("RS256")),
                new AbdmProperties.DataPush(requireHttps, false, 10, Duration.ofHours(1)),
                new AbdmProperties.Linking(Duration.ofMinutes(25)),
                new AbdmProperties.ScanShare(Duration.ofMinutes(30), Duration.ofHours(24)),
                new AbdmProperties.Discovery(Duration.ofMinutes(30), 50),
                new AbdmProperties.Otp(null, "Authorization", null, null, null,
                        "{otp} is the OTP for {hospital}, valid {minutes} min.", Duration.ofMinutes(10), 3, 5),
                new AbdmProperties.Hiu(URI.create("https://bridge.hims.test"), Duration.ofHours(1), 30, 10_485_760),
                new AbdmProperties.Fhir("https://hims.test/fhir/identifier", "Syncra Test Hospital"));
    }
}
