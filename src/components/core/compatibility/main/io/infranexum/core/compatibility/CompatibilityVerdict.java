package io.infranexum.core.compatibility;

/** Result of comparing an already published contract with a candidate revision. */
public enum CompatibilityVerdict {
    COMPATIBLE,
    BREAKING,
    INDETERMINATE
}
