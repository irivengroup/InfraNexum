package io.infranexum.server.http;

/** Shared servlet request attribute keys established by authentication boundaries. */
public final class AuthenticatedActorContext {
    public static final String ACCOUNT_ATTRIBUTE = AuthenticatedActorContext.class.getName() + ".account";

    private AuthenticatedActorContext() {
        throw new AssertionError("no instances");
    }
}
