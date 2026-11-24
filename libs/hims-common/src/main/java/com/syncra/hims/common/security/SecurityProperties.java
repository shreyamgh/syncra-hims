package com.syncra.hims.common.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** JWT verification settings, shared with the API gateway; validated at startup. */
@Validated
@ConfigurationProperties(prefix = "syncra.security")
public record SecurityProperties(@Valid @NotNull Jwt jwt) {

    public record Jwt(
            @NotBlank @Size(min = 32, message = "must be at least 32 bytes (256 bits)") String secret,
            @NotBlank String issuer,
            @NotBlank String audience) {

        @Override
        public String toString() {
            return "Jwt[secret=****, issuer=" + issuer + ", audience=" + audience + "]";
        }
    }
}
