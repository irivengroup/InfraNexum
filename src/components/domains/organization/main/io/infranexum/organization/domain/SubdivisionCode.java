package io.infranexum.organization.domain;

import java.util.Locale; import java.util.Objects; import java.util.regex.Pattern;
/** Immutable organization-local subdivision code. */
public record SubdivisionCode(String value) implements Comparable<SubdivisionCode> {
    private static final Pattern FORMAT=Pattern.compile("[A-Z0-9][A-Z0-9-]{2,31}");
    public SubdivisionCode { Objects.requireNonNull(value,"value"); value=value.strip().toUpperCase(Locale.ROOT); if(!FORMAT.matcher(value).matches()) throw new IllegalArgumentException("invalid subdivision code"); }
    @Override public int compareTo(SubdivisionCode other){return value.compareTo(Objects.requireNonNull(other,"other").value);} @Override public String toString(){return value;}
}
