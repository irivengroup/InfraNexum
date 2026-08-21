package io.infranexum.itam.compliance.application;

import io.infranexum.core.contracts.ContractVersion;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.contracts.MemorableCodeGenerator;
import io.infranexum.core.contracts.OffsetPage;
import io.infranexum.core.contracts.PaginationConstraints;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.core.events.EventEnvelope;
import io.infranexum.core.events.EventSource;
import io.infranexum.core.events.EventType;
import io.infranexum.core.events.TransactionExecutionException;
import io.infranexum.core.events.TransactionalEventStore;
import io.infranexum.itam.asset.domain.Asset;
import io.infranexum.itam.asset.domain.AssetType;
import io.infranexum.itam.asset.ports.AssetRepository;
import io.infranexum.itam.compliance.domain.*;
import io.infranexum.itam.compliance.ports.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * PGM-07-E03 application service for warranty, third-party support, software licensing and supportability deadlines.
 *
 * <p>All mutations are idempotent, optimistic and transactional with the Core Events outbox. Raw software
 * license keys are intentionally rejected by contract: PGM-13-E02 is not yet available to protect key material.</p>
 */
public final class ComplianceApplicationService {
    private static final ContractVersion EVENT_VERSION=ContractVersion.parse("1.0.0");
    private static final EventSource SOURCE=new EventSource("infranexum.itam.compliance");
    private static final int[] DEFAULT_ALERT_THRESHOLDS={180,120,90,60,30,15,7,1};
    private final AssetRepository assets;
    private final ComplianceRepository repository;
    private final ComplianceIdempotencyRepository idempotency;
    private final ComplianceReferencePolicy references;
    private final ComplianceFeaturePolicy features;
    private final TransactionalEventStore events;
    private final UuidV7Generator ids;
    private final Clock clock;
    private final MemorableCodeGenerator codes = new MemorableCodeGenerator();
    private final int[] alertThresholds;

    public ComplianceApplicationService(AssetRepository assets,ComplianceRepository repository,
            ComplianceIdempotencyRepository idempotency,ComplianceReferencePolicy references,ComplianceFeaturePolicy features,
            TransactionalEventStore events,UuidV7Generator ids,Clock clock){
        this(assets,repository,idempotency,references,features,events,ids,clock,DEFAULT_ALERT_THRESHOLDS);
    }

    /** Creates the service with policy-controlled, deterministic contractual alert thresholds. */
    public ComplianceApplicationService(AssetRepository assets,ComplianceRepository repository,
            ComplianceIdempotencyRepository idempotency,ComplianceReferencePolicy references,ComplianceFeaturePolicy features,
            TransactionalEventStore events,UuidV7Generator ids,Clock clock,int[] alertThresholds){
        this.assets=Objects.requireNonNull(assets,"assets");this.repository=Objects.requireNonNull(repository,"repository");
        this.idempotency=Objects.requireNonNull(idempotency,"idempotency");this.references=Objects.requireNonNull(references,"references");
        this.features=Objects.requireNonNull(features,"features");this.events=Objects.requireNonNull(events,"events");
        this.ids=Objects.requireNonNull(ids,"ids");this.clock=Objects.requireNonNull(clock,"clock");
        this.alertThresholds=validatedThresholds(alertThresholds);
    }

    /** Returns whether the complete ITAM compliance dependency chain is currently available. */
    public boolean enabled(){return features.complianceEnabled();}

    public Warranty createWarranty(CreateWarrantyCommand command,ComplianceCommandContext context){
        requireEnabled();Objects.requireNonNull(command,"command");Objects.requireNonNull(context,"context");
        Asset asset=requireAsset(command.assetId(),AssetType.HARDWARE);requireProducer(asset,command.manufacturerPartnerId());
        references.validateManufacturer(asset,command.manufacturerPartnerId(),command.warrantyStartDate());
        references.validateWarrantyType(command.warrantyTypeId());
        Warranty prototype=Warranty.draft(ids.next(),asset.id(),command.manufacturerPartnerId(),command.warrantyTypeId(),command.coverageLevel(),
                command.warrantyStartDate(),command.warrantyEndDate(),command.manufacturerSupportEndDate(),command.contractOrCertificateNumber(),
                command.proofReference(),EvidenceSource.parse(command.source()),context.actorId(),context.reason(),clock.instant());
        String fp=fingerprint("warranty-create",command);
        return execute(tx->{Optional<ComplianceIdempotencyRepository.Record> prior=idempotency.find(context.idempotencyKey());
            if(prior.isPresent())return replayWarranty(prior.orElseThrow(),fp,"warranty-create");repository.insertWarranty(prototype);
            tx.append(event("itam.warranty.created.v1",prototype.id(),asset,prototype.version(),context.correlationId()));
            idempotency.insert(new ComplianceIdempotencyRepository.Record(context.idempotencyKey(),fp,"warranty-create","warranty",prototype.id(),clock.instant()));return prototype;});
    }

