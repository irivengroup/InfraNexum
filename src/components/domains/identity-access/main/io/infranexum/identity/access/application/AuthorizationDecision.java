package io.infranexum.identity.access.application;

import java.util.Objects;

/** Explainable RBAC decision returned to Server/API/CLI/Web enforcement points. */
public record AuthorizationDecision(boolean allowed, String code, String explanation) {
    public AuthorizationDecision { Objects.requireNonNull(code, "code"); Objects.requireNonNull(explanation, "explanation"); }
    public static AuthorizationDecision allow(String code, String explanation) { return new AuthorizationDecision(true, code, explanation); }
    public static AuthorizationDecision deny(String code, String explanation) { return new AuthorizationDecision(false, code, explanation); }
}
