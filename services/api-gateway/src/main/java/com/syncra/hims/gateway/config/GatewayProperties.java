package com.syncra.hims.gateway.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Strongly-typed gateway configuration, bound from {@code syncra.gateway.*}.
 * Validated at startup so a mis-configured deployment fails fast instead of
 * running with weak or missing security settings.
 */
@Validated
@ConfigurationProperties(prefix = "syncra.gateway")
public record GatewayProperties(
        @Valid @NotNull Jwt jwt,
        @Valid @NotNull Cors cors,
        @Valid @NotNull Orthanc orthanc,
        /* Marks the image-viewer cookie Secure (HTTPS only). Browsers treat localhost as secure. */
        @org.springframework.boot.context.properties.bind.DefaultValue("true") boolean pacsCookieSecure) {

    /**
     * HS256 token verification settings.
     *
     * @param secret   shared signing secret; at least 256 bits as required by RFC 7518 §3.2
     * @param issuer   expected {@code iss} claim
     * @param audience expected value within the {@code aud} claim
     */
    public record Jwt(
            @NotBlank @Size(min = 32, message = "must be at least 32 bytes (256 bits)") String secret,
            @NotBlank String issuer,
            @NotBlank String audience) {

        @Override
        public String toString() {
            return "Jwt[secret=****, issuer=" + issuer + ", audience=" + audience + "]";
        }
    }

    /** @param allowedOrigins exact browser origins allowed to call the API (no wildcards) */
    public record Cors(@NotEmpty List<@NotBlank String> allowedOrigins) {
    }

    /**
     * Orthanc PACS upstream. Credentials are injected by the gateway so that
     * Orthanc itself is never reachable without a valid Syncra JWT.
     */
    public record Orthanc(@NotNull URI uri, @NotBlank String username, @NotBlank String password) {

        @Override
        public String toString() {
            return "Orthanc[uri=" + uri + ", username=" + username + ", password=****]";
        }
    }
}
