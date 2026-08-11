package io.infranexum.server.platform.entitlements;

import io.infranexum.adapters.persistence.jdbc.FileIntegrityProofStore;
import io.infranexum.adapters.persistence.jdbc.JdbcActivationOperationalRepository;
import io.infranexum.adapters.persistence.jdbc.JdbcDatabaseDialect;
import io.infranexum.adapters.persistence.jdbc.JdbcRevocationRegistry;
import io.infranexum.core.capabilities.CapabilityCatalog;
import io.infranexum.core.capabilities.InstallationProfile;
import io.infranexum.core.capabilities.QuotaCatalog;
import io.infranexum.core.contracts.ConfigurationException;
import io.infranexum.core.entitlements.ActivationContextFactory;
import io.infranexum.core.entitlements.ActivationImportCoordinator;
import io.infranexum.core.entitlements.ActivationManifestCodec;
import io.infranexum.core.entitlements.ActivationManifestVerifier;
import io.infranexum.core.entitlements.EntitlementGuard;
import io.infranexum.core.entitlements.EntitlementRuntimeAuthority;
import io.infranexum.core.entitlements.IndependentIntegrityProofStore;
import io.infranexum.core.entitlements.LiteEvaluationPolicy;
import io.infranexum.core.entitlements.RevocationRegistry;
import io.infranexum.core.entitlements.TrustedKeyStore;
import io.infranexum.core.entitlements.TrustedTimeGuard;
import io.infranexum.server.persistence.PersistenceMode;
import io.infranexum.server.persistence.PersistenceRuntimeProperties;
import io.infranexum.server.platform.PlatformCapabilityProperties;
import io.infranexum.server.platform.PlatformCapabilityService;
import java.time.Clock;
import java.util.Optional;
import javax.crypto.SecretKey;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.ObjectMapper;

