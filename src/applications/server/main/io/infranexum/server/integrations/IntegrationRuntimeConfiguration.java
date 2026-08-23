package io.infranexum.server.integrations;

import io.infranexum.adapters.jiraassets.JdkJiraAssetsTransport;
import io.infranexum.adapters.outboundwebhook.JdkSignedWebhookTransport;
import io.infranexum.adapters.jiraassets.JiraAssetsTransport;
import io.infranexum.adapters.servicenow.JdkServiceNowTransport;
import io.infranexum.adapters.servicenow.ServiceNowTransport;
import io.infranexum.adapters.persistence.jdbc.JdbcConnectorInboxRepository;
import io.infranexum.adapters.persistence.jdbc.JdbcOutboundNotificationRepository;
import io.infranexum.adapters.persistence.jdbc.JdbcConnectorSyncRepository;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
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
    ConnectorGovernanceRegistry connectorGovernanceRegistry(IntegrationRuntimeProperties properties) {
        return new ConfiguredConnectorGovernanceRegistry(
                properties.jiraAssetsDefinitions(), properties.serviceNowDefinitions(), properties.governanceDefinitions());
    }

    @Bean
    ConnectorGovernancePlanner connectorGovernancePlanner() { return new ConnectorGovernancePlanner(); }

    @Bean
    ConnectorGovernanceOperationsService connectorGovernanceOperationsService(
            ConnectorGovernanceRegistry registry,
            ConnectorGovernancePlanner planner,
            AuditJournal audit,
            @Qualifier("integrationIdentifiers") UuidV7Generator ids,
            @Qualifier("platformClock") Clock clock) {
        return new ConnectorGovernanceOperationsService(registry, planner, audit, ids, clock);
    }

    @Bean
    @ConditionalOnExpression("\'${infranexum.persistence.mode:MEMORY}\' == \'POSTGRESQL\' || \'${infranexum.persistence.mode:MEMORY}\' == \'ORACLE\'")
    ConnectorSyncRepository connectorSyncRepository(DataSource dataSource, PersistenceRuntimeProperties persistence) {
        JdbcDatabaseDialect dialect = switch (persistence.mode()) {
            case POSTGRESQL -> JdbcDatabaseDialect.POSTGRESQL;
            case ORACLE -> JdbcDatabaseDialect.ORACLE;
            case MEMORY -> throw new ConfigurationException("connector synchronization requires PostgreSQL or Oracle persistence");
        };
        return new JdbcConnectorSyncRepository(dataSource, dialect);
    }

    @Bean
    ConfiguredJiraAssetsSyncHandlerCatalog jiraAssetsSyncHandlerCatalog(
            IntegrationRuntimeProperties properties,
            ConfiguredJiraAssetsConnectorRegistry connectors,
            ConnectorGovernanceRegistry governance,
            DataSource dataSource,
            PersistenceRuntimeProperties persistence) {
        return new ConfiguredJiraAssetsSyncHandlerCatalog(
                properties.jiraAssetsMutationDefinitions(), connectors, governance, dataSource, persistence);
    }

    @Bean
    ConfiguredServiceNowSyncHandlerCatalog serviceNowSyncHandlerCatalog(
            IntegrationRuntimeProperties properties,
            ConfiguredServiceNowConnectorRegistry connectors,
            ConnectorGovernanceRegistry governance,
            DataSource dataSource,
            PersistenceRuntimeProperties persistence) {
        return new ConfiguredServiceNowSyncHandlerCatalog(
                properties.serviceNowMutationDefinitions(), connectors, governance, dataSource, persistence);
    }

    @Bean
    ImmutableConnectorSyncHandlerRegistry connectorSyncHandlerRegistry(
            ObjectProvider<ConnectorSyncHandler> handlers,
            ConfiguredJiraAssetsSyncHandlerCatalog jiraHandlers,
            ConfiguredServiceNowSyncHandlerCatalog serviceNowHandlers) {
        java.util.List<ConnectorSyncHandler> values = new java.util.ArrayList<>(handlers.orderedStream().toList());
        values.addAll(jiraHandlers.handlers());
        values.addAll(serviceNowHandlers.handlers());
        return new ImmutableConnectorSyncHandlerRegistry(values);
    }

    @Bean
    SmartInitializingSingleton connectorSyncHandlerValidator(
            ImmutableConnectorSyncHandlerRegistry handlers, ConnectorGovernanceRegistry governance) {
        return () -> {
            for (ConnectorKey key : handlers.keys()) {
                ConnectorGovernancePolicy policy;
                try {
                    policy = governance.require(key);
                } catch (ConnectorGovernanceNotFoundException missing) {
                    throw new ConfigurationException(
                            "synchronization handler has no configured connector governance policy: " + key.value());
                }
                if (!policy.direction().mutating() || !policy.executionEnabled()) {
                    throw new ConfigurationException(
                            "synchronization handler registered for connector without active mutating execution: " + key.value());
                }
            }
            for (ConnectorGovernancePolicy policy : governance.policies()) {
                if (policy.direction().mutating() && policy.executionEnabled() && !handlers.contains(policy.connectorKey())) {
                    throw new ConfigurationException(
                            "active mutating connector policy has no registered synchronization handler: "
                                    + policy.connectorKey().value());
                }
            }
        };
    }

    @Bean
    ConnectorSyncRuntimeObserver connectorSyncRuntimeObserver(MeterRegistry registry) {
        return new MicrometerConnectorSyncRuntimeObserver(registry);
    }

    @Bean
    @ConditionalOnExpression("\'${infranexum.persistence.mode:MEMORY}\' == \'POSTGRESQL\' || \'${infranexum.persistence.mode:MEMORY}\' == \'ORACLE\'")
    ConnectorSyncEngine connectorSyncEngine(
            ConnectorGovernanceRegistry governance, ConnectorGovernancePlanner planner,
            ConnectorSyncHandlerRegistry handlers, ConnectorSyncRepository repository,
            @Qualifier("integrationIdentifiers") UuidV7Generator ids, @Qualifier("platformClock") Clock clock,
            ConnectorSyncRuntimeObserver observer) {
        return new ConnectorSyncEngine(governance, planner, handlers, repository, ids, clock, observer);
    }

    @Bean
    @ConditionalOnExpression("\'${infranexum.persistence.mode:MEMORY}\' == \'POSTGRESQL\' || \'${infranexum.persistence.mode:MEMORY}\' == \'ORACLE\'")
    ConnectorSyncOperationsService connectorSyncOperationsService(
            ConnectorSyncEngine engine, ConnectorSyncRepository repository, AuditJournal audit,
            @Qualifier("integrationIdentifiers") UuidV7Generator ids, @Qualifier("platformClock") Clock clock, MeterRegistry meters) {
        return new ConnectorSyncOperationsService(engine, repository, audit, ids, clock, meters);
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

    @Bean
    OutboundNotificationRepository outboundNotificationRepository(DataSource dataSource, PersistenceRuntimeProperties persistence) {
        JdbcDatabaseDialect dialect = switch (persistence.mode()) {
            case POSTGRESQL -> JdbcDatabaseDialect.POSTGRESQL;
            case ORACLE -> JdbcDatabaseDialect.ORACLE;
            case MEMORY -> throw new ConfigurationException("outbound notifications require PostgreSQL or Oracle persistence");
        };
        return new JdbcOutboundNotificationRepository(dataSource, dialect);
    }

    @Bean
    OutboundNotificationEndpointRegistry outboundNotificationEndpointRegistry(IntegrationRuntimeProperties properties) {
        return new ConfiguredOutboundNotificationEndpointRegistry(properties.notificationEndpointDefinitions());
    }

    @Bean
    SmartInitializingSingleton outboundNotificationSecretValidator(
            OutboundNotificationEndpointRegistry endpoints, ConnectorSecretProvider secrets) {
        return () -> {
            for (OutboundNotificationEndpoint endpoint : endpoints.endpoints()) {
                if (!endpoint.enabled()) continue;
                byte[] resolved = secrets.resolve(endpoint.secretReference());
                try {
                    if (resolved == null || resolved.length < 32) {
                        throw new ConfigurationException("enabled notification endpoint secret is unavailable or shorter than 32 bytes: " + endpoint.endpointKey());
                    }
                } finally {
                    if (resolved != null) Arrays.fill(resolved, (byte) 0);
                }
            }
        };
    }

    @Bean
    OutboundNotificationTransport outboundNotificationTransport(
            ConnectorSecretProvider secrets, @Qualifier("platformClock") Clock clock) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_2)
                .build();
        return new JdkSignedWebhookTransport(client, secrets, clock);
    }

    @Bean
    OutboundNotificationRuntimeObserver outboundNotificationRuntimeObserver(
            MeterRegistry registry, OutboundNotificationRepository repository,
            OutboundNotificationEndpointRegistry endpoints, @Qualifier("platformClock") Clock clock) {
        return new MicrometerOutboundNotificationObserver(registry, repository, endpoints, clock);
    }

    @Bean
    OutboundNotificationPublisher outboundNotificationPublisher(
            OutboundNotificationEndpointRegistry endpoints, OutboundNotificationRepository repository,
            OutboundNotificationRuntimeObserver observer, @Qualifier("integrationIdentifiers") UuidV7Generator ids,
            @Qualifier("platformClock") Clock clock, IntegrationRuntimeProperties properties) {
        return new OutboundNotificationPublisher(
                endpoints, repository, observer, ids, clock, properties.notifications().maximumPayloadBytes());
    }

    @Bean
    NotificationOperationsService notificationOperationsService(
            OutboundNotificationEndpointRegistry endpoints, OutboundNotificationPublisher publisher,
            OutboundNotificationRepository repository, OutboundNotificationRuntimeObserver observer,
            AuditJournal audit, @Qualifier("integrationIdentifiers") UuidV7Generator ids,
            @Qualifier("platformClock") Clock clock) {
        return new NotificationOperationsService(endpoints, publisher, repository, observer, audit, ids, clock);
    }

    @Bean
    OutboundNotificationDispatcher outboundNotificationDispatcher(
            OutboundNotificationRepository repository, OutboundNotificationEndpointRegistry endpoints,
            OutboundNotificationTransport transport, OutboundNotificationRuntimeObserver observer,
            @Qualifier("platformClock") Clock clock, ServerRuntimeProperties server, IntegrationRuntimeProperties properties) {
        return new OutboundNotificationDispatcher(
                repository, endpoints, transport, observer, properties.retryPolicy(), clock,
                server.instanceId() + ":notifications", properties.claimBatchSize(), properties.leaseDuration(),
                properties.suspendAfterDeadLetters(), properties.suspensionDuration());
    }

    @Bean OutboundNotificationSchedule outboundNotificationSchedule(OutboundNotificationDispatcher dispatcher) { return new OutboundNotificationSchedule(dispatcher); }

    @Bean ConnectorInboxSchedule connectorInboxSchedule(ConnectorInboxDispatcher dispatcher) { return new ConnectorInboxSchedule(dispatcher); }
}
