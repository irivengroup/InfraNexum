package io.infranexum.itam.partner.domain;

import io.infranexum.core.contracts.DomainIdentifier;
import java.net.URI;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Aggregate root for manufacturers, publishers, suppliers and support partners. */
public final class Partner {
    private final DomainIdentifier id;
    private final DomainIdentifier governingOrganizationId;
    private final DomainIdentifier governingSubdivisionId;
    private final PartnerCode code;
    private final String legalName;
    private final String displayName;
    private final String countryCode;
    private final Set<PartnerRole> roles;
    private final PartnerAuthorizationStatus authorizationStatus;
    private final LocalDate validFrom;
    private final LocalDate validUntil;
    private final String officialWebsite;
    private final String supportPortal;
    private final List<String> aliases;
    private final List<PartnerExternalId> externalIds;
    private final List<PartnerAccreditation> accreditations;
    private final List<PartnerContact> contacts;
    private final long version;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final DomainIdentifier createdBy;
    private final DomainIdentifier updatedBy;
    private final String lastReason;

    private Partner(
            DomainIdentifier id, DomainIdentifier governingOrganizationId, DomainIdentifier governingSubdivisionId,
            PartnerCode code, String legalName, String displayName, String countryCode, Set<PartnerRole> roles,
            PartnerAuthorizationStatus authorizationStatus, LocalDate validFrom, LocalDate validUntil,
            String officialWebsite, String supportPortal, List<String> aliases, List<PartnerExternalId> externalIds,
            List<PartnerAccreditation> accreditations, List<PartnerContact> contacts, long version, Instant createdAt,
            Instant updatedAt, DomainIdentifier createdBy, DomainIdentifier updatedBy, String lastReason) {
        this.id = Objects.requireNonNull(id, "id");
        this.governingOrganizationId = Objects.requireNonNull(governingOrganizationId, "governingOrganizationId");
        this.governingSubdivisionId = governingSubdivisionId;
        this.code = Objects.requireNonNull(code, "code");
        this.legalName = text(legalName, "legalName", 2, 255);
        this.displayName = text(displayName, "displayName", 2, 255);
        this.countryCode = country(countryCode);
        this.roles = immutableRoles(roles);
        this.authorizationStatus = Objects.requireNonNull(authorizationStatus, "authorizationStatus");
        this.validFrom = Objects.requireNonNull(validFrom, "validFrom");
        if (validUntil != null && validUntil.isBefore(validFrom)) {
            throw new IllegalArgumentException("validUntil precedes validFrom");
        }
        this.validUntil = validUntil;
        this.officialWebsite = httpUri(officialWebsite, "officialWebsite");
        this.supportPortal = httpUri(supportPortal, "supportPortal");
        this.aliases = immutableAliases(aliases);
        this.externalIds = List.copyOf(Objects.requireNonNull(externalIds, "externalIds"));
        this.accreditations = List.copyOf(Objects.requireNonNull(accreditations, "accreditations"));
        this.contacts = List.copyOf(Objects.requireNonNull(contacts, "contacts"));
        if (version < 1) throw new IllegalArgumentException("version must be positive");
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("updatedAt precedes createdAt");
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
        this.updatedBy = Objects.requireNonNull(updatedBy, "updatedBy");
        this.lastReason = text(lastReason, "lastReason", 2, 1024);
    }

    public static Partner draft(
            DomainIdentifier id, DomainIdentifier organizationId, DomainIdentifier subdivisionId, PartnerCode code,
            String legalName, String displayName, String countryCode, Set<PartnerRole> roles, LocalDate validFrom,
            LocalDate validUntil, String officialWebsite, String supportPortal, List<String> aliases,
            List<PartnerExternalId> externalIds, List<PartnerAccreditation> accreditations, List<PartnerContact> contacts,
            DomainIdentifier actorId, String reason, Instant now) {
        return new Partner(id, organizationId, subdivisionId, code, legalName, displayName, countryCode, roles,
                PartnerAuthorizationStatus.DRAFT, validFrom, validUntil, officialWebsite, supportPortal, aliases,
                externalIds, accreditations, contacts, 1, now, now, actorId, actorId, reason);
    }

    public static Partner restore(
            DomainIdentifier id, DomainIdentifier organizationId, DomainIdentifier subdivisionId, PartnerCode code,
            String legalName, String displayName, String countryCode, Set<PartnerRole> roles,
            PartnerAuthorizationStatus status, LocalDate validFrom, LocalDate validUntil, String officialWebsite,
            String supportPortal, List<String> aliases, List<PartnerExternalId> externalIds,
            List<PartnerAccreditation> accreditations, List<PartnerContact> contacts, long version, Instant createdAt,
            Instant updatedAt, DomainIdentifier createdBy, DomainIdentifier updatedBy, String lastReason) {
        return new Partner(id, organizationId, subdivisionId, code, legalName, displayName, countryCode, roles, status,
                validFrom, validUntil, officialWebsite, supportPortal, aliases, externalIds, accreditations, contacts,
                version, createdAt, updatedAt, createdBy, updatedBy, lastReason);
    }

    public Partner submitApproval(DomainIdentifier actorId, String reason, Instant now) {
        return transition(PartnerAuthorizationStatus.PENDING_APPROVAL, actorId, reason, now);
    }

