package io.infranexum.server.rsot.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Dependency-light regressions for deterministic CLI parsing and output encoding. */
class RsotSchemaCliParserTest {
    @Test
    void parsesNamespacedOperationsFlagsAndBoundedPagination() {
        var args = RsotSchemaCli.Arguments.parse(new String[] {
                "rsot", "schema", "list", "--username", "admin", "--password-file", "/run/secrets/password",
                "--limit", "200", "--offset", "3", "--dry-run", "--format", "json"
        });
        assertEquals("rsot", args.namespace());
        assertEquals("schema", args.resource());
        assertEquals("list", args.operation());
        assertEquals(200, args.limit());
        assertEquals(3, args.offset());
        assertTrue(args.flag("dry-run"));
        assertTrue(args.json());
        assertFalse(args.has("missing"));
    }

    @Test
    void rejectsDuplicatesMissingValuesAndOutOfRangeInputs() {
        assertThrows(IllegalArgumentException.class, () -> RsotSchemaCli.Arguments.parse(new String[] {"rsot", "schema"}));
        assertThrows(IllegalArgumentException.class, () -> RsotSchemaCli.Arguments.parse(new String[] {"rsot", "schema", "list", "value"}));
        assertThrows(IllegalArgumentException.class, () -> RsotSchemaCli.Arguments.parse(new String[] {"rsot", "schema", "list", "--limit"}));
        assertThrows(IllegalArgumentException.class, () -> RsotSchemaCli.Arguments.parse(new String[] {"rsot", "schema", "list", "--limit", "1", "--limit", "2"}));
        var invalidLimit = RsotSchemaCli.Arguments.parse(new String[] {"rsot", "schema", "list", "--limit", "201"});
        assertThrows(IllegalArgumentException.class, invalidLimit::limit);
        var invalidRevision = RsotSchemaCli.Arguments.parse(new String[] {"rsot", "schema", "show", "--revision", "0"});
        assertThrows(IllegalArgumentException.class, invalidRevision::revision);
    }

    @Test
    void jsonEncodingEscapesControlsAndHelpDocumentsSafetyContract() {
        assertEquals("\"line\\n\\\"quoted\\\"\"", RsotSchemaCli.Json.write("line\n\"quoted\""));
        assertTrue(RsotSchemaCli.help().contains("--password-file"));
        assertTrue(RsotSchemaCli.help().contains("--definition-file"));
        assertTrue(RsotSchemaCli.help().contains("--dry-run"));
    }
}
