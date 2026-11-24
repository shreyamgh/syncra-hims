package com.syncra.hims.common.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncra.hims.common.audit.AuditService;
import com.syncra.hims.common.config.HospitalTime;
import com.syncra.hims.common.persistence.JpaAuditingConfig;
import com.syncra.hims.common.security.CurrentUser;
import com.syncra.hims.common.security.SecurityConfig;
import com.syncra.hims.common.security.SecurityProperties;
import com.syncra.hims.common.web.ApiExceptionHandler;
import com.syncra.hims.common.web.ConstraintMessages;
import com.syncra.hims.common.web.DatabaseErrorTranslator;
import com.syncra.hims.common.web.RequestIdFilter;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Everything a Syncra HIMS servlet service shares: JWT re-validation and method
 * security, RFC 7807 errors with PHI-safe database translation, request ids, the
 * append-only audit trail and the hospital clock. Activated automatically for any
 * service that depends on {@code hims-common}; each bean can be overridden.
 */
@AutoConfiguration
@EnableConfigurationProperties({SecurityProperties.class, HospitalTime.Properties.class})
@Import({SecurityConfig.class, JpaAuditingConfig.class, ApiExceptionHandler.class})
public class HimsCommonAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock hospitalClock(HospitalTime.Properties properties) {
        return Clock.system(properties.timeZone());
    }

    @Bean
    @ConditionalOnMissingBean
    HospitalTime hospitalTime(Clock hospitalClock) {
        return new HospitalTime(hospitalClock);
    }

    @Bean
    @ConditionalOnMissingBean
    CurrentUser currentUser() {
        return new CurrentUser();
    }

    @Bean
    RequestIdFilter requestIdFilter() {
        return new RequestIdFilter();
    }

    @Bean
    @ConditionalOnMissingBean
    DatabaseErrorTranslator databaseErrorTranslator(List<ConstraintMessages> contributions) {
        return new DatabaseErrorTranslator(contributions);
    }

    @Bean
    @ConditionalOnMissingBean
    AuditService auditService(JdbcClient jdbc, CurrentUser currentUser, ObjectMapper objectMapper,
                              @Value("${spring.application.name}") String serviceName) {
        return new AuditService(jdbc, currentUser, objectMapper, serviceName);
    }
}
