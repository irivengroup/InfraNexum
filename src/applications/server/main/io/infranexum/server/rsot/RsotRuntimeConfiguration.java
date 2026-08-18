package io.infranexum.server.rsot;

import io.infranexum.adapters.persistence.jdbc.JdbcDatabaseDialect;
import io.infranexum.adapters.persistence.jdbc.JdbcRsotRepository;
import io.infranexum.adapters.persistence.jdbc.JdbcSchemaRegistryRepository;
import io.infranexum.adapters.persistence.jdbc.JdbcTransactionalEventStore;
import io.infranexum.core.audit.AuditJournal;
import io.infranexum.core.compatibility.SchemaRegistryService;
import io.infranexum.core.compatibility.SchemaRegistryFeaturePolicy;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.core.capabilities.CapabilityGuard;
import io.infranexum.server.platform.PlatformCapabilityService;
import io.infranexum.server.configuration.ServerTemporalInputParser;
import io.infranexum.identity.access.application.RbacAuthorizationService;
import io.infranexum.identity.local.application.LocalAuthenticationService;
import io.infranexum.server.rsot.cli.RsotSchemaCli;
import io.infranexum.core.events.TransactionalEventStore;
import io.infranexum.rsot.application.RsotAuthorityService;
import io.infranexum.rsot.application.RsotQueryService;
import java.security.SecureRandom;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composes the RSOT foundation and the PGM-06-E03 Core Schema Registry on durable databases. */
@Configuration(proxyBeanMethods = false)
public class RsotRuntimeConfiguration {
    @Bean
    SchemaRegistryFeaturePolicy schemaRegistryFeaturePolicy(PlatformCapabilityService capabilities) {
        return () -> CapabilityGuard.requireAvailable(capabilities.explain("rsot.core"));
    }

    @Bean
    JacksonSchemaDefinitionInspector schemaDefinitionInspector() {
        return new JacksonSchemaDefinitionInspector();
    }

    @Bean
    @ConditionalOnExpression("\'${infranexum.persistence.mode:MEMORY}\' == \'POSTGRESQL\' || \'${infranexum.persistence.mode:MEMORY}\' == \'ORACLE\'")
    RsotSchemaCli rsotSchemaCli(
            LocalAuthenticationService authentication,
            RbacAuthorizationService authorization,
            SchemaRegistryService registry,
            JacksonSchemaDefinitionInspector inspector,
            ServerTemporalInputParser temporal,
            @Qualifier("correlationIdentifiers") UuidV7Generator identifiers) {
        return new RsotSchemaCli(authentication, authorization, registry, inspector, temporal, identifiers);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "infranexum.persistence.mode", havingValue = "POSTGRESQL")
    static class Postgresql {
        @Bean
        JdbcRsotRepository rsotRepository(DataSource dataSource) {
            return new JdbcRsotRepository(dataSource, JdbcDatabaseDialect.POSTGRESQL);
        }

        @Bean
        RsotAuthorityService rsotAuthorityService(JdbcRsotRepository repository, @Qualifier("platformClock") Clock clock) {
            return new RsotAuthorityService(repository, clock);
        }

        @Bean
        RsotQueryService rsotQueryService(JdbcRsotRepository repository) {
            return new RsotQueryService(repository);
        }

        @Bean
        JdbcSchemaRegistryRepository schemaRegistryRepository(DataSource dataSource, TransactionalEventStore events) {
            return repository(dataSource, events, JdbcDatabaseDialect.POSTGRESQL);
        }

        @Bean
        SchemaRegistryService schemaRegistryService(
                JdbcSchemaRegistryRepository repository,
                JacksonSchemaDefinitionInspector inspector,
                TransactionalEventStore events,
                AuditJournal audit,
                @Qualifier("platformClock") Clock clock,
                SchemaRegistryFeaturePolicy features) {
            return new SchemaRegistryService(repository, inspector, events, audit,
                    new UuidV7Generator(clock, new SecureRandom()), clock, features);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "infranexum.persistence.mode", havingValue = "ORACLE")
    static class Oracle {
        @Bean
        JdbcRsotRepository rsotRepository(DataSource dataSource) {
            return new JdbcRsotRepository(dataSource, JdbcDatabaseDialect.ORACLE);
        }

        @Bean
        RsotAuthorityService rsotAuthorityService(JdbcRsotRepository repository, @Qualifier("platformClock") Clock clock) {
            return new RsotAuthorityService(repository, clock);
        }

        @Bean
        RsotQueryService rsotQueryService(JdbcRsotRepository repository) {
            return new RsotQueryService(repository);
        }

        @Bean
        JdbcSchemaRegistryRepository schemaRegistryRepository(DataSource dataSource, TransactionalEventStore events) {
            return repository(dataSource, events, JdbcDatabaseDialect.ORACLE);
        }

        @Bean
        SchemaRegistryService schemaRegistryService(
                JdbcSchemaRegistryRepository repository,
                JacksonSchemaDefinitionInspector inspector,
                TransactionalEventStore events,
                AuditJournal audit,
                @Qualifier("platformClock") Clock clock,
                SchemaRegistryFeaturePolicy features) {
            return new SchemaRegistryService(repository, inspector, events, audit,
                    new UuidV7Generator(clock, new SecureRandom()), clock, features);
        }
    }

    private static JdbcSchemaRegistryRepository repository(
            DataSource dataSource, TransactionalEventStore events, JdbcDatabaseDialect dialect) {
        if (!(events instanceof JdbcTransactionalEventStore jdbcEvents)) {
            throw new IllegalStateException("schema registry requires durable JDBC transactional events");
        }
        return new JdbcSchemaRegistryRepository(dataSource, jdbcEvents, dialect);
    }
}