    public Warranty reviseWarranty(DomainIdentifier id,long expectedVersion,CreateWarrantyCommand command,ComplianceCommandContext context){
        requireEnabled();Objects.requireNonNull(command,"command");Asset asset=requireAsset(command.assetId(),AssetType.HARDWARE);requireProducer(asset,command.manufacturerPartnerId());
        references.validateManufacturer(asset,command.manufacturerPartnerId(),command.warrantyStartDate());references.validateWarrantyType(command.warrantyTypeId());
        return mutateWarranty(id,expectedVersion,context,"warranty-revise","itam.warranty.updated.v1",current->{
            if(!current.assetId().equals(asset.id()))throw new ComplianceConflictException("ITAM_WARRANTY_ASSET_IMMUTABLE","warranty asset cannot change");
            return current.revise(command.manufacturerPartnerId(),command.warrantyTypeId(),command.coverageLevel(),command.warrantyStartDate(),
                    command.warrantyEndDate(),command.manufacturerSupportEndDate(),command.contractOrCertificateNumber(),command.proofReference(),
                    EvidenceSource.parse(command.source()),context.actorId(),context.reason(),clock.instant());});
    }

    public Warranty activateWarranty(DomainIdentifier id,long expectedVersion,ComplianceCommandContext context){
        return mutateWarranty(id,expectedVersion,context,"warranty-activate","itam.warranty.updated.v1",
                current->current.activate(context.actorId(),context.reason(),clock.instant()));
    }

    /** Explicitly expires a warranty after its contractual end date and emits the normative expiration event. */
    public Warranty expireWarranty(DomainIdentifier id,long expectedVersion,ComplianceCommandContext context){
        return mutateWarranty(id,expectedVersion,context,"warranty-expire","itam.warranty.expired.v1",
                current->current.expire(context.actorId(),context.reason(),clock.instant(),LocalDate.now(clock)));
    }

    public SoftwareLicenseContract createLicense(CreateLicenseCommand command,ComplianceCommandContext context){
        requireEnabled();Objects.requireNonNull(command,"command");Objects.requireNonNull(context,"context");
        Asset asset=requireAsset(command.assetId(),AssetType.SOFTWARE);requireProducer(asset,command.publisherPartnerId());
        references.validatePublisher(asset,command.publisherPartnerId(),command.startsOn());
        SoftwareLicenseContract prototype=SoftwareLicenseContract.draft(ids.next(),asset.id(),command.publisherPartnerId(),command.contractNumber(),
                command.licenseModel(),command.usageRights(),command.entitlementQuantity(),command.startsOn(),command.endsOn(),command.publisherSupportEndDate(),
                command.proofReference(),EvidenceSource.parse(command.source()),context.actorId(),context.reason(),clock.instant());
        String fp=fingerprint("license-create",command);
        return execute(tx->{Optional<ComplianceIdempotencyRepository.Record> prior=idempotency.find(context.idempotencyKey());if(prior.isPresent())return replayLicense(prior.orElseThrow(),fp,"license-create");
            requireQuota();repository.insertLicense(prototype);tx.append(event("itam.license.created.v1",prototype.id(),asset,prototype.version(),context.correlationId()));
            idempotency.insert(new ComplianceIdempotencyRepository.Record(context.idempotencyKey(),fp,"license-create","license",prototype.id(),clock.instant()));return prototype;});
    }

    public SoftwareLicenseContract reviseLicense(DomainIdentifier id,long expectedVersion,CreateLicenseCommand command,ComplianceCommandContext context){
        requireEnabled();Asset asset=requireAsset(command.assetId(),AssetType.SOFTWARE);requireProducer(asset,command.publisherPartnerId());
        references.validatePublisher(asset,command.publisherPartnerId(),command.startsOn());
        return mutateLicense(id,expectedVersion,context,"license-revise","itam.license.updated.v1",current->{
            if(!current.assetId().equals(asset.id()))throw new ComplianceConflictException("ITAM_LICENSE_ASSET_IMMUTABLE","license asset cannot change");
            return current.revise(command.publisherPartnerId(),command.contractNumber(),command.licenseModel(),command.usageRights(),command.entitlementQuantity(),
                    command.startsOn(),command.endsOn(),command.publisherSupportEndDate(),command.proofReference(),EvidenceSource.parse(command.source()),
                    context.actorId(),context.reason(),clock.instant());});
    }

    public SoftwareLicenseContract activateLicense(DomainIdentifier id,long expectedVersion,ComplianceCommandContext context){
        return mutateLicense(id,expectedVersion,context,"license-activate","itam.license.updated.v1",
                current->current.activate(context.actorId(),context.reason(),clock.instant()));
    }

    /** Explicitly expires a software-license contract after its contractual end date. */
    public SoftwareLicenseContract expireLicense(DomainIdentifier id,long expectedVersion,ComplianceCommandContext context){
        return mutateLicense(id,expectedVersion,context,"license-expire","itam.license.expired.v1",
                current->current.expire(context.actorId(),context.reason(),clock.instant(),LocalDate.now(clock)));
    }

