package io.infranexum.core.capabilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import org.junit.jupiter.api.Test;

/** Covers RFC4180 parser edge cases that catalog-level tests do not naturally exercise. */
class CsvTableCoverageTest {
    @Test
    void parserHandlesFinalQuotedFieldEmptyTokensAndEmbeddedQuoteCharacters() {
        assertEquals("x", CsvTable.read(new StringReader("a\n\"x\"")).getFirst().get("a"));
        assertEquals("a\"b", CsvTable.read(new StringReader("h\na\"b\n")).getFirst().get("h"));
        assertEquals("", CsvTable.read(new StringReader("a,b,c\n1,,3\n")).getFirst().get("b"));
        assertEquals("line1\nline2", CsvTable.read(new StringReader("a\n\"line1\nline2\"\n")).getFirst().get("a"));
    }

    @Test
    void parserRejectsBlankHeadersAndPropagatesReaderIoFailures() {
        assertThrows(IllegalArgumentException.class, () -> CsvTable.read(new StringReader(",b\n1,2\n")));
        assertThrows(IllegalArgumentException.class, () -> CsvTable.read(new FailingReader()));
    }

    /** Reader without mark/reset support exercises CsvTable's buffering boundary. */
    private static final class FailingReader extends Reader {
        @Override
        public int read(char[] buffer, int offset, int length) throws IOException {
            throw new IOException("synthetic read failure");
        }

        @Override
        public void close() {}

        @Override
        public boolean markSupported() {
            return false;
        }
    }
}
