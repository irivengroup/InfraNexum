package io.infranexum.server.integrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import io.infranexum.adapters.persistence.jdbc.JdbcConnectorInboxRepository;
import io.infranexum.adapters.persistence.jdbc.JdbcConnectorSyncRepository;
import io.infranexum.core.audit.AuditJournal;
import io.infranexum.core.contracts.ConfigurationException;
import io.infranexum.core.contracts.RuntimeMode;
import io.infranexum.integrations.ConnectorDelivery;
import io.infranexum.integrations.ConnectorDeliveryHandler;
import io.infranexum.integrations.ConnectorConflictStrategy;
import io.infranexum.integrations.ConnectorDataAuthority;
import io.infranexum.integrations.ConnectorDeletionPolicy;
import io.infranexum.integrations.ConnectorInboxRepository;
import io.infranexum.integrations.ConnectorKey;
import io.infranexum.integrations.ConnectorRollbackStrategy;
import io.infranexum.integrations.ConnectorSyncDirection;
import io.infranexum.integrations.ConnectorSyncHandler;
import io.infranexum.integrations.ConnectorSyncBatchContext;
import io.infranexum.integrations.ConnectorSyncBatchResult;
import io.infranexum.integrations.ConnectorSyncCompensationContext;
import io.infranexum.integrations.ConnectorSyncCompensationResult;
import io.infranexum.integrations.ConnectorWebhookEndpoint;
import io.infranexum.server.configuration.ServerRuntimeProperties;
import io.infranexum.server.persistence.JdbcIsolation;
import io.infranexum.server.persistence.PersistenceMode;
import io.infranexum.server.persistence.PersistenceRuntimeProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** Composition-root tests proving that connector runtime startup is explicit and fail-closed. */
class IntegrationRuntimeConfigurationTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-16T12:00:00Z"), ZoneOffset.UTC);
    private final IntegrationRuntimeConfiguration configuration = new IntegrationRuntimeConfiguration();

    @Test
    void applicationYamlBindsWithNoIntegrationEndpointsConfigured() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(IntegrationPropertiesBindingConfiguration.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    IntegrationRuntimeProperties properties = context.getBean(IntegrationRuntimeProperties.class);
                    assertTrue(properties.endpoints().isEmpty());
                    assertTrue(properties.jiraAssets().connectors().isEmpty());
                    assertTrue(properties.serviceNow().connectors().isEmpty());
                    assertTrue(properties.notifications().endpoints().isEmpty());
                    assertTrue(properties.governance().isEmpty());
                });
    }

    @Test
    void explicitPreparedGovernanceBindsThroughSpringConfigData() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(IntegrationPropertiesBindingConfiguration.class)
                .withPropertyValues(
                        "infranexum.integrations.governance.jira-prod.direction=INBOUND",
                        "infranexum.integrations.governance.jira-prod.authority=EXTERNAL",
                        "infranexum.integrations.governance.jira-prod.conflict-strategy=PREFER_AUTHORITY",
                        "infranexum.integrations.governance.jira-prod.deletion-policy=IGNORE",
                        "infranexum.integrations.governance.jira-prod.rollback-strategy=LOCAL_CHECKPOINT",
                        "infranexum.integrations.governance.jira-prod.execution-enabled=false",
                        "infranexum.integrations.governance.jira-prod.fields.name=EXTERNAL")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    IntegrationRuntimeProperties properties = context.getBean(IntegrationRuntimeProperties.class);
                    assertEquals(1, properties.governance().size());
                    var governance = properties.governance().get("jira-prod");
                    assertEquals(ConnectorSyncDirection.INBOUND, governance.direction());
                    assertEquals(ConnectorDataAuthority.EXTERNAL, governance.fields().get("name"));
                    assertFalse(governance.executionEnabled());
                });
    }

    @Test
    void durableRepositorySupportsOnlyPostgresqlAndOracle() {
        DataSource dataSource = mock(DataSource.class);
        assertInstanceOf(JdbcConnectorInboxRepository.class, configuration.connectorInboxRepository(
                dataSource, persistence(PersistenceMode.POSTGRESQL)));
        assertInstanceOf(JdbcConnectorInboxRepository.class, configuration.connectorInboxRepository(
                dataSource, persistence(PersistenceMode.ORACLE)));
        assertThrows(ConfigurationException.class, () -> configuration.connectorInboxRepository(
                dataSource, persistence(PersistenceMode.MEMORY)));
    }

    @Test
    void connectorSyncRepositoryIsDurableOnlyAndHandlerAdmissionIsBidirectionallyFailClosed() {
        DataSource dataSource = mock(DataSource.class);
        assertInstanceOf(JdbcConnectorSyncRepository.class, configuration.connectorSyncRepository(
                dataSource, persistence(PersistenceMode.POSTGRESQL)));
        assertInstanceOf(JdbcConnectorSyncRepository.class, configuration.connectorSyncRepository(
                dataSource, persistence(PersistenceMode.ORACLE)));
        assertThrows(ConfigurationException.class, () -> configuration.connectorSyncRepository(
                dataSource, persistence(PersistenceMode.MEMORY)));

        ConnectorKey key = new ConnectorKey("jira-prod");
        ConnectorSyncHandler jiraMutation = new ConnectorSyncHandler() {
            @Override public ConnectorKey connectorKey() { return key; }
            @Override public ConnectorSyncBatchResult synchronize(ConnectorSyncBatchContext context) {
                return ConnectorSyncBatchResult.applied(null, 0, 0, 0, true);
            }
            @Override public ConnectorSyncCompensationResult compensate(ConnectorSyncCompensationContext context) {
                return ConnectorSyncCompensationResult.succeeded();
            }
        };
        StaticListableBeanFactory handlersFactory = new StaticListableBeanFactory();
        handlersFactory.addBean("jiraMutation", jiraMutation);
        var emptyJiraCatalog = new ConfiguredJiraAssetsSyncHandlerCatalog(
                Map.of(), null, null, null, null);
        var handlerRegistry = configuration.connectorSyncHandlerRegistry(
                handlersFactory.getBeanProvider(ConnectorSyncHandler.class), emptyJiraCatalog);
        StaticListableBeanFactory emptyFactory = new StaticListableBeanFactory();
        var emptyRegistry = configuration.connectorSyncHandlerRegistry(
                emptyFactory.getBeanProvider(ConnectorSyncHandler.class), emptyJiraCatalog);

        var jira = Map.of(key, new io.infranexum.adapters.jiraassets.JiraAssetsSettings(
                key, "cloud", "workspace", "env:PATH", Duration.ofSeconds(5), true));
        var readOnly = new ConfiguredConnectorGovernanceRegistry(jira, Map.of());
        assertThrows(ConfigurationException.class,
                configuration.connectorSyncHandlerValidator(handlerRegistry, readOnly)::afterSingletonsInstantiated);
        configuration.connectorSyncHandlerValidator(emptyRegistry, readOnly).afterSingletonsInstantiated();

        var prepared = new ConfiguredConnectorGovernanceRegistry(
                jira, Map.of(), Map.of(key, governance(false)));
        configuration.connectorSyncHandlerValidator(emptyRegistry, prepared).afterSingletonsInstantiated();
        assertThrows(ConfigurationException.class,
                configuration.connectorSyncHandlerValidator(handlerRegistry, prepared)::afterSingletonsInstantiated);

        var active = new ConfiguredConnectorGovernanceRegistry(
                jira, Map.of(), Map.of(key, governance(true)));
        assertThrows(ConfigurationException.class,
                configuration.connectorSyncHandlerValidator(emptyRegistry, active)::afterSingletonsInstantiated);
        configuration.connectorSyncHandlerValidator(handlerRegistry, active).afterSingletonsInstantiated();
    }

    @Test
    void startupValidatorRejectsEnabledEndpointWithoutHandlerOrResolvableSecret() {
        var endpoint = new ConnectorWebhookEndpoint(
                new ConnectorKey("jira-assets.test"), "jira-handler", "env:PATH", Duration.ofMinutes(5), true);
        var endpoints = new ConfiguredConnectorEndpointRegistry(Map.of(endpoint.connectorKey(), endpoint));
        var noHandlers = new ImmutableConnectorHandlerRegistry(List.of());
        SmartInitializingSingleton missingHandler = configuration.integrationEndpointValidator(
                endpoints, noHandlers, new ExternalConnectorSecretProvider());
        assertThrows(ConfigurationException.class, missingHandler::afterSingletonsInstantiated);

        var handlers = new ImmutableConnectorHandlerRegistry(List.of(handler("jira-handler")));
        var missingSecret = new ConnectorWebhookEndpoint(
                endpoint.connectorKey(), "jira-handler", "env:INFRANEXUM_VARIABLE_THAT_MUST_NOT_EXIST_A101", Duration.ofMinutes(5), true);
        SmartInitializingSingleton missingSecretValidator = configuration.integrationEndpointValidator(
                new ConfiguredConnectorEndpointRegistry(Map.of(endpoint.connectorKey(), missingSecret)),
                handlers,
                new ExternalConnectorSecretProvider());
        assertThrows(ConfigurationException.class, missingSecretValidator::afterSingletonsInstantiated);

        var disabled = new ConnectorWebhookEndpoint(
                endpoint.connectorKey(), "missing-handler", "env:INFRANEXUM_VARIABLE_THAT_MUST_NOT_EXIST_A101", Duration.ofMinutes(5), false);
        configuration.integrationEndpointValidator(
                new ConfiguredConnectorEndpointRegistry(Map.of(endpoint.connectorKey(), disabled)),
                noHandlers,
                new ExternalConnectorSecretProvider()).afterSingletonsInstantiated();

        configuration.integrationEndpointValidator(endpoints, handlers, reference -> new byte[32])
                .afterSingletonsInstantiated();
    }

    @Test
    void everyRuntimeBeanCanBeComposedFromValidatedPorts() {
        IntegrationRuntimeProperties properties = properties();
        var endpointRegistry = configuration.connectorEndpointRegistry(properties);
        assertNotNull(endpointRegistry);
        assertNotNull(configuration.connectorSecretProvider());

        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean("handler", handler("jira-handler"));
        var handlerRegistry = configuration.connectorHandlerRegistry(factory.getBeanProvider(ConnectorDeliveryHandler.class));
        assertNotNull(handlerRegistry);

        ConnectorInboxRepository inbox = mock(ConnectorInboxRepository.class);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        try {
            var observer = configuration.connectorRuntimeObserver(meters, inbox, endpointRegistry, CLOCK);
            var ids = configuration.integrationIdentifiers(CLOCK);
            var authenticator = configuration.connectorWebhookAuthenticator(configuration.connectorSecretProvider(), CLOCK);
            var webhook = configuration.connectorWebhookService(
                    endpointRegistry, authenticator, inbox, observer, ids, CLOCK, properties);
            var operations = configuration.integrationOperationsService(
                    inbox, mock(AuditJournal.class), observer, ids, CLOCK);
            var dispatcher = configuration.connectorInboxDispatcher(
                    inbox, endpointRegistry, handlerRegistry, observer, CLOCK,
                    new ServerRuntimeProperties("server-a", RuntimeMode.REGIONAL, "eu-west", "paris", "2.0.0-alpha.0.128", "2.0.0-draft.21"),
                    properties);

            assertNotNull(webhook);
            assertNotNull(operations);
            assertNotNull(dispatcher);
            assertNotNull(configuration.connectorInboxSchedule(dispatcher));
        } finally {
            meters.close();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(IntegrationRuntimeProperties.class)
    static class IntegrationPropertiesBindingConfiguration { }

    private static PersistenceRuntimeProperties persistence(PersistenceMode mode) {
        return new PersistenceRuntimeProperties(mode, JdbcIsolation.READ_COMMITTED);
    }

    private static IntegrationRuntimeProperties properties() {
        return new IntegrationRuntimeProperties(
                true, 1_048_576, 50, Duration.ofSeconds(1), Duration.ofSeconds(30),
                5, Duration.ofSeconds(1), Duration.ofMinutes(1), 0.2,
                3, Duration.ofMinutes(15),
                Map.of("jira-assets.test", new IntegrationRuntimeProperties.EndpointProperties(
                        "jira-handler", "env:PATH", Duration.ofMinutes(5), true)),
                new IntegrationRuntimeProperties.JiraAssetsProperties(2_097_152, Map.of()),
                new IntegrationRuntimeProperties.ServiceNowProperties(2_097_152, Map.of()));
    }

    private static IntegrationRuntimeProperties.GovernanceProperties governance(boolean executionEnabled) {
        return new IntegrationRuntimeProperties.GovernanceProperties(
                ConnectorSyncDirection.INBOUND, ConnectorDataAuthority.EXTERNAL,
                ConnectorConflictStrategy.PREFER_AUTHORITY, ConnectorDeletionPolicy.IGNORE,
                ConnectorRollbackStrategy.LOCAL_CHECKPOINT, executionEnabled,
                Map.of("name", ConnectorDataAuthority.EXTERNAL));
    }

    private static ConnectorDeliveryHandler handler(String name) {
        return new ConnectorDeliveryHandler() {
            @Override public String name() { return name; }
            @Override public void handle(ConnectorDelivery delivery) { }
        };
    }
}
