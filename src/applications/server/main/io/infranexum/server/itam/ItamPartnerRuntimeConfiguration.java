package io.infranexum.server.itam;

import io.infranexum.adapters.persistence.jdbc.JdbcDatabaseDialect;
import io.infranexum.adapters.persistence.jdbc.JdbcOrganizationRepository;
import io.infranexum.adapters.persistence.jdbc.JdbcPartnerIdempotencyRepository;
import io.infranexum.adapters.persistence.jdbc.JdbcPartnerRepository;
import io.infranexum.adapters.persistence.jdbc.JdbcSubdivisionRepository;
import io.infranexum.adapters.persistence.jdbc.JdbcTransactionalEventStore;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.core.events.TransactionalEventStore;
import io.infranexum.itam.partner.application.PartnerApplicationService;
import io.infranexum.identity.access.application.PolicyDecisionService;
import io.infranexum.identity.access.application.RbacAuthorizationService;
import io.infranexum.identity.access.ports.IdentityAccessFeaturePolicy;
import io.infranexum.identity.local.application.LocalAuthenticationService;
import io.infranexum.server.itam.cli.ItamPartnerCli;
import io.infranexum.itam.partner.ports.PartnerFeaturePolicy;
import io.infranexum.server.platform.PlatformCapabilityService;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Durable runtime composition for PGM-07-E01 Partner catalogues. */
@Configuration(proxyBeanMethods = false)
public class ItamPartnerRuntimeConfiguration {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "infranexum.persistence.mode", havingValue = "POSTGRESQL")
    static class Postgresql {
        @Bean
        PartnerApplicationService partnerApplicationService(
                DataSource dataSource, TransactionalEventStore eventStore, PlatformCapabilityService capabilities,
                @Qualifier("platformClock") Clock clock,
                @Qualifier("correlationIdentifiers") UuidV7Generator identifiers) {
            return service(dataSource, eventStore, capabilities, clock, identifiers, JdbcDatabaseDialect.POSTGRESQL);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "infranexum.persistence.mode", havingValue = "ORACLE")
    static class Oracle {
        @Bean
        PartnerApplicationService partnerApplicationService(
                DataSource dataSource, TransactionalEventStore eventStore, PlatformCapabilityService capabilities,
                @Qualifier("platformClock") Clock clock,
                @Qualifier("correlationIdentifiers") UuidV7Generator identifiers) {
            return service(dataSource, eventStore, capabilities, clock, identifiers, JdbcDatabaseDialect.ORACLE);
        }
    }

    @Bean
    @ConditionalOnBean(PartnerApplicationService.class)
    ItamPartnerCli itamPartnerCli(
            LocalAuthenticationService authentication,
            RbacAuthorizationService authorization,
            PolicyDecisionService policyDecisions,
            IdentityAccessFeaturePolicy features,
            PlatformCapabilityService capabilities,
            PartnerApplicationService partners,
            @Qualifier("correlationIdentifiers") UuidV7Generator identifiers) {
        return new ItamPartnerCli(
                authentication, authorization, policyDecisions, features, capabilities, partners, identifiers);
    }

    private static PartnerApplicationService service(
            DataSource dataSource, TransactionalEventStore eventStore, PlatformCapabilityService capabilities,
            @Qualifier("platformClock") Clock clock, UuidV7Generator identifiers, JdbcDatabaseDialect dialect) {
        if (!(eventStore instanceof JdbcTransactionalEventStore jdbcEvents)) {
            throw new IllegalStateException("ITAM Partner catalogue requires durable JDBC transactional events");
        }
        JdbcOrganizationRepository organizations = new JdbcOrganizationRepository(dataSource, jdbcEvents, dialect);
        JdbcSubdivisionRepository subdivisions = new JdbcSubdivisionRepository(dataSource, jdbcEvents, dialect);
        return new PartnerApplicationService(
                new JdbcPartnerRepository(dataSource, jdbcEvents, dialect),
                new JdbcPartnerIdempotencyRepository(jdbcEvents, dialect),
                featurePolicy(capabilities),
                new JdbcPartnerGovernanceScope(organizations, subdivisions),
                eventStore,
                identifiers,
                clock);
    }

    static PartnerFeaturePolicy featurePolicy(PlatformCapabilityService capabilities) {
        return new PartnerFeaturePolicy() {
            @Override
            public boolean partnerCatalogueEnabled() {
                return capabilities.explain("itam.partners").available();
            }

            @Override
            public long partnerLimit() {
                return capabilities.quotaPlan().limit("itam.partners.max");
            }
        };
    }
}
