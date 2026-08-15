package io.infranexum.itam.compliance.domain;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/** Manufacturer warranty with immutable identity and versioned, evidenced verification history. */
public final class Warranty {
    private final DomainIdentifier id;
    private final DomainIdentifier assetId;
    private final DomainIdentifier manufacturerPartnerId;
    private final DomainIdentifier warrantyTypeId;
    private final String coverageLevel;
    private final LocalDate warrantyStartDate;
    private final LocalDate warrantyEndDate;
    private final LocalDate manufacturerSupportEndDate;
    private final String contractOrCertificateNumber;
    private final String proofReference;
    private final EvidenceSource source;
    private final ComplianceStatus status;
    private final Instant verifiedAt;
    private final DomainIdentifier verifiedBy;
    private final long version;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final DomainIdentifier createdBy;
    private final DomainIdentifier updatedBy;
    private final String lastReason;

    private Warranty(
            DomainIdentifier id, DomainIdentifier assetId, DomainIdentifier manufacturerPartnerId,
            DomainIdentifier warrantyTypeId, String coverageLevel, LocalDate warrantyStartDate,
            LocalDate warrantyEndDate, LocalDate manufacturerSupportEndDate, String contractOrCertificateNumber,
            String proofReference, EvidenceSource source, ComplianceStatus status, Instant verifiedAt,
            DomainIdentifier verifiedBy, long version, Instant createdAt, Instant updatedAt,
            DomainIdentifier createdBy, DomainIdentifier updatedBy, String lastReason) {
        this.id = Objects.requireNonNull(id, "id");
        this.assetId = Objects.requireNonNull(assetId, "assetId");
        this.manufacturerPartnerId = Objects.requireNonNull(manufacturerPartnerId, "manufacturerPartnerId");
        this.warrantyTypeId = Objects.requireNonNull(warrantyTypeId, "warrantyTypeId");
        this.coverageLevel = ComplianceTexts.text(coverageLevel, "coverageLevel", 2, 120);
        this.warrantyStartDate = Objects.requireNonNull(warrantyStartDate, "warrantyStartDate");
        this.warrantyEndDate = Objects.requireNonNull(warrantyEndDate, "warrantyEndDate");
        if (warrantyEndDate.isBefore(warrantyStartDate)) throw new IllegalArgumentException("warrantyEndDate precedes warrantyStartDate");
        this.manufacturerSupportEndDate = Objects.requireNonNull(manufacturerSupportEndDate, "manufacturerSupportEndDate");
        if (manufacturerSupportEndDate.isBefore(warrantyStartDate)) throw new IllegalArgumentException("manufacturerSupportEndDate precedes warrantyStartDate");
        this.contractOrCertificateNumber = ComplianceTexts.optional(contractOrCertificateNumber, "contractOrCertificateNumber", 160);
        this.proofReference = ComplianceTexts.text(proofReference, "proofReference", 2, 240);
        this.source = Objects.requireNonNull(source, "source");
        this.status = Objects.requireNonNull(status, "status");
        if (status.verifiedState() && (verifiedAt == null || verifiedBy == null)) {
            throw new IllegalArgumentException("verified warranty state requires verifier and timestamp");
        }
        this.verifiedAt = verifiedAt;
        this.verifiedBy = verifiedBy;
        if (version < 1) throw new IllegalArgumentException("version must be positive");
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("updatedAt precedes createdAt");
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
        this.updatedBy = Objects.requireNonNull(updatedBy, "updatedBy");
        this.lastReason = ComplianceTexts.text(lastReason, "lastReason", 2, 1024);
    }

    public static Warranty draft(
            DomainIdentifier id, DomainIdentifier assetId, DomainIdentifier manufacturerPartnerId,
            DomainIdentifier warrantyTypeId, String coverageLevel, LocalDate warrantyStartDate,
            LocalDate warrantyEndDate, LocalDate manufacturerSupportEndDate, String contractOrCertificateNumber,
            String proofReference, EvidenceSource source, DomainIdentifier actorId, String reason, Instant now) {
        return new Warranty(id, assetId, manufacturerPartnerId, warrantyTypeId, coverageLevel, warrantyStartDate,
                warrantyEndDate, manufacturerSupportEndDate, contractOrCertificateNumber, proofReference, source,
                ComplianceStatus.DRAFT, null, null, 1, now, now, actorId, actorId, reason);
    }

    public static Warranty restore(
            DomainIdentifier id, DomainIdentifier assetId, DomainIdentifier manufacturerPartnerId,
            DomainIdentifier warrantyTypeId, String coverageLevel, LocalDate warrantyStartDate,
            LocalDate warrantyEndDate, LocalDate manufacturerSupportEndDate, String contractOrCertificateNumber,
            String proofReference, EvidenceSource source, ComplianceStatus status, Instant verifiedAt,
            DomainIdentifier verifiedBy, long version, Instant createdAt, Instant updatedAt,
            DomainIdentifier createdBy, DomainIdentifier updatedBy, String lastReason) {
        return new Warranty(id, assetId, manufacturerPartnerId, warrantyTypeId, coverageLevel, warrantyStartDate,
                warrantyEndDate, manufacturerSupportEndDate, contractOrCertificateNumber, proofReference, source,
                status, verifiedAt, verifiedBy, version, createdAt, updatedAt, createdBy, updatedBy, lastReason);
    }