/** Authoritative Spring composition root for signed entitlements and Lite lifecycle enforcement. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(ActivationRuntimeProperties.class)
@ConditionalOnProperty(
        name = "infranexum.entitlements.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ActivationRuntimeConfiguration {
    @Bean
    Clock entitlementClock() {
        return Clock.systemUTC();
    }

    @Bean
    ActivationManifestCodec activationManifestCodec(
            ObjectMapper mapper, ActivationRuntimeProperties properties) {
        return new ActivationManifestJsonCodec(mapper, properties.maxManifestBytes());
    }

    @Bean
    SecretKey entitlementIntegrityKey(ActivationRuntimeProperties properties) {
        return new IntegritySecretLoader().load(properties.integrityKeyPath());
    }

    @Bean
    TrustedKeyStore activationTrustedKeyStore(
            ObjectMapper mapper,
            ActivationRuntimeProperties properties,
            PlatformCapabilityProperties platform) {
        if (platform.profile() == InstallationProfile.LITE) {
            return keyId -> Optional.empty();
        }
        if (properties.trustStorePath() == null) {
            throw new ConfigurationException("Pro and Enterprise require an external activation trust store");
        }
        return new ActivationTrustStoreLoader(mapper).load(properties.trustStorePath());
    }

    @Bean
    JdbcDatabaseDialect activationJdbcDialect(PersistenceRuntimeProperties persistence) {
        return switch (persistence.mode()) {
            case POSTGRESQL -> JdbcDatabaseDialect.POSTGRESQL;
            case ORACLE -> JdbcDatabaseDialect.ORACLE;
            case MEMORY -> throw new ConfigurationException(
                    "authoritative entitlements require PostgreSQL or Oracle persistence");
        };
    }

    @Bean
    JdbcActivationOperationalRepository activationOperationalRepository(
            DataSource dataSource, JdbcDatabaseDialect activationJdbcDialect) {
        return new JdbcActivationOperationalRepository(dataSource, activationJdbcDialect);
    }

    @Bean
    IndependentIntegrityProofStore independentIntegrityProofStore(ActivationRuntimeProperties properties) {
        return new FileIntegrityProofStore(properties.proofDirectory());
    }

    @Bean
    RevocationRegistry activationRevocationRegistry(
            DataSource dataSource, JdbcDatabaseDialect activationJdbcDialect) {
        return new JdbcRevocationRegistry(dataSource, activationJdbcDialect);
    }

    @Bean
    ActivationManifestVerifier activationManifestVerifier() {
        return new ActivationManifestVerifier();
    }

    @Bean
    TrustedTimeGuard trustedTimeGuard() {
        return new TrustedTimeGuard();
    }

    @Bean
    LiteEvaluationPolicy liteEvaluationPolicy() {
        return new LiteEvaluationPolicy();
    }

    @Bean
    EntitlementGuard entitlementGuard() {
        return new EntitlementGuard();
    }

    @Bean
    ActivationContextFactory activationContextFactory(
            ActivationRuntimeProperties properties,
            PlatformCapabilityProperties platform,
            CapabilityCatalog capabilityCatalog,
            QuotaCatalog quotaCatalog,
            TrustedKeyStore activationTrustedKeyStore,
            RevocationRegistry activationRevocationRegistry) {
        return (identity, sequence, now) -> new io.infranexum.core.entitlements.ActivationValidationContext(
                identity,
                properties.customerId(),
                platform.profile(),
                platform.catalogVersion(),
                capabilityCatalog,
                quotaCatalog,
                sequence,
                activationTrustedKeyStore,
                activationRevocationRegistry,
                now);
    }

    @Bean
    EntitlementRuntimeAuthority entitlementRuntimeAuthority(
            JdbcActivationOperationalRepository repository,
            IndependentIntegrityProofStore independentIntegrityProofStore,
            ActivationManifestVerifier activationManifestVerifier,
            ActivationContextFactory activationContextFactory,
            ActivationManifestCodec activationManifestCodec,
            TrustedTimeGuard trustedTimeGuard,
            LiteEvaluationPolicy liteEvaluationPolicy,
            EntitlementGuard entitlementGuard,
            SecretKey entitlementIntegrityKey,
            Clock entitlementClock) {
        return new EntitlementRuntimeAuthority(
                repository,
                independentIntegrityProofStore,
                activationManifestVerifier,
                activationContextFactory,
                activationManifestCodec,
                trustedTimeGuard,
                liteEvaluationPolicy,
                entitlementGuard,
                entitlementIntegrityKey,
                entitlementClock);
    }

    @Bean
    ActivationImportCoordinator activationImportCoordinator(
            JdbcActivationOperationalRepository repository,
            IndependentIntegrityProofStore independentIntegrityProofStore,
            ActivationManifestVerifier activationManifestVerifier,
            ActivationContextFactory activationContextFactory,
            TrustedTimeGuard trustedTimeGuard,
            SecretKey entitlementIntegrityKey,
            Clock entitlementClock) {
        return new ActivationImportCoordinator(
                repository,
                independentIntegrityProofStore,
                activationManifestVerifier,
                activationContextFactory,
                trustedTimeGuard,
                entitlementIntegrityKey,
                entitlementClock);
    }

    @Bean
    ActivationAdministrationService activationAdministrationService(
            ActivationManifestCodec activationManifestCodec,
            ActivationImportCoordinator activationImportCoordinator,
            EntitlementRuntimeAuthority entitlementRuntimeAuthority,
            PlatformCapabilityProperties platform,
            PlatformCapabilityService capabilityService) {
        return new ActivationAdministrationService(
                activationManifestCodec,
                activationImportCoordinator,
                entitlementRuntimeAuthority,
                platform,
                capabilityService);
    }

    @Bean
    EntitlementWebServerStartupGuard entitlementWebServerStartupGuard(
            EntitlementRuntimeAuthority authority,
            PlatformCapabilityProperties platform,
            PlatformCapabilityService capabilityService) {
        return new EntitlementWebServerStartupGuard(authority, platform, capabilityService);
    }

    @Bean
    EntitlementMutationInterceptor entitlementMutationInterceptor(EntitlementRuntimeAuthority authority) {
        return new EntitlementMutationInterceptor(authority);
    }

    @Bean
    EntitlementWebMvcConfiguration entitlementWebMvcConfiguration(
            EntitlementMutationInterceptor interceptor) {
        return new EntitlementWebMvcConfiguration(interceptor);
    }

    @Bean
    EntitlementRefreshScheduler entitlementRefreshScheduler(
            EntitlementRuntimeAuthority authority,
            PlatformCapabilityProperties platform,
            PlatformCapabilityService capabilityService,
            ConfigurableApplicationContext context) {
        return new EntitlementRefreshScheduler(authority, platform, capabilityService, context);
    }

    @Bean
    EntitlementHealthIndicator entitlementHealthIndicator(EntitlementRuntimeAuthority authority) {
        return new EntitlementHealthIndicator(authority);
    }
}