    public SupportProviderAuthorization createSupportAuthorization(CreateSupportAuthorizationCommand command,ComplianceCommandContext context){
        requireEnabled();Objects.requireNonNull(command,"command");Objects.requireNonNull(context,"context");
        references.validateSupportAuthorizationDefinition(command.providerPartnerId(),command.organizationId(),
                command.supportedManufacturerIds(),command.supportedObjectTypes(),command.subdivisionScopes(),
                command.escalationContactTypes(),command.validFrom());
        SupportProviderAuthorization prototype=SupportProviderAuthorization.draft(ids.next(),command.providerPartnerId(),command.organizationId(),
                command.supportedManufacturerIds(),command.supportedObjectTypes(),command.subdivisionScopes(),command.serviceHours(),command.timeZoneId(),
                command.serviceLevels(),command.escalationContactTypes(),command.validFrom(),command.validUntil(),context.actorId(),context.reason(),clock.instant());
        String fp=fingerprint("support-auth-create",command);
        return execute(tx->{Optional<ComplianceIdempotencyRepository.Record> prior=idempotency.find(context.idempotencyKey());if(prior.isPresent())return replayAuthorization(prior.orElseThrow(),fp,"support-auth-create");
            repository.insertSupportAuthorization(prototype);tx.append(event("itam.support_authorization.created.v1",prototype.id(),null,prototype.version(),context.correlationId()));
            idempotency.insert(new ComplianceIdempotencyRepository.Record(context.idempotencyKey(),fp,"support-auth-create","support_authorization",prototype.id(),clock.instant()));return prototype;});
    }

    public SupportProviderAuthorization activateSupportAuthorization(DomainIdentifier id,long expectedVersion,ComplianceCommandContext context){
        return mutateAuthorization(id,expectedVersion,context,"support-auth-activate","itam.support_authorization.activated.v1",
                current->current.activate(context.actorId(),context.reason(),clock.instant(),LocalDate.now(clock)));
    }

    public SupportProviderAuthorization suspendSupportAuthorization(DomainIdentifier id,long expectedVersion,ComplianceCommandContext context){
        requireEnabled();String fp=fingerprint("support-auth-suspend",id,expectedVersion,context.reason());
        return execute(tx->{Optional<ComplianceIdempotencyRepository.Record> prior=idempotency.find(context.idempotencyKey());
            if(prior.isPresent())return replayAuthorization(prior.orElseThrow(),fp,"support-auth-suspend");
            SupportProviderAuthorization current=repository.findSupportAuthorization(id).orElseThrow(ComplianceNotFoundException::new);
            requireVersion(current.version(),expectedVersion);
            SupportProviderAuthorization changed=current.suspend(context.actorId(),context.reason(),clock.instant());
            repository.updateSupportAuthorization(changed,expectedVersion);
            for(SupportCoverage coverage:repository.supportCoveragesForAuthorization(id)){
                if(coverage.status()==ComplianceStatus.ACTIVE){
                    SupportCoverage review=coverage.requireReview(context.actorId(),context.reason(),clock.instant());
                    repository.updateSupportCoverage(review,coverage.version());
                    Asset asset=assets.findById(review.assetId()).orElse(null);
                    tx.append(event("itam.support_coverage.review_required.v1",review.id(),asset,review.version(),context.correlationId()));
                }
            }
            tx.append(event("itam.support_authorization.suspended.v1",changed.id(),null,changed.version(),context.correlationId()));
            idempotency.insert(new ComplianceIdempotencyRepository.Record(context.idempotencyKey(),fp,"support-auth-suspend","support_authorization",changed.id(),clock.instant()));
            return changed;});
    }

    public SupportCoverage createSupportCoverage(CreateSupportCoverageCommand command,ComplianceCommandContext context){
        requireEnabled();Objects.requireNonNull(command,"command");Objects.requireNonNull(context,"context");
        Asset asset=requireAsset(command.assetId(),AssetType.HARDWARE);if(asset.producerPartnerId()==null)throw missingProducer();
        SupportProviderAuthorization authorization=repository.findSupportAuthorization(command.authorizationId()).orElseThrow(ComplianceNotFoundException::new);
        if(!authorization.providerPartnerId().equals(command.providerPartnerId()))throw new ComplianceConflictException("ITAM_SUPPORT_AUTH_PROVIDER_MISMATCH","support authorization belongs to another provider");
        String objectType=references.canonicalObjectType(asset);
        references.validateSupportProvider(asset,command.providerPartnerId(),command.startsOn(),authorization.escalationContactTypes());
        references.validateSupportAuthorization(asset,authorization,command.serviceLevel(),command.startsOn());
        SupportCoverage prototype=SupportCoverage.draft(ids.next(),asset.id(),command.providerPartnerId(),authorization.id(),command.contractReference(),
                command.coverageType(),command.serviceLevel(),command.startsOn(),command.endsOn(),asset.producerPartnerId(),objectType,
                asset.owningOrganizationId(),asset.owningSubdivisionId(),command.proofReference(),context.actorId(),context.reason(),clock.instant());
        String fp=fingerprint("support-coverage-create",command);
        return execute(tx->{Optional<ComplianceIdempotencyRepository.Record> prior=idempotency.find(context.idempotencyKey());if(prior.isPresent())return replayCoverage(prior.orElseThrow(),fp,"support-coverage-create");
            requireQuota();repository.insertSupportCoverage(prototype);tx.append(event("itam.support_coverage.created.v1",prototype.id(),asset,prototype.version(),context.correlationId()));
            idempotency.insert(new ComplianceIdempotencyRepository.Record(context.idempotencyKey(),fp,"support-coverage-create","support_coverage",prototype.id(),clock.instant()));return prototype;});
    }

