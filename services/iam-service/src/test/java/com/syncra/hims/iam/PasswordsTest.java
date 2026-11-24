package com.syncra.hims.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.syncra.hims.iam.auth.Passwords;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PasswordsTest {

    final Passwords passwords = new Passwords();

    @Test
    void prefersLengthOverCompositionAndRefusesCommonOrPersonalPasswords() {
        assertThat(passwords.problems("monsoon tea at the ward", "dr.asha")).isEmpty();
        assertThat(passwords.problems("short", "dr.asha")).hasSize(1).first().asString().contains("at least 10");
        assertThat(passwords.problems("Password123", "dr.asha")).containsExactly("This password is too common or too simple; choose another");
        assertThat(passwords.problems("aaaaabbbbbaaaaa", "dr.asha")).containsExactly("This password is too common or too simple; choose another");
        assertThat(passwords.problems("dr.asha-2026-ward", "dr.asha")).containsExactly("The password must not contain the username");
    }

    @Test
    void hashesWithBcryptAndMakesUnambiguousTemporaryPasswords() {
        String hash = passwords.hash("monsoon tea at the ward");
        assertThat(hash).startsWith("$2a$12$");
        assertThat(passwords.matches("monsoon tea at the ward", hash)).isTrue();
        assertThat(passwords.matches("monsoon tea at the war", hash)).isFalse();
        assertThat(passwords.matches("anything", null)).isFalse();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            String temporary = passwords.temporary();
            assertThat(temporary).hasSize(12).doesNotContainAnyWhitespaces().matches("[^0O1lI]+");
            assertThat(passwords.problems(temporary, "someone")).isEmpty();
            seen.add(temporary);
        }
        assertThat(seen).hasSize(50);
    }
}
