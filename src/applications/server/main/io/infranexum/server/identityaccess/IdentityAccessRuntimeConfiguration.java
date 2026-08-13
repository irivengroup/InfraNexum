package io.infranexum.server.identityaccess;

import io.infranexum.adapters.persistence.jdbc.JdbcAuditJournal;
import io.infranexum.adapters.persistence.jdbc.JdbcDatabaseDialect;
import io.infranexum.adapters.persistence.jdbc.JdbcIdentityAccessRepository;
import io.infranexum.adapters.persistence.jdbc.JdbcLocalIdentityRepository;
import io.infranexum.adapters.persistence.jdbc.JdbcTransactionalEventStore;
import io.infranexum.core.audit.AuditJournal;
import io.infranexum.core.capabilities.InstallationProfile;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.core.events.TransactionalEventStore;
import io.infranexum.identity.access.application.IdentityAccessAdminService;
import io.infranexum.identity.access.application.RbacAuthorizationService;
import io.infranexum.identity.access.domain.IdentityUser;
import io.infranexum.identity.access.ports.IdentityAccessFeaturePolicy;
import io.infranexum.server.identity.LocalAuthRuntimeProperties;
import io.infranexum.identity.local.application.LocalAuthenticationService;
import io.infranexum.server.identityaccess.cli.IdentityAccessCli;
import io.infranexum.server.persistence.PersistenceMode;
import io.infranexum.server.persistence.PersistenceRuntimeProperties;
import io.infranexum.server.platform.PlatformCapabilityService;
import java.security.SecureRandom;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/** Composes PGM-03-E03 whenever local authentication exposes protected Server APIs. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "infranexum.identity.local.enabled", havingValue = "true")
public class IdentityAccessRuntimeConfiguration {
    @Bean
    JdbcIdentityAccessRepository identityAccessRepository(
            DataSource dataSource,
            TransactionalEventStore eventStore,
            PersistenceRuntimeProperties persistence) {
        if (!(eventStore instanceof JdbcTransactionalEventStore jdbcEventStore)) {
            throw new IllegalStateException("RBAC requires durable JDBC persistence");
        }
        return new JdbcIdentityAccessRepository(dataSource, jdbcEventStore, dialect(persistence.mode()));
    }

    @Bean
    AuditJournal identityAccessAuditJournal(
            DataSource dataSource,
            TransactionalEventStore eventStore,
            PersistenceRuntimeProperties persistence) {
        if (!(eventStore instanceof JdbcTransactionalEventStore jdbcEventStore)) {
            throw new IllegalStateException("RBAC audit requires durable JDBC persistence");
        }
        return new JdbcAuditJournal(dataSource, dialect(persistence.mode()), jdbcEventStore);
    }

    @Bean
    IdentityAccessFeaturePolicy identityAccessFeaturePolicy(PlatformCapabilityService capabilities) {
        return new IdentityAccessFeaturePolicy() {
            private InstallationProfile profile() { return capabilities.quotaPlan().profile(); }
            @Override public boolean supportsNestedGroups() { return profile() != InstallationProfile.LITE; }
            @Override public boolean supportsMultiMembership() { return profile() != InstallationProfile.LITE; }
        };
    }

    @Bean
    IdentityAccessAdminService identityAccessAdministrationService(
            JdbcIdentityAccessRepository repository,
            IdentityAccessFeaturePolicy features,
            TransactionalEventStore events,
            AuditJournal audit,
            @Qualifier("platformClock") Clock clock) {
        return new IdentityAccessAdminService(
                repository, features, events, audit, new UuidV7Generator(clock, new SecureRandom()), clock);
    }

    @Bean
    RbacAuthorizationService rbacAuthorizationService(
            JdbcIdentityAccessRepository repository,
            AuditJournal audit,
            @Qualifier("platformClock") Clock clock) {
        return new RbacAuthorizationService(repository, audit, new UuidV7Generator(clock, new SecureRandom()), clock);
    }

    @Bean
    RbacAuthorizationFilter rbacAuthorizationFilter(RbacAuthorizationService authorization) {
        return new RbacAuthorizationFilter(authorization);
    }

    @Bean
    IdentityAccessCli identityAccessCli(
            LocalAuthenticationService authentication,
            IdentityAccessAdminService administration,
            RbacAuthorizationService authorization,
            @Qualifier("correlationIdentifiers") UuidV7Generator identifiers) {
        return new IdentityAccessCli(authentication, administration, authorization, identifiers);
    }

    @Bean
    @Order(200)
    ApplicationRunner identityAccessBootstrap(
            IdentityAccessAdminService service,
            JdbcLocalIdentityRepository localIdentities,
            LocalAuthRuntimeProperties properties) {
        return arguments -> localIdentities.findByUsername(IdentityUser.canonicalLogin(properties.bootstrapUsername()))
                .ifPresent(account -> service.ensureBootstrapPlatformAdministrator(
                        account.id(), account.username(), account.displayName()));
    }

    private static JdbcDatabaseDialect dialect(PersistenceMode mode) {
        return switch (mode) {
            case POSTGRESQL -> JdbcDatabaseDialect.POSTGRESQL;
            case ORACLE -> JdbcDatabaseDialect.ORACLE;
            case MEMORY -> throw new IllegalStateException("RBAC requires durable JDBC persistence");
        };
    }
}
