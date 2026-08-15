package io.infranexum.server.configuration;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;

/**
 * Converts human-entered temporal values at the Server boundary.
 *
 * <p>Offset/zoned values preserve their explicit timezone. Local date-time values produced by
 * browser calendar controls are resolved in the Server timezone. Ambiguous or nonexistent local
 * times around daylight-saving transitions are rejected rather than silently shifted.
 */
public final class ServerTemporalInputParser {
    private final ZoneId serverZone;

    public ServerTemporalInputParser(ZoneId serverZone) {
        this.serverZone = Objects.requireNonNull(serverZone, "serverZone");
    }

    /** Returns the timezone used when a request omits an explicit offset or zone. */
    public ZoneId serverZone() {
        return serverZone;
    }

    /**
     * Parses an optional date-time into an absolute instant.
     *
     * @param raw client value, or {@code null}/blank for no value
     * @param fieldName stable field name used in validation errors
     * @return resolved instant, or {@code null} when the input is absent
     * @throws IllegalArgumentException when the value is malformed or a local wall time is unsafe
     */
    public Instant optionalInstant(String raw, String fieldName) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.strip();
        String field = requireFieldName(fieldName);

        Instant explicit = parseExplicitInstant(value);
        if (explicit != null) {
            return explicit;
        }

        final LocalDateTime local;
        try {
            local = LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException failure) {
            throw new IllegalArgumentException(field + " must be an ISO-8601 date-time", failure);
        }

        List<ZoneOffset> offsets = serverZone.getRules().getValidOffsets(local);
        if (offsets.isEmpty()) {
            throw new IllegalArgumentException(field + " is not a valid local time in Server timezone " + serverZone);
        }
        if (offsets.size() != 1) {
            throw new IllegalArgumentException(field + " is ambiguous in Server timezone " + serverZone + "; provide an explicit offset");
        }
        return local.toInstant(offsets.get(0));
    }

    /** Parses a calendar-only value without inventing a time-of-day. */
    public LocalDate optionalDate(String raw, String fieldName) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String field = requireFieldName(fieldName);
        try {
            return LocalDate.parse(raw.strip(), DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException failure) {
            throw new IllegalArgumentException(field + " must be an ISO-8601 calendar date", failure);
        }
    }

    private static Instant parseExplicitInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // Continue with richer ISO-8601 forms.
        }
        try {
            return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException ignored) {
            // Continue with a named zone form.
        }
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.ISO_ZONED_DATE_TIME).toInstant();
        } catch (DateTimeException ignored) {
            return null;
        }
    }

    private static String requireFieldName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("fieldName must not be blank");
        }
        return value.strip();
    }
}
