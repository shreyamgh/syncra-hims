package com.syncra.hims.common.persistence;

import com.syncra.hims.common.config.HospitalTime;
import com.syncra.hims.common.security.CurrentUser;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/** Fills created/updated by/at from the JWT subject and the hospital clock. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "org.springframework.data.jpa.repository.config.EnableJpaAuditing")
@EnableJpaAuditing(auditorAwareRef = "auditorAware", dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaAuditingConfig {

    @Bean
    AuditorAware<String> auditorAware(CurrentUser currentUser) {
        return () -> Optional.of(currentUser.subject());
    }

    @Bean
    DateTimeProvider auditingDateTimeProvider(HospitalTime time) {
        return () -> Optional.of(time.now());
    }
}
