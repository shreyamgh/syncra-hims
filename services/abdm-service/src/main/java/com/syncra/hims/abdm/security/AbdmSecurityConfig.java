package com.syncra.hims.abdm.security;

import com.syncra.hims.abdm.config.AbdmProperties;
import com.syncra.hims.abdm.gateway.AbdmPaths;
import com.syncra.hims.common.security.SecurityConfig;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Three callers, three filter chains.
 *
 * <ol>
 *   <li>{@code /hiu/v3/data/push/*}: other hospitals pushing consented records here (no
 *       credential in ABDM's protocol; see {@code hiuDataPushChain}).</li>
 *   <li>{@code /api/v3/**}: callbacks from ABDM. The bearer token must be a JWT signed by the
 *       ABDM gateway (keys from its JWKS endpoint); the Syncra staff token is not accepted
 *       here. The caller is recorded as {@value #GATEWAY_PRINCIPAL}.</li>
 *   <li>Everything else: the standard staff chain from {@code hims-common} (Syncra HS256 JWT,
 *       roles checked per endpoint).</li>
 * </ol>
 * The ABDM decoder is deliberately not a {@link JwtDecoder} bean, so the staff chain keeps the
 * shared Syncra decoder.
 */
@Configuration(proxyBeanMethods = false)
public class AbdmSecurityConfig {

    public static final String GATEWAY_PRINCIPAL = "abdm-gateway";
    public static final String GATEWAY_ROLE = "ROLE_ABDM_GATEWAY";
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

    @Bean
    @Order(1)
    SecurityFilterChain abdmCallbackChain(HttpSecurity http, AbdmProperties properties) throws Exception {
        JwtDecoder gatewayTokens = gatewayJwtDecoder(properties);
        return http
                .securityMatcher(AbdmPaths.CALLBACK_ROOT + "/**")
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.POST, AbdmPaths.CALLBACK_ROOT + "/**").hasAuthority(GATEWAY_ROLE)
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt
                        .decoder(gatewayTokens)
                        .jwtAuthenticationConverter(token -> new JwtAuthenticationToken(
                                token, AuthorityUtils.createAuthorityList(GATEWAY_ROLE), GATEWAY_PRINCIPAL))))
                .build();
    }

    /**
     * Other hospitals pushing records this hospital asked for (HIU data push). ABDM's push carries
     * no credential: the one-time secret in the URL and the per-request decryption key are the
     * protection, both checked by {@code HiuService}. POST only.
     */
    @Bean
    @Order(0)
    SecurityFilterChain hiuDataPushChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher(AbdmPaths.HIU_DATA_PUSH_ROOT + "/**")
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .anonymous(anonymous -> anonymous.principal("abdm-hip-push"))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.POST, AbdmPaths.HIU_DATA_PUSH_ROOT + "/*").permitAll()
                        .anyRequest().denyAll())
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain staffChain(HttpSecurity http) throws Exception {
        return SecurityConfig.staffApi(http).build();
    }

    /**
     * Verifies ABDM's callback tokens: signature against the gateway's published keys,
     * expiry required, issuer when configured. With the integration off, every token is refused.
     */
    static JwtDecoder gatewayJwtDecoder(AbdmProperties properties) {
        if (!properties.enabled()) {
            return token -> {
                throw new BadJwtException("ABDM integration is disabled");
            };
        }
        URI configured = properties.callbacks().jwksUri();
        String jwks = configured != null && !configured.toString().isBlank()
                ? configured.toString()
                : UriComponentsBuilder.fromUri(properties.gateway().baseUrl()).path(AbdmPaths.CERTS).toUriString();
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwks)
                .jwsAlgorithms(algorithms -> properties.callbacks().algorithms().stream()
                        .map(SignatureAlgorithm::from)
                        .map(Objects::requireNonNull)
                        .forEach(algorithms::add))
                .build();

        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(new JwtTimestampValidator(CLOCK_SKEW));
        validators.add(new JwtClaimValidator<Instant>(JwtClaimNames.EXP, Objects::nonNull));
        String issuer = properties.callbacks().issuer();
        if (issuer != null && !issuer.isBlank()) {
            validators.add(new JwtIssuerValidator(issuer));
        }
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return decoder;
    }
}
