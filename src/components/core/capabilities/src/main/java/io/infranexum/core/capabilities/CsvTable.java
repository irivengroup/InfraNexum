package io.infranexum.core.capabilities;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal RFC 4180 reader used to keep Core independent from JSON/CSV libraries. */
final class CsvTable {
    private CsvTable() {}

    static List<Map<String, String>> read(Path path) {
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return read(reader);
        } catch (IOException error) {
            throw new IllegalArgumentException("cannot read CSV catalogue: " + path, error);
        }
    }

    static List<Map<String, String>> read(Reader reader) {
        try {
            Reader source = reader.markSupported() ? reader : new BufferedReader(reader);
            List<List<String>> rows = parse(source);
            if (rows.isEmpty()) {
                throw new IllegalArgumentException("CSV catalogue is empty");
            }
            List<String> header = rows.getFirst();
            if (header.isEmpty() || header.stream().anyMatch(String::isBlank)) {
                throw new IllegalArgumentException("CSV header contains a blank column");
            }
            if (header.stream().distinct().count() != header.size()) {
                throw new IllegalArgumentException("CSV header contains duplicate columns");
            }
            List<Map<String, String>> result = new ArrayList<>();
            for (int rowIndex = 1; rowIndex < rows.size(); rowIndex++) {
                List<String> row = rows.get(rowIndex);
                if (row.size() != header.size()) {
                    throw new IllegalArgumentException("CSV row " + (rowIndex + 1) + " has " + row.size()
                            + " columns; expected " + header.size());
                }
                Map<String, String> mapped = new LinkedHashMap<>();
                for (int column = 0; column < header.size(); column++) {
                    mapped.put(header.get(column), row.get(column));
                }
                result.add(Map.copyOf(mapped));
            }
            return List.copyOf(result);
        } catch (IOException error) {
            throw new IllegalArgumentException("cannot parse CSV catalogue", error);
        }
    }

    private static List<List<String>> parse(Reader reader) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean sawAny = false;
        int current;
        while ((current = reader.read()) != -1) {
            sawAny = true;
            char character = (char) current;
            if (quoted) {
                if (character == '"') {
                    reader.mark(1);
                    int next = reader.read();
                    if (next == '"') {
                        field.append('"');
                    } else {
                        quoted = false;
                        if (next != -1) {
                            reader.reset();
                        }
                    }
                } else {
                    field.append(character);
                }
            } else if (character == '"' && field.isEmpty()) {
                quoted = true;
            } else if (character == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (character == '\n') {
                row.add(stripCarriageReturn(field.toString()));
                rows.add(List.copyOf(row));
                row.clear();
                field.setLength(0);
            } else {
                field.append(character);
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("unterminated quoted CSV field");
        }
        if (sawAny && (!row.isEmpty() || !field.isEmpty())) {
            row.add(stripCarriageReturn(field.toString()));
            rows.add(List.copyOf(row));
        }
        return rows;
    }

    private static String stripCarriageReturn(String value) {
        return value.endsWith("\r") ? value.substring(0, value.length() - 1) : value;
    }
}
