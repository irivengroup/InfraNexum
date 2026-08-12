package io.infranexum.organization.domain;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Currency;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Immutable aggregate root owning organization identity, lifecycle and scope boundary. */
public final class Organization {
    private static final Set<String> SUPPORTED_LANGUAGES = Set.of("de", "en", "es", "fr", "it");
    private final DomainIdentifier id;
    private final OrganizationCode code;
    private final String displayName;
    private final String legalName;
    private final String countryCode;
    private final String defaultLanguage;
    private final String timezone;
    private final String currency;
    private final DomainIdentifier parentOrganizationId;
    private final OrganizationState state;
    private final long version;
    private final Instant createdAt;
    private final Instant updatedAt;

    private Organization(DomainIdentifier id, OrganizationCode code, String displayName, String legalName,
            String countryCode, String defaultLanguage, String timezone, String currency,
            DomainIdentifier parentOrganizationId, OrganizationState state, long version,
            Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.code = Objects.requireNonNull(code, "code");
        this.displayName = text(displayName, "displayName", 2, 160);
        this.legalName = text(legalName, "legalName", 2, 255);
        this.countryCode = country(countryCode);
        this.defaultLanguage = language(defaultLanguage);
        this.timezone = zone(timezone);
        this.currency = currency(currency);
        this.parentOrganizationId = parentOrganizationId;
        this.state = Objects.requireNonNull(state, "state");
        if (version < 0) throw new IllegalArgumentException("version must be non-negative");
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("updatedAt precedes createdAt");
        if (parentOrganizationId != null && parentOrganizationId.equals(id)) throw new IllegalArgumentException("organization cannot parent itself");
    }

    public static Organization provisioning(DomainIdentifier id, OrganizationCode code, String displayName, String legalName,
            String countryCode, String defaultLanguage, String timezone, String currency,
            DomainIdentifier parentOrganizationId, Instant now) {
        return new Organization(id, code, displayName, legalName, countryCode, defaultLanguage, timezone, currency,
                parentOrganizationId, OrganizationState.PROVISIONING, 0, now, now);
    }

    public static Organization restore(DomainIdentifier id, OrganizationCode code, String displayName, String legalName,
            String countryCode, String defaultLanguage, String timezone, String currency,
            DomainIdentifier parentOrganizationId, OrganizationState state, long version, Instant createdAt, Instant updatedAt) {
        return new Organization(id, code, displayName, legalName, countryCode, defaultLanguage, timezone, currency,
                parentOrganizationId, state, version, createdAt, updatedAt);
    }

    public Organization activate(Instant now) { return transition(OrganizationState.ACTIVE, now); }
    public Organization suspend(Instant now) { return transition(OrganizationState.SUSPENDED, now); }
    public Organization resume(Instant now) { return transition(OrganizationState.ACTIVE, now); }
    public Organization beginArchiving(Instant now) { return transition(OrganizationState.ARCHIVING, now); }
    public Organization completeArchiving(Instant now) { return transition(OrganizationState.ARCHIVED, now); }
    public Organization requestDeletion(Instant now) { return transition(OrganizationState.DELETION_PENDING, now); }
    public Organization markDeleted(Instant now) { return transition(OrganizationState.DELETED, now); }

    private Organization transition(OrganizationState target, Instant now) {
        Objects.requireNonNull(now, "now");
        if (!state.canTransitionTo(target)) throw new OrganizationStateException(state, target);
        if (now.isBefore(updatedAt)) throw new IllegalArgumentException("transition time precedes current state");
        return new Organization(id, code, displayName, legalName, countryCode, defaultLanguage, timezone, currency,
                parentOrganizationId, target, Math.addExact(version, 1L), createdAt, now);
    }

    private static String text(String value, String field, int min, int max) {
        Objects.requireNonNull(value, field); String v=value.strip();
        if (v.length()<min || v.length()>max || v.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("invalid "+field);
        return v;
    }
    private static String country(String value) {
        Objects.requireNonNull(value,"countryCode"); String v=value.strip().toUpperCase(Locale.ROOT);
        if (v.length()!=2 || !Set.of(Locale.getISOCountries()).contains(v)) throw new IllegalArgumentException("invalid countryCode"); return v;
    }
    private static String language(String value) {
        Objects.requireNonNull(value,"defaultLanguage"); String v=value.strip(); Locale locale=Locale.forLanguageTag(v);
        if (locale.getLanguage().isBlank() || !SUPPORTED_LANGUAGES.contains(locale.getLanguage())) throw new IllegalArgumentException("unsupported defaultLanguage"); return locale.toLanguageTag();
    }
    private static String zone(String value) { Objects.requireNonNull(value,"timezone"); String v=value.strip(); ZoneId.of(v); return v; }
    private static String currency(String value) { Objects.requireNonNull(value,"currency"); String v=value.strip().toUpperCase(Locale.ROOT); Currency.getInstance(v); return v; }

    public DomainIdentifier id(){return id;} public OrganizationCode code(){return code;} public String displayName(){return displayName;}
    public String legalName(){return legalName;} public String countryCode(){return countryCode;} public String defaultLanguage(){return defaultLanguage;}
    public String timezone(){return timezone;} public String currency(){return currency;} public DomainIdentifier parentOrganizationId(){return parentOrganizationId;}
    public OrganizationState state(){return state;} public long version(){return version;} public Instant createdAt(){return createdAt;} public Instant updatedAt(){return updatedAt;}
}
