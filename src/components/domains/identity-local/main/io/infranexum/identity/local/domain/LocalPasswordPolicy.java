package io.infranexum.identity.local.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Normative local-password policy: 12..128 characters and all four ASCII categories. */
public final class LocalPasswordPolicy {
    public static final int MIN_LENGTH = 12;
    public static final int MAX_LENGTH = 128;

    public void validate(char[] password) {
        Objects.requireNonNull(password, "password");
        List<String> violations = new ArrayList<>();
        if (password.length < MIN_LENGTH) violations.add("min_length");
        if (password.length > MAX_LENGTH) violations.add("max_length");
        boolean upper = false;
        boolean lower = false;
        boolean digit = false;
        boolean special = false;
        boolean control = false;
        for (char value : password) {
            if (value >= 'A' && value <= 'Z') upper = true;
            else if (value >= 'a' && value <= 'z') lower = true;
            else if (value >= '0' && value <= '9') digit = true;
            else if (Character.isISOControl(value)) control = true;
            else if (!Character.isWhitespace(value)) special = true;
        }
        if (!upper) violations.add("uppercase");
        if (!lower) violations.add("lowercase");
        if (!digit) violations.add("digit");
        if (!special) violations.add("special");
        if (control) violations.add("control_character");
        if (!violations.isEmpty()) throw new LocalPasswordPolicyException(violations);
    }
}
