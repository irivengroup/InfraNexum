package io.infranexum.server.organization;

import io.infranexum.adapters.persistence.jdbc.JdbcDatabaseDialect;
import io.infranexum.adapters.persistence.jdbc.JdbcOrganizationIdempotencyRepository;
import io.infranexum.adapters.persistence.jdbc.JdbcOrganizationRepository;
import io.infranexum.adapters.persistence.jdbc.JdbcSubdivisionRepository;
import io.infranexum.adapters.persistence.jdbc.JdbcTemporalScopeRepository;
import io.infranexum.adapters.persistence.jdbc.JdbcTransactionalEventStore;
import io.infranexum.core.capabilities.InstallationProfile;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.core.events.TransactionalEventStore;
import io.infranexum.organization.application.OrganizationApplicationService;
import io.infranexum.organization.ports.OrganizationFeaturePolicy;
import io.infranexum.server.persistence.PersistenceRuntimeProperties;
import io.infranexum.server.platform.PlatformCapabilityService;
import java.security.SecureRandom;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composes the Organization context when enabled; Server RBAC protects its HTTP surface. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OrganizationRuntimeProperties.class)
@ConditionalOnProperty(name = "infranexum.organization.api-enabled", havingValue = "true")
public class OrganizationRuntimeConfiguration {
    @Bean
    OrganizationApplicationService organizationApplicationService(
            DataSource dataSource,
            TransactionalEventStore eventStore,
            PlatformCapabilityService capabilities,
            PersistenceRuntimeProperties persistence,
            @Qualifier("platformClock") Clock clock) {
        if (!(eventStore instanceof JdbcTransactionalEventStore jdbcEventStore)) {
            throw new IllegalStateException("Organization foundation requires JDBC persistence");
        }

        JdbcDatabaseDialect dialect = switch (persistence.mode()) {
            case POSTGRESQL -> JdbcDatabaseDialect.POSTGRESQL;
            case ORACLE -> JdbcDatabaseDialect.ORACLE;
            case MEMORY -> throw new IllegalStateException(
                    "Organization foundation does not support MEMORY persistence");
        };

        return new OrganizationApplicationService(
                new JdbcOrganizationRepository(dataSource, jdbcEventStore, dialect),
                new JdbcSubdivisionRepository(dataSource, jdbcEventStore, dialect),
                new JdbcTemporalScopeRepository(dataSource, jdbcEventStore, dialect),
                new JdbcOrganizationIdempotencyRepository(jdbcEventStore, dialect),
                featurePolicy(capabilities),
                eventStore,
                new UuidV7Generator(clock, new SecureRandom()),
                clock);
    }

    static OrganizationFeaturePolicy featurePolicy(PlatformCapabilityService service) {
        var plan = service.quotaPlan();
        InstallationProfile profile = plan.profile();
        return new OrganizationFeaturePolicy() {
            @Override
            public boolean supportsOrganizationHierarchy() {
                return profile == InstallationProfile.ENTERPRISE;
            }

            @Override
            public boolean supportsSubdivisions() {
                return profile != InstallationProfile.LITE;
            }

            @Override
            public long organizationLimit() {
                return plan.limit("organization.organizations.max");
            }

            @Override
            public long subdivisionLimit() {
                return plan.limit("organization.subdivisions.max");
            }

            @Override
            public long hierarchyDepthLimit() {
                return plan.limit("organization.hierarchy_depth.max");
            }
        };
    }
}