    public SupportCoverage reviseSupportCoverage(DomainIdentifier id,long expectedVersion,CreateSupportCoverageCommand command,ComplianceCommandContext context){
        requireEnabled();SupportCoverage current=getSupportCoverage(id);Asset asset=requireAsset(current.assetId(),AssetType.HARDWARE);
        if(!current.assetId().equals(command.assetId())||!current.providerPartnerId().equals(command.providerPartnerId())||!current.authorizationId().equals(command.authorizationId()))
            throw new ComplianceConflictException("ITAM_SUPPORT_COVERAGE_IDENTITY_IMMUTABLE","support coverage asset, provider and authorization cannot change");
        SupportProviderAuthorization authorization=repository.findSupportAuthorization(current.authorizationId()).orElseThrow(ComplianceNotFoundException::new);
        references.validateSupportAuthorization(asset,authorization,command.serviceLevel(),command.startsOn());
        return mutateCoverage(id,expectedVersion,context,"support-coverage-revise","itam.support_coverage.updated.v1",
                value->value.revise(command.contractReference(),command.coverageType(),command.serviceLevel(),command.startsOn(),command.endsOn(),
                        command.proofReference(),context.actorId(),context.reason(),clock.instant()));
    }

    public SupportCoverage activateSupportCoverage(DomainIdentifier id,long expectedVersion,ComplianceCommandContext context){
        return mutateCoverage(id,expectedVersion,context,"support-coverage-activate","itam.support_coverage.activated.v1",current->{
            Asset asset=requireAsset(current.assetId(),AssetType.HARDWARE);
            SupportProviderAuthorization authorization=repository.findSupportAuthorization(current.authorizationId()).orElseThrow(ComplianceNotFoundException::new);
            references.validateSupportAuthorization(asset,authorization,current.serviceLevel(),LocalDate.now(clock));
            return current.activate(context.actorId(),context.reason(),clock.instant());});
    }

    /** Explicitly expires third-party coverage after its contractual end date. */
    public SupportCoverage expireSupportCoverage(DomainIdentifier id,long expectedVersion,ComplianceCommandContext context){
        return mutateCoverage(id,expectedVersion,context,"support-coverage-expire","itam.support_coverage.expired.v1",
                current->current.expire(context.actorId(),context.reason(),clock.instant(),LocalDate.now(clock)));
    }

    public WarrantyType createWarrantyType(String code,String displayName,ComplianceCommandContext context){
        requireEnabled();Objects.requireNonNull(context,"context");String requested=optionalCode(code);String fp=fingerprint("warranty-type-create",requested==null?"<auto>":requested,displayName);
        return execute(tx->{Optional<ComplianceIdempotencyRepository.Record> prior=idempotency.find(context.idempotencyKey());if(prior.isPresent()){
                ComplianceIdempotencyRepository.Record record=prior.orElseThrow();if(!record.operation().equals("warranty-type-create")||!record.payloadSha256().equals(fp))throw idem();
                return repository.warrantyTypes(false).stream().filter(v->v.id().equals(record.recordId())).findFirst().orElseThrow(ComplianceNotFoundException::new);}
            DomainIdentifier id=ids.next();String generated=requested==null?codes.generate(displayName,id).replace('-','_'):requested;WarrantyType type=new WarrantyType(id,generated,displayName,true,clock.instant(),context.actorId());
            repository.insertWarrantyType(type);idempotency.insert(new ComplianceIdempotencyRepository.Record(context.idempotencyKey(),fp,"warranty-type-create","warranty_type",type.id(),clock.instant()));return type;});
    }

