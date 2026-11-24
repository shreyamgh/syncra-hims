package com.syncra.hims.gateway.config;

import com.syncra.hims.gateway.filter.RequestIdWebFilter;
import com.syncra.hims.gateway.security.WebSocketAwareBearerTokenConverter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/**
 * Stateless bearer-token security for the gateway.
 *
 * <p>Every routed path requires a valid HS256 JWT issued by the Syncra IAM service.
 * The original {@code Authorization} header is forwarded unchanged so downstream
 * services re-validate the token and apply their own role-based authorisation
 * (defence in depth — the gateway is not the only line of control).
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
public class SecurityConfig {

    /** RFC 7518 §3.2: HS256 keys MUST be at least 256 bits. */
    private static final int MIN_HS256_SECRET_BYTES = 32;

    /** Path of the ABDM gateway's calls to this hospital (the HIP bridge URL registered with ABDM). */
    public static final String ABDM_CALLBACKS = "/api/v3/**";

    /** Other hospitals pushing consented records to this one (the HIU data-push URL, a one-time secret). */
    public static final String ABDM_DATA_PUSH = "/hiu/v3/data/push/**";

    /**
     * ABDM's server-to-server callbacks carry ABDM's own signed JWT, not a Syncra one, so they
     * bypass the Syncra token check here and are verified by the ABDM Service against ABDM's
     * published keys. Only POST is let through; no CORS (no browser ever calls these).
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityWebFilterChain abdmCallbackChain(ServerHttpSecurity http) {
        return http
                .securityMatcher(ServerWebExchangeMatchers.pathMatchers(HttpMethod.POST, ABDM_CALLBACKS, ABDM_DATA_PUSH))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(exchanges -> exchanges.anyExchange().permitAll())
                .build();
    }

    /** Sign-in endpoints: how a token is obtained, so none is required (an expired one is ignored). */
    public static final String SIGN_IN = "/api/v1/auth/**";

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    SecurityWebFilterChain signInChain(ServerHttpSecurity http, GatewayProperties properties) {
        return http
                .securityMatcher(ServerWebExchangeMatchers.pathMatchers(SIGN_IN))
                .csrf(ServerHttpSecurity.CsrfSpec::disable) // JSON-only endpoints; SameSite=Strict cookie
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .cors(cors -> cors.configurationSource(corsConfigurationSource(properties)))
                .authorizeExchange(exchanges -> exchanges.anyExchange().permitAll())
                .build();
    }

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, GatewayProperties properties) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable) // bearer tokens only, no cookies
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                // Passed explicitly rather than as a bean: Spring Security's by-type lookup
                // silently disables CORS if a second CorsConfigurationSource bean ever appears.
                .cors(cors -> cors.configurationSource(corsConfigurationSource(properties)))
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // Actuator is served on the separate, unpublished management port.
                        .pathMatchers("/actuator/health", "/actuator/health/**",
                                "/actuator/info", "/actuator/prometheus").permitAll()
                        // Clinical dictation streams patient audio: clinicians only.
                        .pathMatchers("/ws/ai/**").hasAnyRole("DOCTOR", "NURSE")
                        // Images are clinical records: clinicians and imaging staff only.
                        .pathMatchers("/pacs/**").hasAnyRole("DOCTOR", "NURSE", "RADIOGRAPHER", "RADIOLOGIST")
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .bearerTokenConverter(new WebSocketAwareBearerTokenConverter())
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .build();
    }

    @Bean
    ReactiveJwtDecoder jwtDecoder(GatewayProperties properties) {
        GatewayProperties.Jwt settings = properties.jwt();
        byte[] secret = settings.secret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < MIN_HS256_SECRET_BYTES) {
            throw new IllegalStateException("syncra.gateway.jwt.secret must be at least "
                    + MIN_HS256_SECRET_BYTES + " bytes for HS256");
        }

        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder
                .withSecretKey(new SecretKeySpec(secret, "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();

        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>(
                JwtClaimNames.AUD, aud -> aud != null && aud.contains(settings.audience()));
        // The default timestamp validator only checks exp *if present*; HIMS tokens must expire.
        OAuth2TokenValidator<Jwt> expiryRequired = new JwtClaimValidator<Instant>(
                JwtClaimNames.EXP, Objects::nonNull);
        OAuth2TokenValidator<Jwt> subjectRequired = new JwtClaimValidator<String>(
                JwtClaimNames.SUB, sub -> sub != null && !sub.isBlank());

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(settings.issuer()),
                audience, expiryRequired, subjectRequired));
        return decoder;
    }

    private static CorsConfigurationSource corsConfigurationSource(GatewayProperties properties) {
        List<String> origins = properties.cors().allowedOrigins();
        if (origins.stream().anyMatch(origin -> origin.contains("*"))) {
            throw new IllegalStateException("Wildcard CORS origins are not permitted; list exact origins");
        }

        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(origins);
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of(
                HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, HttpHeaders.ACCEPT,
                HttpHeaders.IF_MATCH, HttpHeaders.IF_NONE_MATCH, RequestIdWebFilter.HEADER));
        cors.setExposedHeaders(List.of(
                RequestIdWebFilter.HEADER, HttpHeaders.ETAG, HttpHeaders.LOCATION,
                HttpHeaders.CONTENT_DISPOSITION, "X-RateLimit-Remaining"));
        cors.setAllowCredentials(false);
        cors.setMaxAge(Duration.ofHours(1));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }

    /** Maps the {@code roles} claim (e.g. ["DOCTOR","NURSE"]) to {@code ROLE_*} authorities. */
    private static ReactiveJwtAuthenticationConverterAdapter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return new ReactiveJwtAuthenticationConverterAdapter(converter);
    }
}
