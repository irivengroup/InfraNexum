package io.infranexum.identity.access.domain;

/** Closed set of deterministic operators available to the ABAC language. */
public enum PolicyOperator {
    EQUALS,
    NOT_EQUALS,
    CONTAINS,
    EXISTS
}
