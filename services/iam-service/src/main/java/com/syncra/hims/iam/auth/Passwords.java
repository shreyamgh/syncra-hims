package com.syncra.hims.iam.auth;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Password hashing (bcrypt, cost 12), the password rules, and temporary passwords.
 *
 * <p>The rules follow NIST SP 800-63B: length over composition (10 to 128 characters, any
 * characters, spaces allowed), and no password that is common or contains the username.
 */
@Component
public class Passwords {

    public static final int MIN_LENGTH = 10;
    public static final int MAX_LENGTH = 128;

    /** Commonly used passwords and words from this setting (compared case-insensitively). */
    private static final Set<String> COMMON = Set.of(
            "password", "password1", "password123", "passw0rd", "1234567890", "12345678910", "0123456789",
            "qwertyuiop", "qwerty1234", "1q2w3e4r5t", "abcd123456", "welcome123", "welcome@123", "admin12345",
            "administrator", "letmein123", "iloveyou12", "india12345", "india@1234", "bharat1234", "hospital123",
            "hospital@123", "doctor1234", "doctor@123", "nurse12345", "syncra1234", "syncra@123", "changeme123",
            "aaaaaaaaaa", "1111111111", "0000000000", "9999999999", "jaihind123", "sachin1234");

    /** No 0/O, 1/l/I: temporary passwords are read out or copied by hand. */
    private static final char[] ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
    private final SecureRandom random = new SecureRandom();
    /** Compared against when the username is unknown, so the response takes the same time. */
    private final String decoy = encoder.encode("decoy-password-for-unknown-users");

    public String hash(String password) {
        return encoder.encode(password);
    }

    public boolean matches(String password, String hash) {
        return encoder.matches(password, hash == null ? decoy : hash);
    }

    /** Why a new password is not acceptable for this user (empty when it is). */
    public List<String> problems(String password, String username) {
        List<String> problems = new ArrayList<>();
        if (password == null || password.length() < MIN_LENGTH) {
            problems.add("Use at least " + MIN_LENGTH + " characters (a phrase of several words is easy to remember)");
            return problems;
        }
        if (password.length() > MAX_LENGTH) {
            problems.add("Use at most " + MAX_LENGTH + " characters");
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (COMMON.contains(lower) || lower.chars().distinct().count() < 4) {
            problems.add("This password is too common or too simple; choose another");
        }
        if (username != null && username.length() >= 3 && lower.contains(username.toLowerCase(Locale.ROOT))) {
            problems.add("The password must not contain the username");
        }
        return problems;
    }

    /** A random 12-character temporary password, to be changed at first sign-in. */
    public String temporary() {
        StringBuilder password = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            password.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return password.toString();
    }
}
