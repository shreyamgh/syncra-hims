package com.syncra.hims.abdm.dataflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.syncra.hims.abdm.support.TestProperties;
import org.junit.jupiter.api.Test;

/** The HIU's dataPushUrl is attacker-influenced input: keep pushes off internal networks. */
class PushUrlPolicyTest {

    final HealthInformationService production = new HealthInformationService(null, null, null, null, null,
            TestProperties.abdm(true, true), null);

    @Test
    void publicHttpsUrlsAreAccepted() {
        assertThat(production.pushUrlProblem("https://hiu.example.org/data/push")).isNull();
        assertThat(production.pushUrlProblem("https://203.0.113.10/push")).isNull();
    }

    @Test
    void plainHttpAndInternalTargetsAreRefused() {
        assertThat(production.pushUrlProblem("http://hiu.example.org/push")).contains("HTTPS");
        assertThat(production.pushUrlProblem("https://localhost/push")).contains("internal");
        assertThat(production.pushUrlProblem("https://127.0.0.1:8443/push")).contains("internal");
        assertThat(production.pushUrlProblem("https://10.0.0.5/push")).contains("internal");
        assertThat(production.pushUrlProblem("https://169.254.169.254/latest/meta-data")).contains("internal");
        assertThat(production.pushUrlProblem("https://[::1]/push")).contains("internal");
        assertThat(production.pushUrlProblem("https://user:pass@hiu.example.org/push")).isEqualTo("Malformed dataPushUrl");
        assertThat(production.pushUrlProblem("ftp://hiu.example.org/push")).isEqualTo("Malformed dataPushUrl");
        assertThat(production.pushUrlProblem(null)).contains("Missing");
    }
}
