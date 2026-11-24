package com.syncra.hims.iam;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * @param accessTokenTtl     lifetime of an access token (the desk renews it)
 * @param sessionTtl         longest a sign-in lasts; refreshes never extend it
 * @param maxFailedAttempts  wrong passwords in a row before the account locks
 * @param lockout            how long a lock lasts (an administrator can lift it sooner)
 * @param cookieSecure       mark the refresh cookie Secure (HTTPS only)
 * @param developmentSignIn  let the sign-in page offer development tokens (never in production)
 */
@Validated
@ConfigurationProperties("syncra.iam")
public record IamProperties(
        @NotNull @DefaultValue("PT30M") Duration accessTokenTtl,
        @NotNull @DefaultValue("PT12H") Duration sessionTtl,
        @Min(3) @Max(20) @DefaultValue("5") int maxFailedAttempts,
        @NotNull @DefaultValue("PT15M") Duration lockout,
        @DefaultValue("true") boolean cookieSecure,
        @DefaultValue("false") boolean developmentSignIn) {
}
