package com.syncra.hims.common.web;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/**
 * Entity-tag helpers for optimistic concurrency: the ETag is the row's JPA
 * {@code version}, and updates must send it back in {@code If-Match}.
 */
public final class ETags {

    private static final Pattern VERSION_TAG = Pattern.compile("^(?:W/)?\"(\\d{1,18})\"$");

    private ETags() {
    }

    public static String of(long version) {
        return "\"" + version + "\"";
    }

    /** Parses {@code If-Match}; 428 when absent, 400 when not one of our tags. */
    public static long requireVersion(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "IF_MATCH_REQUIRED",
                    "Send the record's ETag in the If-Match header to update it");
        }
        Matcher matcher = VERSION_TAG.matcher(ifMatch.trim());
        if (!matcher.matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IF_MATCH", "If-Match must be a single ETag");
        }
        return Long.parseLong(matcher.group(1));
    }

    public static void checkCurrent(long expected, long actual) {
        if (expected != actual) {
            throw new ApiException(HttpStatus.PRECONDITION_FAILED, "STALE_VERSION",
                    "The record was changed by someone else. Reload it and try again.");
        }
    }
}
