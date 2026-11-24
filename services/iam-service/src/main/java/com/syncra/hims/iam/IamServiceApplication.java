package com.syncra.hims.iam;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Syncra HIMS IAM Service: staff sign-in with a username and password (bcrypt, lockout,
 * forced change of temporary passwords), the HS256 access tokens every service verifies,
 * rotating refresh sessions in an HttpOnly cookie, and account administration.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class IamServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(IamServiceApplication.class, args);
    }
}