    public Warranty getWarranty(DomainIdentifier id){requireEnabled();return repository.findWarranty(Objects.requireNonNull(id,"id")).orElseThrow(ComplianceNotFoundException::new);}
    public SoftwareLicenseContract getLicense(DomainIdentifier id){requireEnabled();return repository.findLicense(Objects.requireNonNull(id,"id")).orElseThrow(ComplianceNotFoundException::new);}
    public SupportProviderAuthorization getSupportAuthorization(DomainIdentifier id){requireEnabled();return repository.findSupportAuthorization(Objects.requireNonNull(id,"id")).orElseThrow(ComplianceNotFoundException::new);}
    public List<SupportProviderAuthorization> supportAuthorizations(DomainIdentifier organizationId){requireEnabled();return repository.supportAuthorizations(Objects.requireNonNull(organizationId,"organizationId"));}
    public OffsetPage<SupportProviderAuthorization> supportAuthorizationPage(DomainIdentifier organizationId,int offset,int limit){requireEnabled();return offsetPage(repository.supportAuthorizations(Objects.requireNonNull(organizationId,"organizationId")),offset,limit,200);}
    public SupportCoverage getSupportCoverage(DomainIdentifier id){requireEnabled();return repository.findSupportCoverage(Objects.requireNonNull(id,"id")).orElseThrow(ComplianceNotFoundException::new);}

    public List<Warranty> warranties(DomainIdentifier assetId){requireEnabled();requireAsset(assetId,null);return repository.warrantiesForAsset(assetId);}
    public List<SoftwareLicenseContract> licenses(DomainIdentifier assetId){requireEnabled();requireAsset(assetId,null);return repository.licensesForAsset(assetId);}
    public List<SupportCoverage> supportCoverages(DomainIdentifier assetId){requireEnabled();requireAsset(assetId,null);return repository.supportCoveragesForAsset(assetId);}
    public CompliancePage<Warranty> warrantyPage(DomainIdentifier assetId,DomainIdentifier afterId,int limit){requireEnabled();requireAsset(assetId,null);return page(repository.warrantyPage(assetId,afterId,checkedLimit(limit)),limit,Warranty::id);}
    public CompliancePage<SoftwareLicenseContract> licensePage(DomainIdentifier assetId,DomainIdentifier afterId,int limit){requireEnabled();requireAsset(assetId,null);return page(repository.licensePage(assetId,afterId,checkedLimit(limit)),limit,SoftwareLicenseContract::id);}
    public CompliancePage<SupportCoverage> supportCoveragePage(DomainIdentifier assetId,DomainIdentifier afterId,int limit){requireEnabled();requireAsset(assetId,null);return page(repository.supportCoveragePage(assetId,afterId,checkedLimit(limit)),limit,SupportCoverage::id);}
    public List<WarrantyType> warrantyTypes(){requireEnabled();return repository.warrantyTypes(true);}
    public OffsetPage<WarrantyType> warrantyTypePage(int offset,int limit){requireEnabled();return offsetPage(repository.warrantyTypes(true),offset,limit,200);}
    public List<ComplianceRevision> history(String recordType,DomainIdentifier recordId,long afterVersion,int limit){requireEnabled();if(afterVersion<0)throw new IllegalArgumentException("afterVersion must not be negative");if(limit<1||limit>200)throw new IllegalArgumentException("limit must be between 1 and 200");return repository.revisions(recordType,recordId,afterVersion,limit);}

    public List<ComplianceAlert> upcomingAlerts(DomainIdentifier assetId,LocalDate asOf,int horizonDays){
        requireEnabled();requireAsset(assetId,null);Objects.requireNonNull(asOf,"asOf");if(horizonDays<1||horizonDays>3650)throw new IllegalArgumentException("horizonDays must be between 1 and 3650");
        List<ComplianceAlert> result=new ArrayList<>();for(Warranty w:repository.warrantiesForAsset(assetId)){addUpcoming(result,ComplianceAlertKind.WARRANTY_END,w.id(),assetId,w.warrantyEndDate(),asOf,horizonDays,alertThresholds);addUpcoming(result,ComplianceAlertKind.MANUFACTURER_SUPPORT_END,w.id(),assetId,w.manufacturerSupportEndDate(),asOf,horizonDays,alertThresholds);}for(SoftwareLicenseContract l:repository.licensesForAsset(assetId)){if(l.endsOn()!=null)addUpcoming(result,ComplianceAlertKind.LICENSE_END,l.id(),assetId,l.endsOn(),asOf,horizonDays,alertThresholds);addUpcoming(result,ComplianceAlertKind.SOFTWARE_SUPPORT_END,l.id(),assetId,l.publisherSupportEndDate(),asOf,horizonDays,alertThresholds);}for(SupportCoverage c:repository.supportCoveragesForAsset(assetId))addUpcoming(result,ComplianceAlertKind.THIRD_PARTY_SUPPORT_END,c.id(),assetId,c.endsOn(),asOf,horizonDays,alertThresholds);return result.stream().sorted(java.util.Comparator.comparing(ComplianceAlert::dueDate).thenComparing(a->a.kind().wireValue())).toList();
    }
    public OffsetPage<ComplianceAlert> upcomingAlertPage(DomainIdentifier assetId,LocalDate asOf,int horizonDays,int offset,int limit){return offsetPage(upcomingAlerts(assetId,asOf,horizonDays),offset,limit,200);}

