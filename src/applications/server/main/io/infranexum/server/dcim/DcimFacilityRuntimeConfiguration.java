package io.infranexum.server.dcim;

import io.infranexum.adapters.persistence.jdbc.JdbcDatabaseDialect;
import io.infranexum.adapters.persistence.jdbc.JdbcFacilityIdempotencyRepository;
import io.infranexum.adapters.persistence.jdbc.JdbcFacilityRepository;
import io.infranexum.adapters.persistence.jdbc.JdbcOrganizationRepository;
import io.infranexum.adapters.persistence.jdbc.JdbcSubdivisionRepository;
import io.infranexum.adapters.persistence.jdbc.JdbcTransactionalEventStore;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.core.events.TransactionalEventStore;
import io.infranexum.dcim.facility.application.FacilityApplicationService;
import io.infranexum.dcim.facility.domain.FacilityKind;
import io.infranexum.dcim.facility.ports.FacilityFeaturePolicy;
import io.infranexum.identity.access.application.PolicyDecisionService;
import io.infranexum.identity.access.application.RbacAuthorizationService;
import io.infranexum.identity.access.ports.IdentityAccessFeaturePolicy;
import io.infranexum.identity.local.application.LocalAuthenticationService;
import io.infranexum.server.dcim.cli.DcimFacilityCli;
import io.infranexum.server.platform.PlatformCapabilityService;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Durable Server composition for PGM-07-E04 physical facility hierarchy. */
@Configuration(proxyBeanMethods = false)
public class DcimFacilityRuntimeConfiguration {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "infranexum.persistence.mode", havingValue = "POSTGRESQL")
    static class Postgresql {
        @Bean
        FacilityApplicationService facilityApplicationService(
                DataSource dataSource,
                TransactionalEventStore eventStore,
                PlatformCapabilityService capabilities,
                @Qualifier("platformClock") Clock clock,
                @Qualifier("correlationIdentifiers") UuidV7Generator identifiers) {
            return service(dataSource, eventStore, capabilities, clock, identifiers, JdbcDatabaseDialect.POSTGRESQL);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "infranexum.persistence.mode", havingValue = "ORACLE")
    static class Oracle {
        @Bean
        FacilityApplicationService facilityApplicationService(
                DataSource dataSource,
                TransactionalEventStore eventStore,
                PlatformCapabilityService capabilities,
                @Qualifier("platformClock") Clock clock,
                @Qualifier("correlationIdentifiers") UuidV7Generator identifiers) {
            return service(dataSource, eventStore, capabilities, clock, identifiers, JdbcDatabaseDialect.ORACLE);
        }
    }

    @Bean
    @ConditionalOnBean(FacilityApplicationService.class)
    DcimFacilityCli dcimFacilityCli(
            LocalAuthenticationService authentication,
            RbacAuthorizationService authorization,
            PolicyDecisionService policyDecisions,
            IdentityAccessFeaturePolicy features,
            PlatformCapabilityService capabilities,
            FacilityApplicationService facilities,
            @Qualifier("correlationIdentifiers") UuidV7Generator identifiers) {
        return new DcimFacilityCli(authentication, authorization, policyDecisions, features, capabilities, facilities, identifiers);
    }

    private static FacilityApplicationService service(
            DataSource dataSource,
            TransactionalEventStore eventStore,
            PlatformCapabilityService capabilities,
            @Qualifier("platformClock") Clock clock,
            UuidV7Generator identifiers,
            JdbcDatabaseDialect dialect) {
        if (!(eventStore instanceof JdbcTransactionalEventStore jdbcEvents)) {
            throw new IllegalStateException("DCIM facilities require durable JDBC transactional events");
        }
        JdbcOrganizationRepository organizations = new JdbcOrganizationRepository(dataSource, jdbcEvents, dialect);
        JdbcSubdivisionRepository subdivisions = new JdbcSubdivisionRepository(dataSource, jdbcEvents, dialect);
        return new FacilityApplicationService(
                new JdbcFacilityRepository(dataSource, jdbcEvents, dialect),
                new JdbcFacilityIdempotencyRepository(jdbcEvents, dialect),
                featurePolicy(capabilities),
                new DcimFacilityScopePolicy(organizations, subdivisions),
                eventStore,
                identifiers,
                clock);
    }

    static FacilityFeaturePolicy featurePolicy(PlatformCapabilityService capabilities) {
        return new FacilityFeaturePolicy() {
            @Override
            public boolean facilitiesEnabled() {
                return capabilities.explain("dcim.facilities").available();
            }

            @Override
            public long limit(FacilityKind kind) {
                return switch (kind) {
                    case SITE -> capabilities.quotaPlan().limit("dcim.sites.max");
                    case BUILDING -> capabilities.quotaPlan().limit("dcim.buildings.max");
                    case ROOM -> capabilities.quotaPlan().limit("dcim.rooms.max");
                    case FLOOR, ZONE -> Long.MAX_VALUE;
                };
            }
        };
    }
}
