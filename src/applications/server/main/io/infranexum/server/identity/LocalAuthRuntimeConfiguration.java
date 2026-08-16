package io.infranexum.server.identity;

import io.infranexum.adapters.persistence.jdbc.JdbcDatabaseDialect;
import io.infranexum.adapters.persistence.jdbc.JdbcLocalIdentityRepository;
import io.infranexum.adapters.persistence.jdbc.JdbcLocalSessionRepository;
import io.infranexum.adapters.security.BouncyCastleArgon2idPasswordHasher;
import io.infranexum.adapters.security.SecureRandomTokenGenerator;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.identity.local.application.LocalAuthenticationPolicy;
import io.infranexum.identity.local.application.LocalAuthenticationService;
import io.infranexum.identity.local.domain.LocalPasswordPolicy;
import io.infranexum.server.http.ApiProblemSupport;
import io.infranexum.server.persistence.PersistenceMode;
import io.infranexum.server.persistence.PersistenceRuntimeProperties;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LocalAuthRuntimeProperties.class)
@ConditionalOnProperty(name = "infranexum.identity.local.enabled", havingValue = "true")
public class LocalAuthRuntimeConfiguration {
    @Bean
    JdbcLocalIdentityRepository localIdentityRepository(DataSource dataSource, PersistenceRuntimeProperties persistence) {
        return new JdbcLocalIdentityRepository(dataSource, dialect(persistence.mode()));
    }

    @Bean
    JdbcLocalSessionRepository localSessionRepository(DataSource dataSource, PersistenceRuntimeProperties persistence) {
        return new JdbcLocalSessionRepository(dataSource, dialect(persistence.mode()));
    }

    @Bean
    LocalAuthenticationService localAuthenticationService(
            JdbcLocalIdentityRepository identities,
            JdbcLocalSessionRepository sessions,
            LocalAuthRuntimeProperties properties,
            @Qualifier("platformClock") Clock clock) {
        SecureRandom random = new SecureRandom();
        return new LocalAuthenticationService(
                identities,
                sessions,
                new BouncyCastleArgon2idPasswordHasher(random),
                new SecureRandomTokenGenerator(random),
                new LocalPasswordPolicy(),
                new LocalAuthenticationPolicy(
                        properties.lockThreshold(), properties.lockDuration(), properties.idleTimeout(),
                        properties.absoluteTimeout(), properties.touchInterval()),
                new UuidV7Generator(clock, random),
                clock);
    }

    @Bean
    LocalAuthenticationFilter localAuthenticationFilter(LocalAuthenticationService service, ApiProblemSupport problems) {
        return new LocalAuthenticationFilter(service, problems);
    }

    @Bean
    @Order(100)
    ApplicationRunner localIdentityBootstrap(
            LocalAuthenticationService service,
            JdbcLocalIdentityRepository identities,
            LocalAuthRuntimeProperties properties) {
        return arguments -> {
            if (identities.hasAnyAccount()) return;
            if (properties.bootstrapPasswordFile().isBlank()) {
                throw new IllegalStateException("local authentication requires a bootstrap password file while no account exists");
            }
            char[] secret = LocalAuthSecretReader.read(Path.of(properties.bootstrapPasswordFile()));
            try {
                service.bootstrap(properties.bootstrapUsername(), properties.bootstrapDisplayName(), secret, true);
            } catch (RuntimeException failure) {
                if (!identities.hasAnyAccount()) throw failure;
            }
        };
    }

    private static JdbcDatabaseDialect dialect(PersistenceMode mode) {
        return switch (mode) {
            case POSTGRESQL -> JdbcDatabaseDialect.POSTGRESQL;
            case ORACLE -> JdbcDatabaseDialect.ORACLE;
            case MEMORY -> throw new IllegalStateException("local authentication requires durable JDBC persistence");
        };
    }
}
