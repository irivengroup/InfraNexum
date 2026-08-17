package io.infranexum.server.integrations;

import io.infranexum.adapters.jiraassets.JdkJiraAssetsTransport;
import io.infranexum.adapters.jiraassets.JiraAssetsTransport;
import io.infranexum.adapters.servicenow.JdkServiceNowTransport;
import io.infranexum.adapters.servicenow.ServiceNowTransport;
import io.infranexum.adapters.persistence.jdbc.JdbcConnectorInboxRepository;
import io.infranexum.adapters.persistence.jdbc.JdbcDatabaseDialect;
import io.infranexum.core.audit.AuditJournal;
import io.infranexum.core.contracts.ConfigurationException;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.integrations.*;
import io.infranexum.server.configuration.ServerRuntimeProperties;
import io.infranexum.server.persistence.PersistenceRuntimeProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Arrays;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Server composition root for the durable connector webhook/inbox/DLQ runtime. */
@Configuration(proxyBeanMethods=false)
@EnableScheduling
@EnableConfigurationProperties(IntegrationRuntimeProperties.class)
@ConditionalOnProperty(name="infranexum.integrations.enabled",havingValue="true")
public class IntegrationRuntimeConfiguration {

    @Bean
    ConnectorInboxRepository connectorInboxRepository(DataSource dataSource, PersistenceRuntimeProperties persistence) {
        JdbcDatabaseDialect dialect = switch (persistence.mode()) {
            case POSTGRESQL -> JdbcDatabaseDialect.POSTGRESQL;
            case ORACLE -> JdbcDatabaseDialect.ORACLE;
            case MEMORY -> throw new ConfigurationException("PGM-10-E05 connector runtime requires PostgreSQL or Oracle persistence");
        };
        return new JdbcConnectorInboxRepository(dataSource, dialect);
    }

    @Bean
    ConnectorEndpointRegistry connectorEndpointRegistry(IntegrationRuntimeProperties properties) {
        return new ConfiguredConnectorEndpointRegistry(properties.endpointDefinitions());
    }

    @Bean ConnectorSecretProvider connectorSecretProvider() { return new ExternalConnectorSecretProvider(); }