    private static <T>OffsetPage<T> offsetPage(List<T> rows,int offset,int limit,int max){PaginationConstraints.requireOffset(offset);if(limit<1||limit>max)throw new IllegalArgumentException("limit must be between 1 and "+max);int from=Math.min(offset,rows.size());int to=Math.min(rows.size(),Math.addExact(from,limit));List<T> items=List.copyOf(rows.subList(from,to));Integer next=to<rows.size()?to:null;return new OffsetPage<>(items,next);}

    /** Idempotently publishes deadline events exactly on configured J-180/J-120/.../J-1 thresholds. */
    public int publishDueAlerts(LocalDate asOf,DomainIdentifier correlationId){
        requireEnabled();Objects.requireNonNull(asOf,"asOf");Objects.requireNonNull(correlationId,"correlationId");
        LocalDate end=asOf.plusDays(alertThresholds[0]);List<ComplianceAlert> due=new ArrayList<>();
        for(Warranty w:repository.warrantiesDueBetween(asOf.plusDays(1),end)){addDue(due,ComplianceAlertKind.WARRANTY_END,w.id(),w.assetId(),w.warrantyEndDate(),asOf,alertThresholds);addDue(due,ComplianceAlertKind.MANUFACTURER_SUPPORT_END,w.id(),w.assetId(),w.manufacturerSupportEndDate(),asOf,alertThresholds);}
        for(SoftwareLicenseContract l:repository.licensesDueBetween(asOf.plusDays(1),end)){if(l.endsOn()!=null)addDue(due,ComplianceAlertKind.LICENSE_END,l.id(),l.assetId(),l.endsOn(),asOf,alertThresholds);addDue(due,ComplianceAlertKind.SOFTWARE_SUPPORT_END,l.id(),l.assetId(),l.publisherSupportEndDate(),asOf,alertThresholds);}
        for(SupportCoverage c:repository.supportCoveragesDueBetween(asOf.plusDays(1),end))addDue(due,ComplianceAlertKind.THIRD_PARTY_SUPPORT_END,c.id(),c.assetId(),c.endsOn(),asOf,alertThresholds);
        int emitted=0;for(ComplianceAlert alert:due){boolean sent=execute(tx->{if(!repository.reserveAlert(alert,asOf))return false;Asset asset=assets.findById(alert.assetId()).orElse(null);tx.append(event(alert.kind().eventType(),alert.recordId(),asset,1,correlationId));return true;});if(sent)emitted++;}return emitted;
    }

    public boolean hardwareReady(Asset asset,LocalDate date){
        if(!features.complianceEnabled()||asset.assetType()!=AssetType.HARDWARE||asset.producerPartnerId()==null)return false;
        List<Warranty> warranties=repository.warrantiesForAsset(asset.id()).stream().filter(Warranty::verifiedComplete).filter(w->w.manufacturerPartnerId().equals(asset.producerPartnerId())).toList();
        if(warranties.isEmpty())return false;if(warranties.stream().anyMatch(w->w.warrantyCovers(date)))return true;
        return repository.supportCoveragesForAsset(asset.id()).stream().anyMatch(c->c.supportedManufacturerId().equals(asset.producerPartnerId())&&c.covers(date));
    }
    public boolean softwareReady(Asset asset,LocalDate date){
        if(!features.complianceEnabled()||asset.assetType()!=AssetType.SOFTWARE||asset.producerPartnerId()==null)return false;
        return repository.licensesForAsset(asset.id()).stream().anyMatch(l->l.publisherPartnerId().equals(asset.producerPartnerId())&&l.covers(date));
    }

