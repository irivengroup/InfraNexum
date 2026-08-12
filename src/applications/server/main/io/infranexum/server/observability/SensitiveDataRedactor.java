package io.infranexum.server.observability;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic, fail-closed redaction for diagnostic text crossing observability boundaries.
 *
 * <p>The policy intentionally does not attempt to recognize arbitrary business data. It protects
 * explicit credential-bearing fields and common credential encodings that can appear inside
 * messages, stack traces, MDC values, URLs or RFC problem details. The implementation is pure JDK
 * so the exact same policy can be exercised by the offline validation harness.
 */
public final class SensitiveDataRedactor {
    public static final String REDACTED = "[REDACTED]";

    private static final Set<String> SENSITIVE_FIELD_NAMES = Set.of(
            "password",
            "passwd",
            "pwd",
            "secret",
            "token",
            "accesstoken",
            "refreshtoken",
            "idtoken",
            "apikey",
            "authorization",
            "proxyauthorization",
            "cookie",
            "setcookie",
            "credential",
            "credentials",
            "clientsecret",
            "privatekey",
            "databasepassword",
            "datasourcepassword",
            "dbpassword");

    private static final Pattern HEADER_SECRET = Pattern.compile(
            "(?im)\\b(authorization|proxy-authorization|cookie|set-cookie)\\s*[:=]\\s*[^\\r\\n]*");
    private static final Pattern KEY_VALUE_SECRET = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_])"
                    + "([A-Za-z0-9_.-]*(?:password|passwd|pwd|secret|token|api[_-]?key|apikey|"
                    + "credential(?:s)?|private[_-]?key))"
                    + "(\\s*[:=]\\s*)"
                    + "(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;&]+)");
    private static final Pattern AUTH_SCHEME =
            Pattern.compile("(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern URI_USERINFO = Pattern.compile(
            "(?i)(\\b[a-z][a-z0-9+.-]*://[^:/@\\s]+:)([^@/\\s]+)(@)");
    private static final Pattern JWT = Pattern.compile(
            "(?<![A-Za-z0-9_-])eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+(?![A-Za-z0-9_-])");
    private static final Pattern PRIVATE_KEY = Pattern.compile(
            "(?s)-----BEGIN(?: [A-Z0-9]+)* PRIVATE KEY-----.*?-----END(?: [A-Z0-9]+)* PRIVATE KEY-----");

    /** Redacts a value whose structured field path is known. */
    public String redact(String fieldPath, String value) {
        if (value == null) {
            return null;
        }
        if (isSensitiveField(fieldPath)) {
            return REDACTED;
        }
        return redact(value);
    }

    /** Redacts credential encodings found inside arbitrary diagnostic text. */
    public String redact(String value) {
        if (value == null || value.isEmpty() || !needsContentScan(value)) {
            return value;
        }
        String redacted = PRIVATE_KEY.matcher(value).replaceAll(REDACTED);
        redacted = URI_USERINFO.matcher(redacted).replaceAll("$1" + REDACTED + "$3");
        redacted = replaceHeaderSecrets(redacted);
        redacted = replaceKeyValueSecrets(redacted);
        redacted = AUTH_SCHEME.matcher(redacted).replaceAll("$1 " + REDACTED);
        return JWT.matcher(redacted).replaceAll(REDACTED);
    }

    private static boolean needsContentScan(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("password")
                || lower.contains("passwd")
                || lower.contains("pwd=")
                || lower.contains("pwd:")
                || lower.contains("secret")
                || lower.contains("token")
                || lower.contains("api_key")
                || lower.contains("api-key")
                || lower.contains("apikey")
                || lower.contains("credential")
                || lower.contains("authorization")
                || lower.contains("cookie")
                || lower.contains("bearer ")
                || lower.contains("basic ")
                || lower.contains("://")
                || lower.contains("private key")
                || value.contains("eyJ");
    }

    private static String replaceHeaderSecrets(String value) {
        Matcher matcher = HEADER_SECRET.matcher(value);
        StringBuffer output = new StringBuffer(value.length());
        while (matcher.find()) {
            String matched = matcher.group();
            int separator = Math.max(matched.indexOf(':'), matched.indexOf('='));
            String name = separator >= 0 ? matched.substring(0, separator + 1) : matched;
            matcher.appendReplacement(output, Matcher.quoteReplacement(name + " " + REDACTED));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private static String replaceKeyValueSecrets(String value) {
        Matcher matcher = KEY_VALUE_SECRET.matcher(value);
        StringBuffer output = new StringBuffer(value.length());
        while (matcher.find()) {
            String replacement = matcher.group(1) + matcher.group(2) + REDACTED;
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private static boolean isSensitiveField(String fieldPath) {
        if (fieldPath == null || fieldPath.isBlank()) {
            return false;
        }
        String[] components = fieldPath.split("[.\\[\\]]+");
        for (String component : components) {
            String normalized = component.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            if (SENSITIVE_FIELD_NAMES.contains(normalized)
                    || normalized.endsWith("password")
                    || normalized.endsWith("passwd")
                    || normalized.endsWith("secret")
                    || normalized.endsWith("token")
                    || normalized.endsWith("apikey")
                    || normalized.endsWith("credential")
                    || normalized.endsWith("credentials")
                    || normalized.endsWith("privatekey")) {
                return true;
            }
        }
        return false;
    }
}
