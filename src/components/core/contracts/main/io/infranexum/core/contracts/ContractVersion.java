package io.infranexum.core.contracts;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Semantic version attached to a public domain contract pack. */
public record ContractVersion(int major, int minor, int patch) implements Comparable<ContractVersion> {
    private static final Pattern FORMAT = Pattern.compile("(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)");

    public ContractVersion {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("contract version components must be non-negative");
        }
    }

    /** Parses the canonical three-component semantic version form. */
    public static ContractVersion parse(String value) {
        Objects.requireNonNull(value, "value");
        Matcher matcher = FORMAT.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("invalid contract version: " + value);
        }
        return new ContractVersion(
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)));
    }

    /** Returns whether this reader can consume a contract produced at {@code produced}. */
    public boolean canRead(ContractVersion produced) {
        Objects.requireNonNull(produced, "produced");
        return major == produced.major && compareTo(produced) >= 0;
    }

    @Override
    public int compareTo(ContractVersion other) {
        Objects.requireNonNull(other, "other");
        int byMajor = Integer.compare(major, other.major);
        if (byMajor != 0) return byMajor;
        int byMinor = Integer.compare(minor, other.minor);
        return byMinor != 0 ? byMinor : Integer.compare(patch, other.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
