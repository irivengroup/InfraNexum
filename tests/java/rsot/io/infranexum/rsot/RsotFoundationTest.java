package io.infranexum.rsot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.rsot.application.RsotAuthorityService;
import io.infranexum.rsot.application.RsotQueryService;
import io.infranexum.rsot.domain.AttributeAuthorityPolicy;
import io.infranexum.rsot.domain.AuthorityContext;
import io.infranexum.rsot.domain.AuthorityMatrixEntry;
import io.infranexum.rsot.domain.CanonicalLifecycle;
import io.infranexum.rsot.domain.CanonicalObject;
import io.infranexum.rsot.domain.CanonicalObjectStatus;
import io.infranexum.rsot.domain.ContextRelationship;
import io.infranexum.rsot.domain.InitialRsotGovernance;
import io.infranexum.rsot.domain.RsotException;
import io.infranexum.rsot.ports.RsotRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Behavioral regression coverage for PGM-06-E01 canonical identity and authority foundations. */
class RsotFoundationTest {
    private static final Instant NOW = Instant.parse("2026-08-13T16:00:00Z");
    private static final DomainIdentifier ORG = id(1);

    @Test
    void initialAuthorityMatrixAndContextMapMatchDraft21WithoutDirectStorageWrites() {
        List<AuthorityMatrixEntry> matrix = InitialRsotGovernance.authorityMatrix();
        assertEquals(9, matrix.size());
        assertEquals("Organisation, subdivision", matrix.getFirst().information());
        assertEquals("Organisation", matrix.getFirst().authority());
        assertEquals("Governance/RSOT", matrix.getLast().authority());
        assertTrue(matrix.stream().allMatch(row -> row.matrixVersion().equals("2.0.0-draft.21")));

        List<ContextRelationship> contextMap = InitialRsotGovernance.contextMap();
        assertEquals(10, contextMap.size());
        assertEquals("Organization", contextMap.getFirst().provider());
        assertEquals("Core Capabilities", contextMap.getLast().provider());
        assertTrue(contextMap.stream().noneMatch(ContextRelationship::directStorageWriteAllowed));
        assertThrows(IllegalArgumentException.class,
                () -> new ContextRelationship(1, "IAM", "actors", true));
    }