    @Bean
    JiraAssetsTransport jiraAssetsTransport(IntegrationRuntimeProperties properties) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_2)
                .build();
        return new JdkJiraAssetsTransport(client, properties.jiraAssets().maximumResponseBytes());
    }

    @Bean
    ConfiguredJiraAssetsConnectorRegistry jiraAssetsConnectorRegistry(
            IntegrationRuntimeProperties properties,
            JiraAssetsTransport transport,
            ConnectorSecretProvider secrets,
            tools.jackson.databind.ObjectMapper json) {
        return new ConfiguredJiraAssetsConnectorRegistry(properties.jiraAssetsDefinitions(), transport, secrets, json);
    }

    @Bean
    SmartInitializingSingleton jiraAssetsSecretValidator(
            IntegrationRuntimeProperties properties, ConnectorSecretProvider secrets) {
        return () -> {
            for (var definition : properties.jiraAssetsDefinitions().values()) {
                if (!definition.enabled()) continue;
                byte[] resolved = secrets.resolve(definition.bearerTokenReference());
                Arrays.fill(resolved, (byte) 0);
            }
        };
    }

    @Bean
    JiraAssetsOperationsService jiraAssetsOperationsService(
            ConfiguredJiraAssetsConnectorRegistry registry,
            AuditJournal audit,
            @Qualifier("integrationIdentifiers") UuidV7Generator ids,
            @Qualifier("platformClock") Clock clock,
            MeterRegistry meters) {
        return new JiraAssetsOperationsService(registry, audit, ids, clock, meters);
    }

    @Bean
    ServiceNowTransport serviceNowTransport(IntegrationRuntimeProperties properties) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_2)
                .build();
        return new JdkServiceNowTransport(client, properties.serviceNow().maximumResponseBytes());
    }

    @Bean
    ConfiguredServiceNowConnectorRegistry serviceNowConnectorRegistry(
            IntegrationRuntimeProperties properties,
            ServiceNowTransport transport,
            ConnectorSecretProvider secrets,
            tools.jackson.databind.ObjectMapper json) {
        return new ConfiguredServiceNowConnectorRegistry(properties.serviceNowDefinitions(), transport, secrets, json);
    }

    @Bean
    SmartInitializingSingleton serviceNowSecretValidator(
            IntegrationRuntimeProperties properties, ConnectorSecretProvider secrets) {
        return () -> {
            for (var definition : properties.serviceNowDefinitions().values()) {
                if (!definition.enabled()) continue;
                byte[] resolved = secrets.resolve(definition.bearerTokenReference());
                Arrays.fill(resolved, (byte) 0);
            }
        };
    }

    @Bean
    ServiceNowOperationsService serviceNowOperationsService(
            ConfiguredServiceNowConnectorRegistry registry,
            AuditJournal audit,
            @Qualifier("integrationIdentifiers") UuidV7Generator ids,
            @Qualifier("platformClock") Clock clock,
            MeterRegistry meters) {
        return new ServiceNowOperationsService(registry, audit, ids, clock, meters);
    }

    @Bean
    ImmutableConnectorHandlerRegistry connectorHandlerRegistry(ObjectProvider<ConnectorDeliveryHandler> handlers) {
        return new ImmutableConnectorHandlerRegistry(handlers.orderedStream().toList());
    }

    @Bean
    SmartInitializingSingleton integrationEndpointValidator(ConnectorEndpointRegistry endpoints, ImmutableConnectorHandlerRegistry handlers, ConnectorSecretProvider secrets) {
        return () -> {
            for (ConnectorWebhookEndpoint endpoint : endpoints.endpoints()) {
                if (!endpoint.enabled()) continue;
                if (!handlers.contains(endpoint.handlerName())) throw new ConfigurationException("enabled connector endpoint has no registered handler: " + endpoint.connectorKey());
                byte[] resolved = secrets.resolve(endpoint.secretReference());
                Arrays.fill(resolved, (byte)0);
            }
        };
    }

    @Bean
    ConnectorRuntimeObserver connectorRuntimeObserver(MeterRegistry registry, ConnectorInboxRepository inbox, ConnectorEndpointRegistry endpoints, @Qualifier("platformClock") Clock clock) {
        return new MicrometerConnectorRuntimeObserver(registry, inbox, endpoints, clock);
    }

    @Bean("integrationIdentifiers")
    UuidV7Generator integrationIdentifiers(@Qualifier("platformClock") Clock clock) { return new UuidV7Generator(clock, new SecureRandom()); }

    @Bean
    HmacSha256WebhookAuthenticator connectorWebhookAuthenticator(ConnectorSecretProvider secrets, @Qualifier("platformClock") Clock clock) { return new HmacSha256WebhookAuthenticator(secrets, clock); }

    @Bean
    ConnectorWebhookService connectorWebhookService(ConnectorEndpointRegistry endpoints,HmacSha256WebhookAuthenticator authenticator,ConnectorInboxRepository inbox,ConnectorRuntimeObserver observer,@Qualifier("integrationIdentifiers") UuidV7Generator ids,@Qualifier("platformClock") Clock clock,IntegrationRuntimeProperties properties) {
        return new ConnectorWebhookService(endpoints,authenticator,inbox,observer,ids,clock,properties.webhookMaxPayloadBytes());
    }

    @Bean
    IntegrationOperationsService integrationOperationsService(ConnectorInboxRepository inbox,AuditJournal audit,ConnectorRuntimeObserver observer,@Qualifier("integrationIdentifiers") UuidV7Generator ids,@Qualifier("platformClock") Clock clock) {
        return new IntegrationOperationsService(inbox,audit,observer,ids,clock);
    }

    @Bean
    ConnectorInboxDispatcher connectorInboxDispatcher(ConnectorInboxRepository inbox,ConnectorEndpointRegistry endpoints,ConnectorHandlerRegistry handlers,ConnectorRuntimeObserver observer,@Qualifier("platformClock") Clock clock,ServerRuntimeProperties server,IntegrationRuntimeProperties properties) {
        return new ConnectorInboxDispatcher(inbox,endpoints,handlers,observer,properties.retryPolicy(),clock,server.instanceId()+":integration",properties.claimBatchSize(),properties.leaseDuration(),properties.suspendAfterDeadLetters(),properties.suspensionDuration());
    }

    @Bean ConnectorInboxSchedule connectorInboxSchedule(ConnectorInboxDispatcher dispatcher) { return new ConnectorInboxSchedule(dispatcher); }
}
