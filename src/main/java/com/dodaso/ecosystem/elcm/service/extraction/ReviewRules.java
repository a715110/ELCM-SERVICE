package com.dodaso.ecosystem.elcm.service.extraction;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * Rules for a value a reviewer types in. Pure functions, no database. The data type codes are the
 * lkp_field_data_type codes seeded by extraction_wiring.sql; an unknown code is not checked.
 */
public final class ReviewRules {

    public static final int MAX_LENGTH = 2000;
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");

    private ReviewRules() {
    }

    /** Blank becomes null (meaning: no correction). Surrounding spaces are removed. */
    public static String normalize(final String value) {
        if (value == null) {
            return null;
        }
        final String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Null when the value is acceptable for the type, otherwise a message for the reviewer. */
    public static String validate(final String dataTypeCode, final String value) {
        if (value == null) {
            return null;
        }
        if (value.length() > MAX_LENGTH) {
            return "The value is longer than " + MAX_LENGTH + " characters.";
        }
        if (dataTypeCode == null) {
            return null;
        }
        switch (dataTypeCode) {
            case "NUMBER":
                return NUMBER.matcher(value).matches() ? null
                    : "Enter a plain number, for example 15500 or 3.5 (no currency symbol or commas).";
            case "DATE":
                try {
                    LocalDate.parse(value);
                    return null;
                } catch (final DateTimeParseException e) {
                    return "Enter the date as yyyy-mm-dd, for example 2026-04-01.";
                }
            case "BOOLEAN":
                return "true".equals(value) || "false".equals(value) ? null : "Enter true or false.";
            default:
                return null;
        }
    }
}
