package io.infranexum.server.identityaccess;

import io.infranexum.adapters.persistence.jdbc.JdbcAccessPolicyRepository;
import io.infranexum.adapters.persistence.jdbc.JdbcAuditJournal;
import io.infranexum.adapters.persistence.jdbc.JdbcDatabaseDialect;
import io.infranexum.adapters.persistence.jdbc.JdbcIdentityAccessRepository;
import io.infranexum.adapters.persistence.jdbc.JdbcOrganizationRepository;
import io.infranexum.adapters.persistence.jdbc.JdbcSubdivisionRepository;
import io.infranexum.adapters.persistence.jdbc.JdbcLocalIdentityRepository;
import io.infranexum.adapters.persistence.jdbc.JdbcTransactionalEventStore;
import io.infranexum.core.audit.AuditJournal;
import io.infranexum.core.capabilities.InstallationProfile;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.core.events.TransactionalEventStore;
import io.infranexum.identity.access.application.IdentityAccessAdminService;
import io.infranexum.identity.access.application.PolicyAdministrationService;
import io.infranexum.identity.access.application.PolicyDecisionService;
import io.infranexum.identity.access.application.SeparationOfDutyService;
import io.infranexum.identity.access.application.RbacAuthorizationService;
import io.infranexum.identity.access.domain.IdentityUser;
import io.infranexum.identity.access.ports.IdentityAccessFeaturePolicy;
import io.infranexum.identity.access.ports.OrganizationScopeReferencePort;
import io.infranexum.identity.access.ports.PolicyDecisionObserver;
import io.infranexum.identity.access.ports.PolicyInformationPort;
import io.infranexum.identity.access.ports.RoleAssignmentPolicyGuard;
import io.infranexum.server.http.ApiProblemSupport;
import io.infranexum.server.identity.LocalAuthRuntimeProperties;
import io.infranexum.identity.local.application.LocalAuthenticationService;
import io.infranexum.server.identityaccess.cli.IdentityAccessCli;
import io.infranexum.server.persistence.PersistenceMode;
import io.infranexum.server.persistence.PersistenceRuntimeProperties;
import io.infranexum.server.platform.PlatformCapabilityService;
import java.security.SecureRandom;
import java.time.Clock;
import javax.sql.DataSource;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/** Composes RBAC and Pro/Enterprise PGM-03-E04 advanced authorization over durable IAM storage. */
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
            @Override public boolean supportsAdvancedAuthorization() { return profile() != InstallationProfile.LITE; }
        };
    }

    @Bean
    OrganizationScopeReferencePort identityAccessOrganizationScopeReferences(
            DataSource dataSource,
            TransactionalEventStore eventStore,
            PersistenceRuntimeProperties persistence) {
        if (!(eventStore instanceof JdbcTransactionalEventStore jdbcEventStore)) {
            throw new IllegalStateException("IAM organization references require durable JDBC persistence");
        }
        JdbcDatabaseDialect databaseDialect = dialect(persistence.mode());
        return new OrganizationScopeReferenceAdapter(
                new JdbcOrganizationRepository(dataSource, jdbcEventStore, databaseDialect),
                new JdbcSubdivisionRepository(dataSource, jdbcEventStore, databaseDialect));
    }

    @Bean
    JdbcAccessPolicyRepository accessPolicyRepository(
            DataSource dataSource,
            TransactionalEventStore eventStore,
            PersistenceRuntimeProperties persistence) {
        if (!(eventStore instanceof JdbcTransactionalEventStore jdbcEventStore)) {
            throw new IllegalStateException("advanced authorization requires durable JDBC persistence");
        }
        return new JdbcAccessPolicyRepository(dataSource, jdbcEventStore, dialect(persistence.mode()));
    }

    @Bean
    RoleAssignmentPolicyGuard roleAssignmentPolicyGuard(
            JdbcAccessPolicyRepository policies,
            JdbcIdentityAccessRepository identities,
            IdentityAccessFeaturePolicy features) {
        return new SeparationOfDutyService(policies, identities, features);
    }

    @Bean
    IdentityAccessAdminService identityAccessAdministrationService(
            JdbcIdentityAccessRepository repository,
            IdentityAccessFeaturePolicy features,
            OrganizationScopeReferencePort organizationScopes,
            RoleAssignmentPolicyGuard assignmentGuard,
            TransactionalEventStore events,
            AuditJournal audit,
            @Qualifier("platformClock") Clock clock) {
        return new IdentityAccessAdminService(
                repository, features, organizationScopes, assignmentGuard, events, audit,
                new UuidV7Generator(clock, new SecureRandom()), clock);
    }

    @Bean
    PolicyInformationPort policyInformationPort(
            JdbcIdentityAccessRepository identities,
            PlatformCapabilityService capabilities) {
        return new ServerPolicyInformationPort(identities, capabilities);
    }

    @Bean
    PolicyDecisionObserver policyDecisionObserver(MeterRegistry registry) {
        return new PolicyDecisionMetrics(registry);
    }

    @Bean
    PolicyDecisionService policyDecisionService(
            JdbcAccessPolicyRepository policies,
            PolicyInformationPort information,
            IdentityAccessFeaturePolicy features,
            PolicyDecisionObserver observer,
            AuditJournal audit,
            @Qualifier("platformClock") Clock clock) {
        return new PolicyDecisionService(policies, information, features, observer, audit,
                new UuidV7Generator(clock, new SecureRandom()), clock);
    }

    @Bean
    PolicyAdministrationService policyAdministrationService(
            JdbcAccessPolicyRepository policies,
            JdbcIdentityAccessRepository identities,
            IdentityAccessFeaturePolicy features,
            OrganizationScopeReferencePort organizationScopes,
            TransactionalEventStore events,
            AuditJournal audit,
            @Qualifier("platformClock") Clock clock) {
        return new PolicyAdministrationService(policies, identities, features, organizationScopes, events, audit,
                new UuidV7Generator(clock, new SecureRandom()), clock);
    }

    @Bean
    RbacAuthorizationService rbacAuthorizationService(
            JdbcIdentityAccessRepository repository,
            AuditJournal audit,
            @Qualifier("platformClock") Clock clock) {
        return new RbacAuthorizationService(repository, audit, new UuidV7Generator(clock, new SecureRandom()), clock);
    }

    @Bean
    RbacAuthorizationFilter rbacAuthorizationFilter(RbacAuthorizationService authorization, ApiProblemSupport problems) {
        return new RbacAuthorizationFilter(authorization, problems);
    }

    @Bean
    AdvancedAuthorizationFilter advancedAuthorizationFilter(
            PolicyDecisionService decisions,
            IdentityAccessFeaturePolicy features,
            PlatformCapabilityService capabilities,
            ApiProblemSupport problems) {
        return new AdvancedAuthorizationFilter(decisions, features, capabilities, problems);
    }

    @Bean
    ScopedAuthorizationGuard scopedAuthorizationGuard(
            RbacAuthorizationService authorization,
            PolicyDecisionService decisions,
            IdentityAccessFeaturePolicy features,
            PlatformCapabilityService capabilities) {
        return new ScopedAuthorizationGuard(authorization, decisions, features, capabilities);
    }

    @Bean
    IdentityAccessCli identityAccessCli(
            LocalAuthenticationService authentication,
            IdentityAccessAdminService administration,
            RbacAuthorizationService authorization,
            PolicyDecisionService policyDecisions,
            IdentityAccessFeaturePolicy features,
            PlatformCapabilityService capabilities,
            @Qualifier("correlationIdentifiers") UuidV7Generator identifiers) {
        return new IdentityAccessCli(authentication, administration, authorization, policyDecisions, features, capabilities, identifiers);
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
