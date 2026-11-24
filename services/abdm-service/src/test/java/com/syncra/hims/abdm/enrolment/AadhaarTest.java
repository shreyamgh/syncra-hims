package com.syncra.hims.abdm.enrolment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AadhaarTest {

    /** A 12-digit number with a correct Verhoeff check digit. */
    static String valid(String elevenDigits) {
        return elevenDigits + Aadhaar.verhoeffDigit(elevenDigits);
    }

    @Test
    void verhoeffMatchesThePublishedExample() {
        // Worked example of the Verhoeff algorithm: the check digit of 236 is 3
        assertThat(Aadhaar.verhoeffDigit("236")).isEqualTo(3);
        assertThat(Aadhaar.verhoeffValid("2363")).isTrue();
        assertThat(Aadhaar.verhoeffValid("2364")).isFalse();
    }

    @Test
    void acceptsFormattedNumbersWithAValidCheckDigit() {
        String number = valid("23456789012");
        String spaced = number.substring(0, 4) + " " + number.substring(4, 8) + " " + number.substring(8);
        assertThat(Aadhaar.normalise(spaced)).isEqualTo(number);
        assertThat(Aadhaar.masked(number)).isEqualTo("XXXX-XXXX-" + number.substring(8));
    }

    @Test
    void catchesTyposAndImpossibleNumbers() {
        String number = valid("23456789012");
        int last = number.charAt(11) - '0';
        assertThat(Aadhaar.normalise(number.substring(0, 11) + ((last + 1) % 10))).as("wrong digit").isNull();
        String swapped = number.charAt(1) + "" + number.charAt(0) + number.substring(2);
        assertThat(Aadhaar.normalise(swapped)).as("adjacent swap").isNull();
        assertThat(Aadhaar.normalise(valid("13456789012"))).as("cannot start with 1").isNull();
        assertThat(Aadhaar.normalise("2345678901")).isNull();
        assertThat(Aadhaar.normalise("23456789012a")).isNull();
        assertThat(Aadhaar.normalise(null)).isNull();
    }
}
