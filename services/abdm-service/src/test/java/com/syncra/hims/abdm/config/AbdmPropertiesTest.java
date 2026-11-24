package com.syncra.hims.abdm.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syncra.hims.abdm.support.TestProperties;
import org.junit.jupiter.api.Test;

class AbdmPropertiesTest {

    @Test
    void enablingAbdmRequiresTheFacilityIdAndCredentials() {
        AbdmProperties ok = TestProperties.abdm(true, true);
        assertThatThrownBy(() -> new AbdmProperties(true, "sbx", " ", null, ok.gateway(), ok.abha(), ok.callbacks(),
                ok.dataPush(), ok.linking(), ok.scanShare(), ok.discovery(), ok.otp(), ok.hiu(), ok.fhir()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("hip-id");
        assertThat(new AbdmProperties(false, "sbx", null, null, ok.gateway(), ok.abha(), ok.callbacks(),
                ok.dataPush(), ok.linking(), ok.scanShare(), ok.discovery(), ok.otp(), ok.hiu(), ok.fhir()).hipIdOrPlaceholder()).isEqualTo("HIP-NOT-CONFIGURED");
        assertThat(ok.gateway().toString()).doesNotContain("secret");
    }
}
