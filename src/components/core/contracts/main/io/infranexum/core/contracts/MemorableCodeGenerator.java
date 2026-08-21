package io.infranexum.core.contracts;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;

/** Generates stable, human-readable aggregate codes from a business label and aggregate identity. */
public final class MemorableCodeGenerator {
    private static final int DEFAULT_MAX_LENGTH = 64;
    private static final int SUFFIX_LENGTH = 6;

    public String generate(String displayName, DomainIdentifier id) {
        return generate(displayName, id, DEFAULT_MAX_LENGTH);
    }

    /** Generates a code whose total length never exceeds the receiving contract. */
    public String generate(String displayName, DomainIdentifier id, int maxLength) {
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(id, "id");
        if (maxLength < SUFFIX_LENGTH + 2) throw new IllegalArgumentException("maxLength is too small");
        int maxBaseLength = maxLength - SUFFIX_LENGTH - 1;
        String base = asciiSlug(displayName);
        if (base.isBlank()) base = "ITEM";
        if (base.length() > maxBaseLength) {
            base = base.substring(0, maxBaseLength).replaceAll("-+$", "");
        }
        String compactId = id.toString().replace("-", "").toUpperCase(Locale.ROOT);
        String suffix = compactId.substring(compactId.length() - SUFFIX_LENGTH);
        return base + "-" + suffix;
    }

    private static String asciiSlug(String value) {
        String decomposed = Normalizer.normalize(value.strip(), Normalizer.Form.NFKD);
        StringBuilder out = new StringBuilder(decomposed.length());
        boolean separator = false;
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            if (c <= 127 && Character.isLetterOrDigit(c)) {
                if (separator && !out.isEmpty()) out.append('-');
                out.append(Character.toUpperCase(c));
                separator = false;
            } else if (Character.isWhitespace(c) || c == '-' || c == '_' || c == '/' || c == '.') {
                separator = !out.isEmpty();
            }
        }
        return out.toString();
    }
}
