package com.syncra.hims.iam.security;

import com.syncra.hims.common.security.SecurityConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Two chains: the sign-in endpoints ({@code /api/v1/auth/**}) take no token (they are how one is
 * obtained); everything else is the standard staff chain (Syncra JWT, roles per endpoint).
 */
@Configuration(proxyBeanMethods = false)
public class IamSecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain signInChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/api/v1/auth/**")
                .csrf(AbstractHttpConfigurer::disable) // JSON-only endpoints; the cookie is SameSite=Strict
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain staffChain(HttpSecurity http) throws Exception {
        return SecurityConfig.staffApi(http).build();
    }
}
