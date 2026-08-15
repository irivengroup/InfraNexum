package io.infranexum.server.itam;

import io.infranexum.adapters.persistence.jdbc.JdbcAssetIdempotencyRepository;
import io.infranexum.adapters.persistence.jdbc.JdbcAssetRepository;
import io.infranexum.adapters.persistence.jdbc.JdbcDatabaseDialect;
import io.infranexum.adapters.persistence.jdbc.JdbcOrganizationRepository;
import io.infranexum.adapters.persistence.jdbc.JdbcSubdivisionRepository;
import io.infranexum.adapters.persistence.jdbc.JdbcTransactionalEventStore;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.core.events.TransactionalEventStore;
import io.infranexum.itam.asset.application.AssetApplicationService;
import io.infranexum.itam.compliance.application.ComplianceApplicationService;
import io.infranexum.identity.access.application.PolicyDecisionService;
import io.infranexum.identity.access.application.RbacAuthorizationService;
import io.infranexum.identity.access.ports.IdentityAccessFeaturePolicy;
import io.infranexum.identity.local.application.LocalAuthenticationService;
import io.infranexum.server.itam.cli.ItamAssetCli;
import io.infranexum.itam.asset.ports.AssetFeaturePolicy;
import io.infranexum.itam.partner.application.PartnerApplicationService;
import io.infranexum.rsot.application.RsotQueryService;
import io.infranexum.server.platform.PlatformCapabilityService;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Durable runtime composition for PGM-07-E02 ITAM asset lifecycle. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBean({PartnerApplicationService.class, RsotQueryService.class})
public class ItamAssetRuntimeConfiguration {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "infranexum.persistence.mode", havingValue = "POSTGRESQL")
    static class Postgresql {
        @Bean
        AssetApplicationService assetApplicationService(
                DataSource dataSource, TransactionalEventStore eventStore, PlatformCapabilityService capabilities,
                PartnerApplicationService partners, RsotQueryService rsot, ComplianceApplicationService compliance,
                @Qualifier("platformClock") Clock clock,
                @Qualifier("correlationIdentifiers") UuidV7Generator identifiers) {
            return service(dataSource, eventStore, capabilities, partners, rsot, compliance, clock, identifiers,
                    JdbcDatabaseDialect.POSTGRESQL);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "infranexum.persistence.mode", havingValue = "ORACLE")
    static class Oracle {
        @Bean
        AssetApplicationService assetApplicationService(
                DataSource dataSource, TransactionalEventStore eventStore, PlatformCapabilityService capabilities,
                PartnerApplicationService partners, RsotQueryService rsot, ComplianceApplicationService compliance,
                @Qualifier("platformClock") Clock clock,
                @Qualifier("correlationIdentifiers") UuidV7Generator identifiers) {
            return service(dataSource, eventStore, capabilities, partners, rsot, compliance, clock, identifiers,
                    JdbcDatabaseDialect.ORACLE);
        }
    }

    @Bean
    @ConditionalOnBean(AssetApplicationService.class)
    ItamAssetCli itamAssetCli(
            LocalAuthenticationService authentication, RbacAuthorizationService authorization,
            PolicyDecisionService policyDecisions, IdentityAccessFeaturePolicy features,
            PlatformCapabilityService capabilities, AssetApplicationService assets,
            @Qualifier("correlationIdentifiers") UuidV7Generator identifiers) {
        return new ItamAssetCli(authentication, authorization, policyDecisions, features, capabilities, assets, identifiers);
    }

    private static AssetApplicationService service(
            DataSource dataSource, TransactionalEventStore eventStore, PlatformCapabilityService capabilities,
            PartnerApplicationService partners, RsotQueryService rsot, ComplianceApplicationService compliance, @Qualifier("platformClock") Clock clock, UuidV7Generator identifiers,
            JdbcDatabaseDialect dialect) {
        if (!(eventStore instanceof JdbcTransactionalEventStore jdbcEvents)) {
            throw new IllegalStateException("ITAM asset lifecycle requires durable JDBC transactional events");
        }
        JdbcOrganizationRepository organizations = new JdbcOrganizationRepository(dataSource, jdbcEvents, dialect);
        JdbcSubdivisionRepository subdivisions = new JdbcSubdivisionRepository(dataSource, jdbcEvents, dialect);
        return new AssetApplicationService(
                new JdbcAssetRepository(dataSource, jdbcEvents, dialect),
                new JdbcAssetIdempotencyRepository(jdbcEvents, dialect),
                featurePolicy(capabilities),
                new ItamAssetReferencePolicy(rsot, organizations, subdivisions, partners),
                new ItamComplianceReadinessPolicy(compliance, clock), eventStore, identifiers, clock);
    }

    static AssetFeaturePolicy featurePolicy(PlatformCapabilityService capabilities) {
        return new AssetFeaturePolicy() {
            @Override
            public boolean assetLifecycleEnabled() {
                return capabilities.explain("itam.assets").available();
            }

            @Override
            public long assetLimit() {
                return capabilities.quotaPlan().limit("itam.assets.max");
            }
        };
    }
}
