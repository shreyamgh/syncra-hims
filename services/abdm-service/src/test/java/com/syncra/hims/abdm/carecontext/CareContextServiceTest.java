package com.syncra.hims.abdm.carecontext;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syncra.hims.abdm.gateway.AbdmCallException;
import com.syncra.hims.abdm.support.TestProperties;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class CareContextServiceTest {

    @Test
    void linkingIsRefusedBeforeAnythingIsCreatedWhileAbdmIsOff() {
        // Every collaborator is null: the call must fail before touching any of them.
        CareContextService service = new CareContextService(TestProperties.abdm(false, true), null, null, null, null,
                null, null, null, null, null, null);
        assertThatThrownBy(() -> service.link(UUID.randomUUID()))
                .isInstanceOfSatisfying(AbdmCallException.class, e -> {
                    org.assertj.core.api.Assertions.assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    org.assertj.core.api.Assertions.assertThat(e.code()).isEqualTo("ABDM_DISABLED");
                });
    }
}
