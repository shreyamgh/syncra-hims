package com.syncra.hims.iam.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.syncra.hims.common.security.SecurityProperties;
import com.syncra.hims.iam.IamProperties;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/** Signs the access tokens (HS256) that the gateway and every service verify. */
@Component
public class TokenIssuer {

    public record AccessToken(String value, Instant expiresAt) {
    }

    private final NimbusJwtEncoder encoder;
    private final SecurityProperties.Jwt jwt;
    private final IamProperties properties;

    public TokenIssuer(SecurityProperties security, IamProperties properties) {
        this.jwt = security.jwt();
        this.properties = properties;
        SecretKey key = new SecretKeySpec(jwt.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    public AccessToken issue(String username, String displayName, List<String> roles, Instant now, Instant sessionEnds) {
        Instant expires = now.plus(properties.accessTokenTtl());
        if (expires.isAfter(sessionEnds)) {
            expires = sessionEnds;
        }
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwt.issuer())
                .audience(List.of(jwt.audience()))
                .subject(username)
                .claim("name", displayName)
                .claim("roles", roles)
                .issuedAt(now)
                .notBefore(now)
                .expiresAt(expires)
                .id(UUID.randomUUID().toString())
                .build();
        String value = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new AccessToken(value, expires);
    }
}