    private Warranty mutateWarranty(DomainIdentifier id,long version,ComplianceCommandContext ctx,String op,String eventType,WarrantyTransition transition){
        requireEnabled();String fp=fingerprint(op,id,version,ctx.reason());return execute(tx->{Optional<ComplianceIdempotencyRepository.Record> prior=idempotency.find(ctx.idempotencyKey());if(prior.isPresent())return replayWarranty(prior.orElseThrow(),fp,op);Warranty current=repository.findWarranty(id).orElseThrow(ComplianceNotFoundException::new);requireVersion(current.version(),version);Warranty changed=transition.apply(current);repository.updateWarranty(changed,version);Asset asset=assets.findById(changed.assetId()).orElse(null);tx.append(event(eventType,changed.id(),asset,changed.version(),ctx.correlationId()));idempotency.insert(new ComplianceIdempotencyRepository.Record(ctx.idempotencyKey(),fp,op,"warranty",changed.id(),clock.instant()));return changed;});}
    private SoftwareLicenseContract mutateLicense(DomainIdentifier id,long version,ComplianceCommandContext ctx,String op,String eventType,LicenseTransition transition){
        requireEnabled();String fp=fingerprint(op,id,version,ctx.reason());return execute(tx->{Optional<ComplianceIdempotencyRepository.Record> prior=idempotency.find(ctx.idempotencyKey());if(prior.isPresent())return replayLicense(prior.orElseThrow(),fp,op);SoftwareLicenseContract current=repository.findLicense(id).orElseThrow(ComplianceNotFoundException::new);requireVersion(current.version(),version);SoftwareLicenseContract changed=transition.apply(current);repository.updateLicense(changed,version);Asset asset=assets.findById(changed.assetId()).orElse(null);tx.append(event(eventType,changed.id(),asset,changed.version(),ctx.correlationId()));idempotency.insert(new ComplianceIdempotencyRepository.Record(ctx.idempotencyKey(),fp,op,"license",changed.id(),clock.instant()));return changed;});}
    private SupportProviderAuthorization mutateAuthorization(DomainIdentifier id,long version,ComplianceCommandContext ctx,String op,String eventType,AuthorizationTransition transition){
        requireEnabled();String fp=fingerprint(op,id,version,ctx.reason());return execute(tx->{Optional<ComplianceIdempotencyRepository.Record> prior=idempotency.find(ctx.idempotencyKey());if(prior.isPresent())return replayAuthorization(prior.orElseThrow(),fp,op);SupportProviderAuthorization current=repository.findSupportAuthorization(id).orElseThrow(ComplianceNotFoundException::new);requireVersion(current.version(),version);SupportProviderAuthorization changed=transition.apply(current);repository.updateSupportAuthorization(changed,version);tx.append(event(eventType,changed.id(),null,changed.version(),ctx.correlationId()));idempotency.insert(new ComplianceIdempotencyRepository.Record(ctx.idempotencyKey(),fp,op,"support_authorization",changed.id(),clock.instant()));return changed;});}
    private SupportCoverage mutateCoverage(DomainIdentifier id,long version,ComplianceCommandContext ctx,String op,String eventType,CoverageTransition transition){
        requireEnabled();String fp=fingerprint(op,id,version,ctx.reason());return execute(tx->{Optional<ComplianceIdempotencyRepository.Record> prior=idempotency.find(ctx.idempotencyKey());if(prior.isPresent())return replayCoverage(prior.orElseThrow(),fp,op);SupportCoverage current=repository.findSupportCoverage(id).orElseThrow(ComplianceNotFoundException::new);requireVersion(current.version(),version);SupportCoverage changed=transition.apply(current);repository.updateSupportCoverage(changed,version);Asset asset=assets.findById(changed.assetId()).orElse(null);tx.append(event(eventType,changed.id(),asset,changed.version(),ctx.correlationId()));idempotency.insert(new ComplianceIdempotencyRepository.Record(ctx.idempotencyKey(),fp,op,"support_coverage",changed.id(),clock.instant()));return changed;});}

    private Asset requireAsset(DomainIdentifier id,AssetType expected){Asset asset=assets.findById(Objects.requireNonNull(id,"assetId")).orElseThrow(()->new ComplianceConflictException("ITAM_COMPLIANCE_ASSET_NOT_FOUND","asset not found"));if(expected!=null&&asset.assetType()!=expected)throw new ComplianceConflictException("ITAM_COMPLIANCE_ASSET_TYPE_INVALID","compliance record does not apply to asset type");return asset;}
    private static void requireProducer(Asset asset,DomainIdentifier partner){if(asset.producerPartnerId()==null)throw missingProducer();if(!asset.producerPartnerId().equals(partner))throw new ComplianceConflictException("ITAM_COMPLIANCE_PRODUCER_MISMATCH","contractual producer differs from asset producer");}
    private static ComplianceConflictException missingProducer(){return new ComplianceConflictException("ITAM_ASSET_PRODUCER_REQUIRED","asset requires a governed manufacturer or publisher before compliance can be verified");}
    private void requireQuota(){if(repository.contractRecordCount()>=features.contractLimit())throw new ComplianceConflictException("ITAM_CONTRACT_QUOTA_EXCEEDED","ITAM contractual record quota reached");}
    private void requireEnabled(){if(!features.complianceEnabled())throw new ComplianceConflictException("ITAM_COMPLIANCE_CAPABILITY_UNAVAILABLE","ITAM warranty/support/license capability is unavailable");}
    private static void requireVersion(long actual,long expected){if(expected<1)throw new IllegalArgumentException("expectedVersion must be positive");if(actual!=expected)throw new ComplianceConflictException("VERSION_CONFLICT","compliance record version changed");}

