package com.verifi.support;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public final class Time {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private Time() {}

    public static String now() {
        return format(Instant.now());
    }

    public static String format(Instant instant) {
        return FMT.format(instant);
    }

    public static Instant parseStored(String value) {
        return Instant.parse(value);
    }

    public static Instant parseClient(String raw) {
        String value = raw.trim();
        if (value.length() >= 10 && value.charAt(4) == '-' && value.charAt(7) == '-' && value.length() == 10) {
            return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant();
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ex) {
            return OffsetDateTime.parse(value).toInstant();
        }
    }
}