    public Warranty revise(
            DomainIdentifier manufacturerPartnerId, DomainIdentifier warrantyTypeId, String coverageLevel,
            LocalDate startDate, LocalDate endDate, LocalDate supportEndDate, String certificateNumber,
            String proofReference, EvidenceSource source, DomainIdentifier actorId, String reason, Instant now) {
        requireEditable();
        return new Warranty(id, assetId, manufacturerPartnerId, warrantyTypeId, coverageLevel, startDate, endDate,
                supportEndDate, certificateNumber, proofReference, source, ComplianceStatus.DRAFT, null, null,
                Math.addExact(version, 1), createdAt, now, createdBy, actorId, reason);
    }

    public Warranty activate(DomainIdentifier verifierId, String reason, Instant now) {
        if (status != ComplianceStatus.DRAFT) throw state("activate");
        return copy(ComplianceStatus.ACTIVE, now, verifierId, Math.addExact(version, 1), verifierId, reason, now);
    }

    public Warranty expire(DomainIdentifier actorId, String reason, Instant now, LocalDate today) {
        if (status != ComplianceStatus.ACTIVE) throw state("expire");
        if (!today.isAfter(warrantyEndDate)) throw new ComplianceConflictException("ITAM_WARRANTY_NOT_EXPIRED", "warranty end date has not passed");
        return copy(ComplianceStatus.EXPIRED, verifiedAt, verifiedBy, Math.addExact(version, 1), actorId, reason, now);
    }

    public Warranty cancel(DomainIdentifier actorId, String reason, Instant now) {
        if (status == ComplianceStatus.CANCELLED || status == ComplianceStatus.SUPERSEDED) throw state("cancel");
        return copy(ComplianceStatus.CANCELLED, verifiedAt, verifiedBy, Math.addExact(version, 1), actorId, reason, now);
    }

    public Warranty supersede(DomainIdentifier actorId, String reason, Instant now) {
        if (status != ComplianceStatus.ACTIVE && status != ComplianceStatus.EXPIRED) throw state("supersede");
        return copy(ComplianceStatus.SUPERSEDED, verifiedAt, verifiedBy, Math.addExact(version, 1), actorId, reason, now);
    }

    public boolean verifiedComplete() { return status.verifiedState() && verifiedAt != null && verifiedBy != null; }
    public boolean warrantyCovers(LocalDate date) {
        return verifiedComplete() && !date.isBefore(warrantyStartDate) && !date.isAfter(warrantyEndDate)
                && status != ComplianceStatus.CANCELLED && status != ComplianceStatus.SUPERSEDED;
    }

    private Warranty copy(ComplianceStatus target, Instant verified, DomainIdentifier verifier, long nextVersion,
            DomainIdentifier actorId, String reason, Instant now) {
        return new Warranty(id, assetId, manufacturerPartnerId, warrantyTypeId, coverageLevel, warrantyStartDate,
                warrantyEndDate, manufacturerSupportEndDate, contractOrCertificateNumber, proofReference, source,
                target, verified, verifier, nextVersion, createdAt, now, createdBy, actorId, reason);
    }
    private void requireEditable() {
        if (status != ComplianceStatus.DRAFT && status != ComplianceStatus.ACTIVE) throw state("revise");
    }
    private ComplianceConflictException state(String operation) {
        return new ComplianceConflictException("ITAM_WARRANTY_STATE_CONFLICT", "warranty cannot " + operation + " from " + status.wireValue());
    }

    public DomainIdentifier id(){return id;} public DomainIdentifier assetId(){return assetId;}
    public DomainIdentifier manufacturerPartnerId(){return manufacturerPartnerId;} public DomainIdentifier warrantyTypeId(){return warrantyTypeId;}
    public String coverageLevel(){return coverageLevel;} public LocalDate warrantyStartDate(){return warrantyStartDate;}
    public LocalDate warrantyEndDate(){return warrantyEndDate;} public LocalDate manufacturerSupportEndDate(){return manufacturerSupportEndDate;}
    public String contractOrCertificateNumber(){return contractOrCertificateNumber;} public String proofReference(){return proofReference;}
    public EvidenceSource source(){return source;} public ComplianceStatus status(){return status;} public Instant verifiedAt(){return verifiedAt;}
    public DomainIdentifier verifiedBy(){return verifiedBy;} public long version(){return version;} public Instant createdAt(){return createdAt;}
    public Instant updatedAt(){return updatedAt;} public DomainIdentifier createdBy(){return createdBy;} public DomainIdentifier updatedBy(){return updatedBy;}
    public String lastReason(){return lastReason;}
}
