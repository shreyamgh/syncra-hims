package com.syncra.hims.abdm.callback;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Tolerant readers for ABDM callback bodies (field presence and formats vary between ABDM releases). */
public final class Json {

    private Json() {
    }

    /** Text at a dotted path ({@code "hiRequest.consent.id"}), or null when absent or blank. */
    public static String text(JsonNode node, String path) {
        JsonNode current = at(node, path);
        if (current == null || current.isNull() || current.isMissingNode() || current.isContainerNode()) {
            return null;
        }
        String value = current.asText().trim();
        return value.isEmpty() ? null : value;
    }

    public static JsonNode at(JsonNode node, String path) {
        JsonNode current = node;
        for (String field : path.split("\\.")) {
            if (current == null) {
                return null;
            }
            current = current.get(field);
        }
        return current;
    }

    public static UUID uuid(JsonNode node, String path) {
        String value = text(node, path);
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** ABDM timestamps: ISO-8601 with an offset, or a local date-time meaning UTC. */
    public static Instant instant(JsonNode node, String path) {
        String value = text(node, path);
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException e) {
                return null;
            }
        }
    }

    public static List<String> texts(JsonNode node, String path) {
        JsonNode array = at(node, path);
        List<String> values = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(v -> {
                if (v.isTextual() && !v.asText().isBlank()) {
                    values.add(v.asText().trim());
                }
            });
        }
        return values;
    }

    public static boolean present(JsonNode node, String path) {
        JsonNode value = at(node, path);
        return value != null && !value.isNull() && !value.isMissingNode();
    }
}
