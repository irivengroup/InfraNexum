package io.infranexum.organization.domain;
import java.util.Locale;
/** Version-1 subdivision classification catalogue. */
public enum SubdivisionType { DEPARTMENT, SITE, FUNCTION, PROJECT, COST_CENTER;
    public String wireValue(){return name().toLowerCase(Locale.ROOT);} public static SubdivisionType parse(String value){return valueOf(value.strip().toUpperCase(Locale.ROOT));}
}
