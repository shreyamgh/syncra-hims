package com.syncra.hims.common.config;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Single source of "now" and "today" for the hospital, so business dates (UHID
 * date, OPD token day, ages, bed-day census) match the database's
 * {@code core.ist_today()} regardless of the JVM or server time zone.
 * Backed by an injectable {@link Clock} for tests.
 */
public class HospitalTime {

    private final Clock clock;

    public HospitalTime(Clock hospitalClock) {
        this.clock = hospitalClock;
    }

    /** Current instant truncated to PostgreSQL's microsecond precision. */
    public Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    public ZoneId zone() {
        return clock.getZone();
    }

    public LocalDate dateOf(Instant instant) {
        return instant.atZone(clock.getZone()).toLocalDate();
    }

    public Integer ageInYears(LocalDate dateOfBirth) {
        return dateOfBirth == null ? null : Period.between(dateOfBirth, today()).getYears();
    }

    @ConfigurationProperties(prefix = "syncra.hospital")
    public record Properties(ZoneId timeZone) {
        public Properties {
            if (timeZone == null) {
                timeZone = ZoneId.of("Asia/Kolkata");
            }
        }
    }
}
