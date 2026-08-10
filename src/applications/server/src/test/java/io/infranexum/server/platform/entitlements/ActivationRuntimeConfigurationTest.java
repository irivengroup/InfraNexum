package io.infranexum.server.platform.entitlements;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.infranexum.adapters.persistence.jdbc.FileIntegrityProofStore;
import io.infranexum.adapters.persistence.jdbc.JdbcActivationOperationalRepository;
import io.infranexum.adapters.persistence.jdbc.JdbcDatabaseDialect;
import io.infranexum.adapters.persistence.jdbc.JdbcRevocationRegistry;
import io.infranexum.core.capabilities.CapabilityCatalog;
import io.infranexum.core.capabilities.InstallationProfile;
import io.infranexum.core.capabilities.QuotaCatalog;
import io.infranexum.core.contracts.ConfigurationException;
import io.infranexum.core.entitlements.ActivationManifestVerifier;
import io.infranexum.core.entitlements.EntitlementGuard;
import io.infranexum.core.entitlements.LiteEvaluationPolicy;
import io.infranexum.core.entitlements.TrustedTimeGuard;
import io.infranexum.server.persistence.JdbcIsolation;
import io.infranexum.server.persistence.PersistenceMode;
import io.infranexum.server.persistence.PersistenceRuntimeProperties;
import io.infranexum.server.platform.PlatformCapabilityProperties;
import io.infranexum.server.platform.PlatformCapabilityService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.ObjectMapper;

class ActivationRuntimeConfigurationTest {
    @TempDir Path directory;

    @Test
    void createsTheAuthoritativeRuntimeGraphAndRejectsMemoryPersistence() throws Exception {
        Path key = directory.resolve("integrity.key");
        Files.writeString(key, Base64.getEncoder().encodeToString(new byte[32]));
        restrict(key);
        var properties = new ActivationRuntimeProperties(
                true, "customer-1", null, key, directory.resolve("proofs"), 4096, Duration.ofMinutes(5));
        var configuration = new ActivationRuntimeConfiguration();
        var mapper = new ObjectMapper();
        DataSource dataSource = mock(DataSource.class);
        PlatformCapabilityProperties platform = mock(PlatformCapabilityProperties.class);
        when(platform.profile()).thenReturn(InstallationProfile.LITE);
        when(platform.catalogVersion()).thenReturn("2.0.0-draft.20");
        var capabilityCatalog = CapabilityCatalog.loadEmbedded("2.0.0-draft.20");
        var quotaCatalog = QuotaCatalog.loadEmbedded("2.0.0-draft.20");

        assertNotNull(configuration.entitlementClock());
        assertNotNull(configuration.activationManifestCodec(mapper, properties));
        assertEquals("HmacSHA256", configuration.entitlementIntegrityKey(properties).getAlgorithm());
        assertTrueEmpty(configuration.activationTrustedKeyStore(mapper, properties, platform));
        assertEquals(JdbcDatabaseDialect.POSTGRESQL, configuration.activationJdbcDialect(
                persistence(PersistenceMode.POSTGRESQL)));
        assertEquals(JdbcDatabaseDialect.ORACLE, configuration.activationJdbcDialect(
                persistence(PersistenceMode.ORACLE)));
        assertThrows(ConfigurationException.class,
                () -> configuration.activationJdbcDialect(persistence(PersistenceMode.MEMORY)));

        JdbcActivationOperationalRepository repository = configuration.activationOperationalRepository(
                dataSource, JdbcDatabaseDialect.POSTGRESQL);
        assertNotNull(repository);
        assertInstanceOf(FileIntegrityProofStore.class,
                configuration.independentIntegrityProofStore(properties));
        assertInstanceOf(JdbcRevocationRegistry.class,
                configuration.activationRevocationRegistry(dataSource, JdbcDatabaseDialect.POSTGRESQL));
        assertInstanceOf(ActivationManifestVerifier.class, configuration.activationManifestVerifier());
        assertInstanceOf(TrustedTimeGuard.class, configuration.trustedTimeGuard());
        assertInstanceOf(LiteEvaluationPolicy.class, configuration.liteEvaluationPolicy());
        assertInstanceOf(EntitlementGuard.class, configuration.entitlementGuard());

        var trustedKeys = configuration.activationTrustedKeyStore(mapper, properties, platform);
        var revocations = configuration.activationRevocationRegistry(dataSource, JdbcDatabaseDialect.POSTGRESQL);
        var contexts = configuration.activationContextFactory(
                properties, platform, capabilityCatalog, quotaCatalog, trustedKeys, revocations);
        var codec = configuration.activationManifestCodec(mapper, properties);
        var keySecret = configuration.entitlementIntegrityKey(properties);
        var clock = configuration.entitlementClock();
        var authority = configuration.entitlementRuntimeAuthority(
                repository,
                configuration.independentIntegrityProofStore(properties),
                configuration.activationManifestVerifier(),
                contexts,
                codec,
                configuration.trustedTimeGuard(),
                configuration.liteEvaluationPolicy(),
                configuration.entitlementGuard(),
                keySecret,
                clock);
        var coordinator = configuration.activationImportCoordinator(
                repository,
                configuration.independentIntegrityProofStore(properties),
                configuration.activationManifestVerifier(),
                contexts,
                configuration.trustedTimeGuard(),
                keySecret,
                clock);
        PlatformCapabilityService capabilityService = mock(PlatformCapabilityService.class);
        assertNotNull(configuration.activationAdministrationService(
                codec, coordinator, authority, platform, capabilityService));
        assertNotNull(configuration.entitlementWebServerStartupGuard(authority, platform, capabilityService));
        var interceptor = configuration.entitlementMutationInterceptor(authority);
        assertNotNull(configuration.entitlementWebMvcConfiguration(interceptor));
        assertNotNull(configuration.entitlementRefreshScheduler(
                authority, platform, capabilityService, mock(ConfigurableApplicationContext.class)));
        assertNotNull(configuration.entitlementHealthIndicator(authority));
    }

    @Test
    void paidProfilesRequireAnExternalTrustStore() throws Exception {
        Path key = directory.resolve("integrity.key");
        Files.writeString(key, Base64.getEncoder().encodeToString(new byte[32]));
        restrict(key);
        var properties = new ActivationRuntimeProperties(
                true, "customer-1", null, key, directory.resolve("proofs"), 4096, Duration.ofMinutes(5));
        PlatformCapabilityProperties platform = mock(PlatformCapabilityProperties.class);
        when(platform.profile()).thenReturn(InstallationProfile.PRO);
        assertThrows(ConfigurationException.class,
                () -> new ActivationRuntimeConfiguration().activationTrustedKeyStore(
                        new ObjectMapper(), properties, platform));
    }

    private static PersistenceRuntimeProperties persistence(PersistenceMode mode) {
        return new PersistenceRuntimeProperties(mode, JdbcIsolation.READ_COMMITTED);
    }

    private static void assertTrueEmpty(io.infranexum.core.entitlements.TrustedKeyStore store) {
        org.junit.jupiter.api.Assertions.assertTrue(store.find("missing").isEmpty());
    }

    private static void restrict(Path path) throws Exception {
        try {
            Files.setPosixFilePermissions(path, java.util.Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Native ACLs are used on non-POSIX test platforms.
        }
    }
}