    private Warranty replayWarranty(ComplianceIdempotencyRepository.Record r,String fp,String op){validateReplay(r,fp,op,"warranty");return repository.findWarranty(r.recordId()).orElseThrow(ComplianceNotFoundException::new);}
    private SoftwareLicenseContract replayLicense(ComplianceIdempotencyRepository.Record r,String fp,String op){validateReplay(r,fp,op,"license");return repository.findLicense(r.recordId()).orElseThrow(ComplianceNotFoundException::new);}
    private SupportProviderAuthorization replayAuthorization(ComplianceIdempotencyRepository.Record r,String fp,String op){validateReplay(r,fp,op,"support_authorization");return repository.findSupportAuthorization(r.recordId()).orElseThrow(ComplianceNotFoundException::new);}
    private SupportCoverage replayCoverage(ComplianceIdempotencyRepository.Record r,String fp,String op){validateReplay(r,fp,op,"support_coverage");return repository.findSupportCoverage(r.recordId()).orElseThrow(ComplianceNotFoundException::new);}
    private static void validateReplay(ComplianceIdempotencyRepository.Record r,String fp,String op,String type){if(!r.operation().equals(op)||!r.recordType().equals(type)||!r.payloadSha256().equals(fp))throw idem();}
    private static ComplianceConflictException idem(){return new ComplianceConflictException("IDEMPOTENCY_CONFLICT","idempotency key was used with another compliance mutation");}

    private EventEnvelope event(String type,DomainIdentifier recordId,Asset asset,long version,DomainIdentifier correlation){
        String assetJson=asset==null?"null":"\""+asset.id()+"\"";String orgJson=asset==null?"null":"\""+asset.owningOrganizationId()+"\"";
        String payload="{\"record_id\":\""+recordId+"\",\"asset_id\":"+assetJson+",\"organization_id\":"+orgJson+",\"version\":"+version+"}";
        return new EventEnvelope(ids.next(),new EventType(type),EVENT_VERSION,clock.instant(),SOURCE,correlation,recordId,payload);
    }
    private <T>T execute(io.infranexum.core.events.TransactionalWork<T> work){try{return events.execute(work).value();}catch(TransactionExecutionException failure){Throwable cause=failure.getCause();if(cause instanceof ComplianceConflictException c)throw c;if(cause instanceof ComplianceNotFoundException n)throw n;if(cause instanceof IllegalArgumentException i)throw i;throw failure;}}
    private static String fingerprint(Object... values){StringBuilder canonical=new StringBuilder();for(Object value:values){String text=value==null?"<null>":value.toString();canonical.append(text.length()).append(':').append(text).append(';');}try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException exception){throw new IllegalStateException("SHA-256 unavailable",exception);}}
    private static void addUpcoming(List<ComplianceAlert> out,ComplianceAlertKind kind,DomainIdentifier recordId,DomainIdentifier assetId,LocalDate due,LocalDate asOf,int horizon,int[] thresholds){long days=ChronoUnit.DAYS.between(asOf,due);if(days<0||days>horizon)return;int threshold=thresholds[thresholds.length-1];for(int candidate:thresholds)if(days<=candidate)threshold=candidate;out.add(new ComplianceAlert(kind,recordId,assetId,due,days,threshold));}
    private static void addDue(List<ComplianceAlert> out,ComplianceAlertKind kind,DomainIdentifier recordId,DomainIdentifier assetId,LocalDate due,LocalDate asOf,int[] thresholds){long days=ChronoUnit.DAYS.between(asOf,due);for(int threshold:thresholds)if(days==threshold){out.add(new ComplianceAlert(kind,recordId,assetId,due,days,threshold));return;}}


    private static String optionalCode(String value){if(value==null)return null;String normalized=value.strip();return normalized.isEmpty()?null:normalized;}

    private static int[] validatedThresholds(int[] values){
        Objects.requireNonNull(values,"alertThresholds");if(values.length<1||values.length>32)throw new IllegalArgumentException("alertThresholds must contain between 1 and 32 values");
        int[] copy=values.clone();int previous=Integer.MAX_VALUE;for(int value:copy){if(value<1||value>3650||value>=previous)throw new IllegalArgumentException("alertThresholds must be unique, descending and between 1 and 3650 days");previous=value;}return copy;
    }

    private static int checkedLimit(int limit){if(limit<1||limit>200)throw new IllegalArgumentException("limit must be between 1 and 200");return limit;}
    private static <T> CompliancePage<T> page(List<T> rows,int limit,java.util.function.Function<T,DomainIdentifier> id){boolean more=rows.size()>limit;List<T> items=more?List.copyOf(rows.subList(0,limit)):List.copyOf(rows);DomainIdentifier next=more&&!items.isEmpty()?id.apply(items.get(items.size()-1)):null;return new CompliancePage<>(items,next);}

    @FunctionalInterface private interface WarrantyTransition{Warranty apply(Warranty current);} @FunctionalInterface private interface LicenseTransition{SoftwareLicenseContract apply(SoftwareLicenseContract current);} @FunctionalInterface private interface AuthorizationTransition{SupportProviderAuthorization apply(SupportProviderAuthorization current);} @FunctionalInterface private interface CoverageTransition{SupportCoverage apply(SupportCoverage current);}
}
