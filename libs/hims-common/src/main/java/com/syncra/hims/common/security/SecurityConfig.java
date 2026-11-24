package com.syncra.hims.common.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Re-validates the bearer token forwarded by the gateway (defence in depth: the
 * service is never trusted to be reachable only through the gateway) and
 * enables method-level role checks ({@code @PreAuthorize} with each service's role matrix).
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfig {

    private static final int MIN_HS256_SECRET_BYTES = 32;

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return staffApi(http).build();
    }

    /**
     * The staff API rules: stateless, {@code /api/**} needs a valid Syncra JWT (decoded by
     * the {@link JwtDecoder} bean), everything else outside actuator is denied. Services
     * that declare an extra filter chain of their own (which switches off the default
     * chain above) build their staff chain from this.
     */
    public static HttpSecurity staffApi(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable) // stateless bearer-token API
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        // Actuator lives on the unpublished management port.
                        .requestMatchers("/actuator/health", "/actuator/health/**",
                                "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
    }

    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    JwtDecoder jwtDecoder(SecurityProperties properties) {
        SecurityProperties.Jwt settings = properties.jwt();
        byte[] secret = settings.secret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < MIN_HS256_SECRET_BYTES) {
            throw new IllegalStateException("syncra.security.jwt.secret must be at least 32 bytes");
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withSecretKey(new SecretKeySpec(secret, "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();

        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>(
                JwtClaimNames.AUD, aud -> aud != null && aud.contains(settings.audience()));
        OAuth2TokenValidator<Jwt> expiryRequired = new JwtClaimValidator<Instant>(JwtClaimNames.EXP, Objects::nonNull);
        OAuth2TokenValidator<Jwt> subjectRequired = new JwtClaimValidator<String>(
                JwtClaimNames.SUB, sub -> sub != null && !sub.isBlank());
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(settings.issuer()), audience, expiryRequired, subjectRequired));
        return decoder;
    }

    private static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