    @Test
    void canonicalLifecycleAndObjectValidateMandatoryIdentityAndConsumerStates() {
        CanonicalLifecycle validated = lifecycle(CanonicalObjectStatus.VALIDATED);
        CanonicalObject object = object(10, validated);
        assertEquals("rsot.asset", object.objectType());
        assertEquals(1L, object.version());
        assertEquals(ORG, object.organizationId());
        assertTrue(validated.status().consumerReadable());
        assertTrue(CanonicalObjectStatus.RECONCILED.consumerReadable());
        assertFalse(CanonicalObjectStatus.PROPOSED.consumerReadable());
        assertFalse(CanonicalObjectStatus.DEPRECATED.consumerReadable());
        assertFalse(CanonicalObjectStatus.ARCHIVED.consumerReadable());

        assertThrows(IllegalArgumentException.class,
                () -> new CanonicalObject(id(11), "asset", 1, ORG, "1.0.0", validated, NOW, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new CanonicalObject(id(11), "rsot.asset", 0, ORG, "1.0.0", validated, NOW, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new CanonicalLifecycle(CanonicalObjectStatus.ARCHIVED, null, NOW, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new CanonicalLifecycle(CanonicalObjectStatus.VALIDATED, null, NOW, NOW, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new CanonicalLifecycle(CanonicalObjectStatus.VALIDATED, null, NOW, null, NOW, null));
    }

    @Test
    void queryServiceHidesUncertifiedObjectsOutsideGovernanceView() {
        InMemoryRepository repository = new InMemoryRepository();
        CanonicalObject proposed = object(20, lifecycle(CanonicalObjectStatus.PROPOSED));
        CanonicalObject validated = object(21, lifecycle(CanonicalObjectStatus.VALIDATED));
        CanonicalObject reconciled = object(22, lifecycle(CanonicalObjectStatus.RECONCILED));
        repository.objects.addAll(List.of(proposed, validated, reconciled));
        RsotQueryService service = new RsotQueryService(repository);

        assertEquals(List.of(validated, reconciled), service.list(0, 200, false));
        assertEquals(3, service.list(0, 200, true).size());
        assertEquals(validated, service.get(validated.id(), false));
        assertEquals(proposed, service.get(proposed.id(), true));
        assertCode("RSOT_CANONICAL_OBJECT_NOT_READABLE", () -> service.get(proposed.id(), false));
        assertCode("RSOT_CANONICAL_OBJECT_NOT_FOUND", () -> service.get(id(999), false));
        assertThrows(IllegalArgumentException.class, () -> service.list(-1, 1, false));
        assertThrows(IllegalArgumentException.class, () -> service.list(0, 201, false));
    }

    @Test
    void attributePoliciesAreBoundedVersionedTemporalAndFailClosedWhenResolutionIsNotUnique() {
        InMemoryRepository repository = new InMemoryRepository();
        AttributeAuthorityPolicy exact = policy(30, "rsot.asset", "location.site_id", AuthorityContext.DCIM, NOW.minusSeconds(60), null);
        repository.policies.add(exact);
        RsotAuthorityService service = new RsotAuthorityService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
        assertEquals(exact, service.resolve("RSOT.ASSET", "LOCATION.SITE_ID"));
        assertFalse(exact.activeAt(NOW.minusSeconds(61)));
        assertTrue(exact.activeAt(NOW));

        AttributeAuthorityPolicy bounded = policy(31, "rsot.*", "network.*", AuthorityContext.DDI, NOW.minusSeconds(60), NOW.plusSeconds(60));
        repository.policies.clear();
        repository.policies.add(bounded);
        assertEquals(bounded, service.resolve("rsot.server", "network.primary_ip"));
        assertCode("RSOT_AUTHORITY_NOT_CONFIGURED", () -> service.resolve("rsot.server", "hardware.serial"));

        repository.policies.add(policy(32, "rsot.server", "network.primary_ip", AuthorityContext.RSOT, NOW.minusSeconds(1), null));
        assertCode("RSOT_AUTHORITY_AMBIGUOUS", () -> service.resolve("rsot.server", "network.primary_ip"));
        assertThrows(IllegalArgumentException.class,
                () -> new AttributeAuthorityPolicy(id(40), "*", "network.ip", AuthorityContext.DDI,
                        List.of(AuthorityContext.DDI), NOW, null, "1", "approval"));
        assertThrows(IllegalArgumentException.class,
                () -> new AttributeAuthorityPolicy(id(41), "rsot.asset", ".*", AuthorityContext.DDI,
                        List.of(AuthorityContext.DDI), NOW, null, "1", "approval"));
        assertThrows(IllegalArgumentException.class,
                () -> new AttributeAuthorityPolicy(id(42), "rsot.asset", "network.*.ip", AuthorityContext.DDI,
                        List.of(AuthorityContext.DDI), NOW, null, "1", "approval"));
        assertThrows(IllegalArgumentException.class,
                () -> new AttributeAuthorityPolicy(id(43), "rsot.asset", "network.ip", AuthorityContext.DDI,
                        List.of(AuthorityContext.RSOT), NOW, null, "1", "approval"));
    }


    @Test
    void lifecycleRejectsInvalidTemporalArchiveAndTextCombinations() {
        assertThrows(NullPointerException.class,
                () -> new CanonicalLifecycle(null, null, NOW, null, null, null));
        assertThrows(NullPointerException.class,
                () -> new CanonicalLifecycle(CanonicalObjectStatus.VALIDATED, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new CanonicalLifecycle(CanonicalObjectStatus.VALIDATED, null, NOW, NOW.minusSeconds(1), null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new CanonicalLifecycle(CanonicalObjectStatus.VALIDATED, null, NOW, null, null, id(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new CanonicalLifecycle(CanonicalObjectStatus.VALIDATED, null, NOW, null, NOW, null));
        assertThrows(IllegalArgumentException.class,
                () -> new CanonicalLifecycle(CanonicalObjectStatus.ARCHIVED, null, NOW, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new CanonicalLifecycle(CanonicalObjectStatus.ARCHIVED, null, NOW, null, NOW.minusSeconds(1), id(2)));
        assertThrows(IllegalArgumentException.class,
                () -> new CanonicalLifecycle(CanonicalObjectStatus.VALIDATED, "x".repeat(501), NOW, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new CanonicalLifecycle(CanonicalObjectStatus.VALIDATED, "bad\u0001reason", NOW, null, null, null));
        CanonicalLifecycle normalized = new CanonicalLifecycle(
                CanonicalObjectStatus.VALIDATED, "   ", NOW, NOW.plusSeconds(1), null, null);
        assertEquals(null, normalized.statusReason());
    }

    @Test
    void canonicalObjectFailsFastOnMalformedIdentityVersionAndTimeline() {
        CanonicalLifecycle lifecycle = lifecycle(CanonicalObjectStatus.VALIDATED);
        assertThrows(NullPointerException.class,
                () -> new CanonicalObject(null, "rsot.asset", 1, ORG, "1", lifecycle, NOW, NOW));
        assertThrows(NullPointerException.class,
                () -> new CanonicalObject(id(1), "rsot.asset", 1, null, "1", lifecycle, NOW, NOW));
        assertThrows(NullPointerException.class,
                () -> new CanonicalObject(id(1), "rsot.asset", 1, ORG, "1", null, NOW, NOW));
        assertThrows(NullPointerException.class,
                () -> new CanonicalObject(id(1), "rsot.asset", 1, ORG, "1", lifecycle, null, NOW));
        assertThrows(NullPointerException.class,
                () -> new CanonicalObject(id(1), "rsot.asset", 1, ORG, "1", lifecycle, NOW, null));
        assertThrows(IllegalArgumentException.class,
                () -> new CanonicalObject(id(1), "rsot.asset", -1, ORG, "1", lifecycle, NOW, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new CanonicalObject(id(1), " rsot.asset ", 1, ORG, " ", lifecycle, NOW, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new CanonicalObject(id(1), "rsot.asset", 1, ORG, "x".repeat(65), lifecycle, NOW, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new CanonicalObject(id(1), "rsot.asset", 1, ORG, "bad\u0001", lifecycle, NOW, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new CanonicalObject(id(1), "rsot.asset", 1, ORG, "1", lifecycle, NOW, NOW.minusSeconds(1)));
        CanonicalLifecycle beforeCreation = new CanonicalLifecycle(
                CanonicalObjectStatus.VALIDATED, null, NOW.minusSeconds(1), null, null, null);
        assertThrows(IllegalArgumentException.class,
                () -> new CanonicalObject(id(1), "rsot.asset", 1, ORG, "1", beforeCreation, NOW, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new CanonicalObject(id(1), "rsot\u0001.asset", 1, ORG, "1", lifecycle, NOW, NOW));
    }

    @Test
    void authorityPolicyRejectsUnboundedDuplicateAndMalformedConfigurations() {
        List<AuthorityContext> dcim = List.of(AuthorityContext.DCIM);
        assertThrows(NullPointerException.class,
                () -> new AttributeAuthorityPolicy(null, "rsot.asset", "location.site", AuthorityContext.DCIM, dcim, NOW, null, "1", "approval"));
        assertThrows(NullPointerException.class,
                () -> new AttributeAuthorityPolicy(id(1), "rsot.asset", "location.site", null, dcim, NOW, null, "1", "approval"));
        assertThrows(NullPointerException.class,
                () -> new AttributeAuthorityPolicy(id(1), "rsot.asset", "location.site", AuthorityContext.DCIM, null, NOW, null, "1", "approval"));
        assertThrows(IllegalArgumentException.class,
                () -> new AttributeAuthorityPolicy(id(1), "rsot.asset", "location.site", AuthorityContext.DCIM, List.of(), NOW, null, "1", "approval"));
        assertThrows(IllegalArgumentException.class,
                () -> new AttributeAuthorityPolicy(id(1), "rsot.asset", "location.site", AuthorityContext.DCIM,
                        List.of(AuthorityContext.DCIM, AuthorityContext.DCIM), NOW, null, "1", "approval"));
        assertThrows(IllegalArgumentException.class,
                () -> new AttributeAuthorityPolicy(id(1), "rsot.asset", "location.site", AuthorityContext.DCIM,
                        List.of(AuthorityContext.RSOT), NOW, null, "1", "approval"));
        assertThrows(NullPointerException.class,
                () -> new AttributeAuthorityPolicy(id(1), "rsot.asset", "location.site", AuthorityContext.DCIM, dcim, null, null, "1", "approval"));
        assertThrows(IllegalArgumentException.class,
                () -> new AttributeAuthorityPolicy(id(1), "rsot.asset", "location.site", AuthorityContext.DCIM, dcim, NOW, NOW, "1", "approval"));
        assertThrows(IllegalArgumentException.class,
                () -> new AttributeAuthorityPolicy(id(1), "rsot.*.*", "location.site", AuthorityContext.DCIM, dcim, NOW, null, "1", "approval"));
        assertThrows(IllegalArgumentException.class,
                () -> new AttributeAuthorityPolicy(id(1), "rsot*", "location.site", AuthorityContext.DCIM, dcim, NOW, null, "1", "approval"));
        assertThrows(IllegalArgumentException.class,
                () -> new AttributeAuthorityPolicy(id(1), "rsot.asset", "location*", AuthorityContext.DCIM, dcim, NOW, null, "1", "approval"));
        assertThrows(IllegalArgumentException.class,
                () -> new AttributeAuthorityPolicy(id(1), "rsot.asset", "location.site", AuthorityContext.DCIM, dcim, NOW, null, " ", "approval"));
        assertThrows(IllegalArgumentException.class,
                () -> new AttributeAuthorityPolicy(id(1), "rsot.asset", "location.site", AuthorityContext.DCIM, dcim, NOW, null, "x".repeat(65), "approval"));
        assertThrows(IllegalArgumentException.class,
                () -> new AttributeAuthorityPolicy(id(1), "rsot.asset", "location.site", AuthorityContext.DCIM, dcim, NOW, null, "1", "bad\u0001approval"));

        AttributeAuthorityPolicy bounded = new AttributeAuthorityPolicy(
                id(2), "rsot.*", "network.*", AuthorityContext.DDI, List.of(AuthorityContext.DDI),
                NOW, NOW.plusSeconds(10), " v1 ", " approval ");
        assertEquals("v1", bounded.policyVersion());
        assertEquals("approval", bounded.approvalRef());
        assertFalse(bounded.matches("other.asset", "network.ip"));
        assertFalse(bounded.matches("rsot.asset", "hardware.serial"));
        assertTrue(bounded.activeAt(NOW));
        assertFalse(bounded.activeAt(NOW.plusSeconds(10)));
        assertThrows(NullPointerException.class, () -> bounded.activeAt(null));
    }

    @Test
    void governanceValueObjectsAndRsotExceptionValidateTheirPublicContracts() {
        assertThrows(IllegalArgumentException.class,
                () -> new AuthorityMatrixEntry(0, "info", "authority", "contribution", "strategy", "v1"));
        assertThrows(NullPointerException.class,
                () -> new AuthorityMatrixEntry(1, null, "authority", "contribution", "strategy", "v1"));
        assertThrows(IllegalArgumentException.class,
                () -> new AuthorityMatrixEntry(1, " ", "authority", "contribution", "strategy", "v1"));
        assertThrows(IllegalArgumentException.class,
                () -> new AuthorityMatrixEntry(1, "x".repeat(501), "authority", "contribution", "strategy", "v1"));
        assertThrows(IllegalArgumentException.class,
                () -> new AuthorityMatrixEntry(1, "bad\u0001", "authority", "contribution", "strategy", "v1"));
        assertThrows(IllegalArgumentException.class,
                () -> new ContextRelationship(0, "IAM", "actors", false));
        assertThrows(NullPointerException.class,
                () -> new ContextRelationship(1, null, "actors", false));
        assertThrows(IllegalArgumentException.class,
                () -> new ContextRelationship(1, "IAM", " ", false));
        assertThrows(IllegalArgumentException.class,
                () -> new ContextRelationship(1, "IAM", "x".repeat(501), false));
        assertThrows(IllegalArgumentException.class,
                () -> new ContextRelationship(1, "IAM", "bad\u0001", false));

        RsotException error = new RsotException(" RSOT_TEST ", "message");
        assertEquals("RSOT_TEST", error.code());
        assertEquals("message", error.getMessage());
        assertThrows(NullPointerException.class, () -> new RsotException(null, "message"));
        assertThrows(NullPointerException.class, () -> new RsotException("RSOT_TEST", null));
        assertThrows(IllegalArgumentException.class, () -> new RsotException(" ", "message"));
        assertThrows(IllegalArgumentException.class, () -> new RsotException("x".repeat(97), "message"));
        assertThrows(IllegalArgumentException.class, () -> new RsotException("bad\u0001", "message"));
    }

    @Test
    void servicesRejectMissingDependenciesNullTimesAndInvalidPageEdges() {
        InMemoryRepository repository = new InMemoryRepository();
        assertThrows(NullPointerException.class, () -> new RsotQueryService(null));
        assertThrows(NullPointerException.class, () -> new RsotAuthorityService(null, Clock.systemUTC()));
        assertThrows(NullPointerException.class, () -> new RsotAuthorityService(repository, null));
        RsotAuthorityService authority = new RsotAuthorityService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThrows(NullPointerException.class, () -> authority.resolve("rsot.asset", "location.site", null));
        RsotQueryService query = new RsotQueryService(repository);
        assertThrows(NullPointerException.class, () -> query.get(null, false));
        assertThrows(IllegalArgumentException.class, () -> query.list(0, 0, false));
        assertThrows(IllegalArgumentException.class, () -> query.list(0, 201, false));
    }

    @Test
    void servicesExposeRepositoryBackedApprovedGovernanceCatalogues() {
        InMemoryRepository repository = new InMemoryRepository();
        RsotAuthorityService service = new RsotAuthorityService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
        assertEquals(InitialRsotGovernance.authorityMatrix(), service.authorityMatrix());
        assertEquals(InitialRsotGovernance.contextMap(), service.contextMap());
    }

    private static CanonicalObject object(int seed, CanonicalLifecycle lifecycle) {
        return new CanonicalObject(id(seed), "RSOT.Asset", 1, ORG, "1.0.0", lifecycle, NOW, NOW);
    }

    private static CanonicalLifecycle lifecycle(CanonicalObjectStatus status) {
        if (status == CanonicalObjectStatus.ARCHIVED) {
            return new CanonicalLifecycle(status, "retired", NOW, null, NOW.plusSeconds(1), id(777));
        }
        return new CanonicalLifecycle(status, null, NOW, null, null, null);
    }

    private static AttributeAuthorityPolicy policy(
            int seed, String objectType, String path, AuthorityContext authority, Instant from, Instant until) {
        return new AttributeAuthorityPolicy(
                id(seed), objectType, path, authority, List.of(authority), from, until, "1.0.0", "GOV-APPROVAL-1");
    }

    private static DomainIdentifier id(int seed) {
        String tail = String.format("%012x", seed);
        return DomainIdentifier.parse("019ffbda-2000-7000-8000-" + tail);
    }

    private static void assertCode(String expected, Runnable operation) {
        RsotException error = assertThrows(RsotException.class, operation::run);
        assertEquals(expected, error.code());
    }

    private static final class InMemoryRepository implements RsotRepository {
        private final List<CanonicalObject> objects = new ArrayList<>();
        private final List<AttributeAuthorityPolicy> policies = new ArrayList<>();

        @Override
        public Optional<CanonicalObject> findCanonicalObject(DomainIdentifier canonicalId) {
            return objects.stream().filter(object -> object.id().equals(canonicalId)).findFirst();
        }

        @Override
        public List<CanonicalObject> listCanonicalObjects(int offset, int limit) {
            return objects.stream().skip(offset).limit(limit).toList();
        }

        @Override
        public List<CanonicalObject> listCanonicalObjects(
                DomainIdentifier organizationId, int offset, int limit) {
            return objects.stream()
                    .filter(object -> object.organizationId().equals(organizationId))
                    .skip(offset)
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<AttributeAuthorityPolicy> authorityPolicies() {
            return List.copyOf(policies);
        }

        @Override
        public List<AuthorityMatrixEntry> authorityMatrix() {
            return InitialRsotGovernance.authorityMatrix();
        }

        @Override
        public List<ContextRelationship> contextMap() {
            return InitialRsotGovernance.contextMap();
        }
    }
}