    public Partner authorize(DomainIdentifier actorId, String reason, Instant now, LocalDate today) {
        if (today.isBefore(validFrom) || (validUntil != null && today.isAfter(validUntil))) {
            throw new PartnerConflictException("PARTNER_AUTHORIZATION_PERIOD_INVALID",
                    "partner authorization period does not include the authorization date");
        }
        return transition(PartnerAuthorizationStatus.ACTIVE, actorId, reason, now);
    }

    public Partner suspend(DomainIdentifier actorId, String reason, Instant now) {
        return transition(PartnerAuthorizationStatus.SUSPENDED, actorId, reason, now);
    }

    public Partner reactivate(DomainIdentifier actorId, String reason, Instant now, LocalDate today) {
        if (today.isBefore(validFrom) || (validUntil != null && today.isAfter(validUntil))) {
            throw new PartnerConflictException("PARTNER_AUTHORIZATION_PERIOD_INVALID",
                    "partner authorization period does not include the reactivation date");
        }
        return transition(PartnerAuthorizationStatus.ACTIVE, actorId, reason, now);
    }

    public Partner retire(DomainIdentifier actorId, String reason, Instant now) {
        return transition(PartnerAuthorizationStatus.RETIRED, actorId, reason, now);
    }

    public boolean selectableOn(LocalDate date) {
        Objects.requireNonNull(date, "date");
        return authorizationStatus == PartnerAuthorizationStatus.ACTIVE
                && !date.isBefore(validFrom)
                && (validUntil == null || !date.isAfter(validUntil));
    }

    public String normalizedLegalName() { return normalizeIdentityText(legalName); }
    public Set<String> identityTokens() {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        tokens.add("name:" + countryCode + ":" + normalizedLegalName());
        for (String alias : aliases) tokens.add("alias:" + countryCode + ":" + normalizeIdentityText(alias));
        for (PartnerExternalId externalId : externalIds) tokens.add("external:" + externalId.identityToken());
        return Set.copyOf(tokens);
    }

    private Partner transition(PartnerAuthorizationStatus target, DomainIdentifier actorId, String reason, Instant now) {
        Objects.requireNonNull(target, "target"); Objects.requireNonNull(actorId, "actorId"); Objects.requireNonNull(now, "now");
        if (!authorizationStatus.canTransitionTo(target)) {
            throw new PartnerConflictException("PARTNER_STATE_CONFLICT",
                    "partner cannot transition from " + authorizationStatus.wireValue() + " to " + target.wireValue());
        }
        if (now.isBefore(updatedAt)) throw new IllegalArgumentException("transition time precedes current state");
        return new Partner(id, governingOrganizationId, governingSubdivisionId, code, legalName, displayName,
                countryCode, roles, target, validFrom, validUntil, officialWebsite, supportPortal, aliases, externalIds,
                accreditations, contacts, Math.addExact(version, 1), createdAt, now, createdBy, actorId,
                text(reason, "reason", 2, 1024));
    }

    private static Set<PartnerRole> immutableRoles(Set<PartnerRole> values) {
        Objects.requireNonNull(values, "roles");
        if (values.isEmpty()) throw new IllegalArgumentException("partner requires at least one role");
        return Set.copyOf(values);
    }
    private static List<String> immutableAliases(List<String> values) {
        Objects.requireNonNull(values, "aliases"); LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : values) unique.add(text(value, "alias", 2, 255));
        if (unique.size() > 64) throw new IllegalArgumentException("too many partner aliases");
        return List.copyOf(unique);
    }
    private static String text(String value, String field, int min, int max) {
        Objects.requireNonNull(value, field); String result = value.strip();
        if (result.length() < min || result.length() > max || result.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return result;
    }
    private static String country(String value) {
        Objects.requireNonNull(value, "countryCode"); String result = value.strip().toUpperCase(Locale.ROOT);
        if (result.length() != 2 || !Set.of(Locale.getISOCountries()).contains(result)) {
            throw new IllegalArgumentException("invalid countryCode");
        }
        return result;
    }
    private static String httpUri(String value, String field) {
        if (value == null || value.isBlank()) return null;
        String result = value.strip();
        if (result.length() > 2048) throw new IllegalArgumentException(field + " is too long");
        URI uri = URI.create(result);
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))
                || uri.getHost() == null || uri.getUserInfo() != null) throw new IllegalArgumentException("invalid " + field);
        return result;
    }
    private static String normalizeIdentityText(String value) {
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", " ")
                .strip();
        return decomposed.replaceAll("\\s+", " ");
    }

    public DomainIdentifier id() { return id; }
    public DomainIdentifier governingOrganizationId() { return governingOrganizationId; }
    public DomainIdentifier governingSubdivisionId() { return governingSubdivisionId; }
    public PartnerCode code() { return code; }
    public String legalName() { return legalName; }
    public String displayName() { return displayName; }
    public String countryCode() { return countryCode; }
    public Set<PartnerRole> roles() { return roles; }
    public PartnerAuthorizationStatus authorizationStatus() { return authorizationStatus; }
    public LocalDate validFrom() { return validFrom; }
    public LocalDate validUntil() { return validUntil; }
    public String officialWebsite() { return officialWebsite; }
    public String supportPortal() { return supportPortal; }
    public List<String> aliases() { return aliases; }
    public List<PartnerExternalId> externalIds() { return externalIds; }
    public List<PartnerAccreditation> accreditations() { return accreditations; }
    public List<PartnerContact> contacts() { return contacts; }
    public long version() { return version; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public DomainIdentifier createdBy() { return createdBy; }
    public DomainIdentifier updatedBy() { return updatedBy; }
    public String lastReason() { return lastReason; }
}
