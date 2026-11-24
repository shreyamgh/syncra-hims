package com.syncra.hims.patient.registration;

import com.syncra.hims.common.web.ApiException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/**
 * Unique Health Identifier, {@code SYNC-YYYYMMDD-XXXX}: the registration date in
 * hospital time plus a 4-digit daily serial (0001-9999). Issued exclusively by
 * the database function {@code patient.next_uhid()}, which is gap-free and safe
 * across concurrent registration desks and service replicas.
 */
public record Uhid(String value) {

    private static final Pattern FORMAT = Pattern.compile("^SYNC-(\\d{8})-(\\d{4})$");
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;

    public Uhid {
        Matcher m = value == null ? null : FORMAT.matcher(value);
        if (m == null || !m.matches() || Integer.parseInt(m.group(2)) == 0) {
            throw new IllegalArgumentException("Not a UHID: " + value);
        }
        try {
            LocalDate.parse(m.group(1), DATE);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("UHID has an invalid date: " + value, e);
        }
    }

    /** Parses user input (case/whitespace tolerant); 400 INVALID_UHID when malformed. */
    public static Uhid parse(String raw) {
        try {
            return new Uhid(raw == null ? null : raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_UHID", "UHID must look like SYNC-YYYYMMDD-XXXX");
        }
    }

    public static boolean looksLike(String raw) {
        return raw != null && FORMAT.matcher(raw.trim().toUpperCase(Locale.ROOT)).matches();
    }

    public LocalDate registrationDate() {
        return LocalDate.parse(value.substring(5, 13), DATE);
    }

    public int dailySerial() {
        return Integer.parseInt(value.substring(14));
    }

    @Override
    public String toString() {
        return value;
    }
}
