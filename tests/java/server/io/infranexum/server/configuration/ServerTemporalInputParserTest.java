package io.infranexum.server.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** Regression coverage for calendar/date-time input conversion at the Server boundary. */
class ServerTemporalInputParserTest {
    private final ServerTemporalInputParser parser = new ServerTemporalInputParser(ZoneId.of("Europe/Paris"));

    @Test
    void explicitOffsetAndZoneRemainAuthoritative() {
        assertEquals(Instant.parse("2026-08-14T12:00:00Z"), parser.optionalInstant("2026-08-14T12:00:00Z", "effectiveFrom"));
        assertEquals(Instant.parse("2026-08-14T12:00:00Z"), parser.optionalInstant("2026-08-14T14:00:00+02:00", "effectiveFrom"));
        assertEquals(Instant.parse("2026-08-14T12:00:00Z"), parser.optionalInstant("2026-08-14T14:00:00+02:00[Europe/Paris]", "effectiveFrom"));
    }

    @Test
    void timezoneLessDateTimeUsesServerTimezoneNotBrowserTimezone() {
        assertEquals(Instant.parse("2026-08-14T12:00:00Z"), parser.optionalInstant("2026-08-14T14:00", "effectiveFrom"));
        assertEquals(Instant.parse("2026-08-14T12:00:37Z"), parser.optionalInstant("2026-08-14T14:00:37", "effectiveFrom"));
        assertEquals(ZoneId.of("Europe/Paris"), parser.serverZone());
    }

    @Test
    void missingTemporalValuesRemainAbsent() {
        assertNull(parser.optionalInstant(null, "effectiveFrom"));
        assertNull(parser.optionalInstant("   ", "effectiveFrom"));
        assertNull(parser.optionalDate(null, "warrantyEndDate"));
    }

    @Test
    void dateOnlyValuesStayCalendarDates() {
        assertEquals(LocalDate.of(2026, 8, 14), parser.optionalDate("2026-08-14", "warrantyEndDate"));
        assertThrows(IllegalArgumentException.class, () -> parser.optionalDate("14/08/2026", "warrantyEndDate"));
    }

    @Test
    void invalidAmbiguousAndNonexistentLocalTimesFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> parser.optionalInstant("14/08/2026 14:00", "effectiveFrom"));
        assertThrows(IllegalArgumentException.class, () -> parser.optionalInstant("2026-03-29T02:30", "effectiveFrom"));
        assertThrows(IllegalArgumentException.class, () -> parser.optionalInstant("2026-10-25T02:30", "effectiveFrom"));
    }

    @Test
    void explicitOffsetResolvesAnOtherwiseAmbiguousWallTime() {
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), parser.optionalInstant("2026-10-25T02:30:00+02:00", "effectiveFrom"));
    }

    @Test
    void fieldNameMustBeStableForValidationDiagnostics() {
        assertThrows(IllegalArgumentException.class, () -> parser.optionalInstant("2026-08-14T14:00", " "));
    }
}
