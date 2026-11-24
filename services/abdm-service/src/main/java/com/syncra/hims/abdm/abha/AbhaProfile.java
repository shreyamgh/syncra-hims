package com.syncra.hims.abdm.abha;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;

/**
 * A verified ABHA profile as returned by ABDM, for pre-filling registration. The photo is
 * deliberately not carried.
 *
 * @param abhaNumber 14 digits
 */
public record AbhaProfile(
        String abhaNumber,
        String abhaAddress,
        String name,
        String firstName,
        String middleName,
        String lastName,
        String gender,
        Integer yearOfBirth,
        Integer monthOfBirth,
        Integer dayOfBirth,
        String mobile,
        String address,
        String districtName,
        String stateName,
        String pincode,
        String status,
        Boolean kycVerified) {

    /** Full date of birth when ABDM gave day, month and year. */
    public LocalDate dateOfBirth() {
        if (yearOfBirth == null || monthOfBirth == null || dayOfBirth == null) {
            return null;
        }
        try {
            return LocalDate.of(yearOfBirth, monthOfBirth, dayOfBirth);
        } catch (java.time.DateTimeException e) {
            return null;
        }
    }

    public boolean isActive() {
        return status == null || "ACTIVE".equalsIgnoreCase(status);
    }

    /** Reads ABHA's profile JSON; field names vary slightly between ABHA endpoints. */
    public static AbhaProfile fromJson(JsonNode node) {
        String first = text(node, "firstName");
        String middle = text(node, "middleName");
        String last = text(node, "lastName");
        String name = text(node, "name");
        if (name == null) {
            name = String.join(" ", java.util.stream.Stream.of(first, middle, last)
                    .filter(s -> s != null && !s.isBlank()).toList());
        }
        Integer year = integer(node, "yearOfBirth");
        Integer month = integer(node, "monthOfBirth");
        Integer day = integer(node, "dayOfBirth");
        String dob = text(node, "dateOfBirth") != null ? text(node, "dateOfBirth") : text(node, "dob");
        if (year == null && dob != null && dob.matches("\\d{2}-\\d{2}-\\d{4}")) {
            day = Integer.valueOf(dob.substring(0, 2));
            month = Integer.valueOf(dob.substring(3, 5));
            year = Integer.valueOf(dob.substring(6));
        }
        String number = AbhaNumbers.digits(text(node, "ABHANumber") != null ? text(node, "ABHANumber")
                : text(node, "abhaNumber"));
        String address = AbhaNumbers.address(text(node, "preferredAbhaAddress") != null
                ? text(node, "preferredAbhaAddress") : text(node, "abhaAddress"));
        if (address == null && node.path("phrAddress").isArray() && !node.path("phrAddress").isEmpty()) {
            address = AbhaNumbers.address(node.path("phrAddress").get(0).asText());
        }
        String mobile = text(node, "mobile");
        return new AbhaProfile(number, address, name, first, middle, last, gender(text(node, "gender")),
                year, month, day, mobile != null && mobile.matches("^[6-9][0-9]{9}$") ? mobile : null,
                text(node, "address"), text(node, "districtName"), text(node, "stateName"),
                pincode(text(node, "pincode") != null ? text(node, "pincode") : text(node, "pinCode")),
                text(node, "abhaStatus") != null ? text(node, "abhaStatus")
                : text(node, "status"), node.hasNonNull("kycVerified") ? node.get("kycVerified").asBoolean() : null);
    }

    static String gender(String value) {
        if (value == null) {
            return "U";
        }
        return switch (value.trim().toUpperCase()) {
            case "M", "MALE" -> "M";
            case "F", "FEMALE" -> "F";
            case "O", "OTHER", "T", "TRANSGENDER" -> "O";
            default -> "U";
        };
    }

    private static String pincode(String value) {
        return value != null && value.matches("^[1-9][0-9]{5}$") ? value : null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }

    private static Integer integer(JsonNode node, String field) {
        String text = text(node, field);
        if (text == null || !text.matches("\\d{1,4}")) {
            return null;
        }
        int value = Integer.parseInt(text);
        return value == 0 ? null : value;
    }
}
