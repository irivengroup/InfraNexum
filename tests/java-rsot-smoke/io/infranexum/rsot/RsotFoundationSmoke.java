package io.infranexum.rsot;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.rsot.domain.AttributeAuthorityPolicy;
import io.infranexum.rsot.domain.AuthorityContext;
import io.infranexum.rsot.domain.CanonicalLifecycle;
import io.infranexum.rsot.domain.CanonicalObject;
import io.infranexum.rsot.domain.CanonicalObjectStatus;
import io.infranexum.rsot.domain.InitialRsotGovernance;
import java.time.Instant;
import java.util.List;

/** Dependency-free smoke for the RSOT authority/canonical foundation. */
public final class RsotFoundationSmoke {
    private RsotFoundationSmoke() {}

    public static void main(String[] args) {
        Instant now = Instant.parse("2026-08-13T16:00:00Z");
        DomainIdentifier id = DomainIdentifier.parse("019ffbda-2000-7000-8000-000000000001");
        CanonicalObject object = new CanonicalObject(
                id,
                "rsot.asset",
                1,
                DomainIdentifier.parse("019ffbda-2000-7000-8000-000000000002"),
                "1.0.0",
                new CanonicalLifecycle(CanonicalObjectStatus.VALIDATED, null, now, null, null, null),
                now,
                now);
        if (!object.lifecycle().status().consumerReadable()) throw new AssertionError("validated object not readable");
        if (InitialRsotGovernance.authorityMatrix().size() != 9) throw new AssertionError("authority matrix cardinality");
        if (InitialRsotGovernance.contextMap().size() != 10) throw new AssertionError("context map cardinality");
        if (InitialRsotGovernance.contextMap().stream().anyMatch(item -> item.directStorageWriteAllowed())) {
            throw new AssertionError("direct storage write unexpectedly allowed");
        }
        AttributeAuthorityPolicy policy = new AttributeAuthorityPolicy(
                DomainIdentifier.parse("019ffbda-2000-7000-8000-000000000003"),
                "rsot.asset",
                "network.*",
                AuthorityContext.DDI,
                List.of(AuthorityContext.DDI),
                now,
                null,
                "1.0.0",
                "GOV-1");
        if (!policy.matches("rsot.asset", "network.primary_ip")) throw new AssertionError("bounded policy mismatch");
    }
}
