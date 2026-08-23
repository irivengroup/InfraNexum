from pathlib import Path
import unittest
import yaml

ROOT=Path(__file__).resolve().parents[2]
DOMAIN=ROOT/'src/components/domains/integrations/main/io/infranexum/integrations'
JDBC=ROOT/'src/components/adapters/jdbc/main/io/infranexum/adapters/persistence/jdbc/JdbcConnectorSyncRepository.java'
SERVER=ROOT/'src/applications/server/main/io/infranexum/server/integrations'
OPENAPI=ROOT/'src/applications/server/resources/openapi/integrations-connectors.yaml'
WEB=ROOT/'src/applications/web/public/assets'

class ConnectorSyncRuntimeArchitectureTest(unittest.TestCase):
    def test_runtime_is_provider_agnostic_checkpointed_and_not_exactly_once(self):
        text='\n'.join(p.read_text(encoding='utf-8') for p in DOMAIN.glob('ConnectorSync*.java'))
        self.assertIn('appendCheckpoint',text)
        self.assertIn('beginCompensation',text)
        self.assertIn('COMPENSATION',text)
        self.assertIn('BATCH_BUDGET_EXHAUSTED',text)
        self.assertNotIn('io.infranexum.adapters',text)
        self.assertIn('never assumes exactly-once',text.lower())

    def test_durable_schema_exists_in_postgresql_and_oracle_with_active_fence(self):
        for name in ('postgresql.sql','oracle.sql'):
            sql=(ROOT/'src/distribution/migrations/0038-integrations-connector-sync-runtime'/name).read_text(encoding='utf-8').lower()
            self.assertIn('sync_state',sql)
            self.assertIn('sync_run',sql)
            self.assertIn('sync_checkpoint',sql)
            self.assertIn('active_run_id',sql)
            self.assertIn('current_revision',sql)
            self.assertIn('cursor_sha256',sql)
        jdbc=JDBC.read_text(encoding='utf-8')
        self.assertIn('FOR UPDATE',jdbc)
        self.assertIn('connector has advanced beyond the run',jdbc)
        self.assertIn('COMPENSATION',jdbc)

    def test_sync_permissions_and_http_routes_are_fail_closed(self):
        auth=(ROOT/'src/applications/server/main/io/infranexum/server/identityaccess/AuthorizationRequirement.java').read_text(encoding='utf-8')
        for permission in ('INTEGRATIONS_SYNC_READ','INTEGRATIONS_SYNC_EXECUTE','INTEGRATIONS_SYNC_COMPENSATE'):
            self.assertIn(permission,auth)
        self.assertIn('/api/v1/integrations/sync/',auth)
        self.assertIn('UNREGISTERED',auth)
        idem=(ROOT/'src/applications/server/main/io/infranexum/server/http/idempotency/ApiIdempotencyPolicy.java').read_text(encoding='utf-8')
        for suffix in ('/execute','/resume','/compensate'):
            self.assertIn(suffix,idem)

    def test_openapi_exposes_history_without_raw_cursor_and_mutations_are_idempotent(self):
        doc=yaml.safe_load(OPENAPI.read_text(encoding='utf-8'));paths=doc['paths']
        expected={
          '/api/v1/integrations/sync/runs':'get',
          '/api/v1/integrations/sync/{connectorKey}/checkpoints':'get',
          '/api/v1/integrations/sync/{connectorKey}/execute':'post',
          '/api/v1/integrations/sync/runs/{syncRunId}/resume':'post',
          '/api/v1/integrations/sync/runs/{syncRunId}/compensate':'post',
        }
        for path,method in expected.items(): self.assertIn(method,paths[path])
        for path in list(expected)[2:]: self.assertEqual('required',paths[path]['post']['x-infranexum-idempotency'])
        schema=str(doc['components']['schemas']['ConnectorSyncCheckpoint']).lower()
        self.assertIn('cursorsha256',schema);self.assertNotIn("'cursor':",schema)
        directions=doc['components']['schemas']['ConnectorSyncExecutionRequest']['properties']['direction']['enum']
        self.assertNotIn('FEDERATED_READ',directions)

    def test_jira_and_servicenow_mutation_are_admitted_only_by_exact_governance(self):
        governance=(SERVER/'ConfiguredConnectorGovernanceRegistry.java').read_text(encoding='utf-8')
        self.assertIn('externalFederatedRead',governance)

        jira=(SERVER/'ConfiguredJiraAssetsSyncHandlerCatalog.java').read_text(encoding='utf-8')
        service_now=(SERVER/'ConfiguredServiceNowSyncHandlerCatalog.java').read_text(encoding='utf-8')
        service_now_registry=(SERVER/'ConfiguredServiceNowConnectorRegistry.java').read_text(encoding='utf-8')
        for catalog, field_guard in ((jira, 'governed.equals(mutation.attributeIds().keySet())'),
                                     (service_now, 'governed.equals(mutation.fieldNames().keySet())')):
            for guard in (
                'ConnectorSyncDirection.OUTBOUND',
                'ConnectorDataAuthority.INFRANEXUM',
                'ConnectorConflictStrategy.PREFER_AUTHORITY',
                'ConnectorDeletionPolicy.IGNORE',
                'ConnectorDeletionPolicy.TOMBSTONE',
                'ConnectorRollbackStrategy.MANUAL',
                'policy.executionEnabled()',
                'tombstoneConfigured',
                field_guard,
            ):
                self.assertIn(guard,catalog)
        self.assertIn('new JiraAssetsSyncHandler',jira)
        self.assertIn('new ServiceNowSyncHandler',service_now)
        self.assertIn('connectors.require(key)',service_now)
        self.assertIn('ServiceNowConnector require(ConnectorKey key)',service_now_registry)
        self.assertIn('return require(new ConnectorKey(connectorKey));',service_now_registry)

        config=(SERVER/'IntegrationRuntimeConfiguration.java').read_text(encoding='utf-8')
        self.assertIn('ConfiguredJiraAssetsSyncHandlerCatalog',config)
        self.assertIn('ConfiguredServiceNowSyncHandlerCatalog',config)
        self.assertIn('values.addAll(jiraHandlers.handlers())',config)
        self.assertIn('values.addAll(serviceNowHandlers.handlers())',config)
        self.assertIn('direction().mutating()',config)

    def test_remote_deletion_is_tombstone_only_and_disposed_is_the_only_local_trigger(self):
        outbound=(ROOT/'src/components/adapters/jdbc/main/io/infranexum/adapters/persistence/jdbc/JdbcItamAssetOutboundSource.java').read_text(encoding='utf-8')
        jira=(ROOT/'src/components/adapters/jira-assets/main/io/infranexum/adapters/jiraassets/JiraAssetsSyncHandler.java').read_text(encoding='utf-8')
        service_now=(ROOT/'src/components/adapters/service-now/main/io/infranexum/adapters/servicenow/ServiceNowSyncHandler.java').read_text(encoding='utf-8')
        self.assertIn('"DISPOSED".equals(resultSet.getString("lifecycle_status"))',outbound)
        self.assertNotIn('"RETIRED".equals(resultSet.getString("lifecycle_status"))',outbound)
        for handler in (jira,service_now):
            self.assertIn('context.propagateDeletions()',handler)
            self.assertIn('settings.tombstone()',handler)
            self.assertNotIn('.delete(',handler)
            self.assertNotIn('"DELETE"',handler)

    def test_java_empty_collection_fallbacks_are_explicitly_typed_and_registry_is_in_preflight(self):
        registry=(SERVER/'ImmutableConnectorSyncHandlerRegistry.java').read_text(encoding='utf-8')
        self.assertIn('List.<ConnectorSyncHandler>of()',registry)
        self.assertNotIn('Objects.requireNonNullElse(handlers, List.of())',registry)
        makefile=(ROOT/'Makefile').read_text(encoding='utf-8')
        self.assertIn('ImmutableConnectorSyncHandlerRegistry.java',makefile)
        self.assertIn('ConnectorSyncHandlerRegistrySmoke.java',makefile)
        self.assertIn('ConnectorSyncHandlerRegistrySmoke',makefile)

        import re
        offenders=[]
        for source in (ROOT/'src').rglob('*.java'):
            for lineno,line in enumerate(source.read_text(encoding='utf-8').splitlines(),1):
                if 'Objects.requireNonNullElse(' not in line:
                    continue
                if re.search(r'\b(?:List|Set|Map)\.of\(\)',line):
                    offenders.append(f'{source.relative_to(ROOT)}:{lineno}')
        self.assertEqual([],offenders,"untyped empty collection fallback(s): "+", ".join(offenders))

    def test_sync_runtime_metrics_are_low_cardinality_and_engine_owned(self):
        observer=(DOMAIN/'ConnectorSyncRuntimeObserver.java').read_text(encoding='utf-8')
        engine=(DOMAIN/'ConnectorSyncEngine.java').read_text(encoding='utf-8')
        micrometer=(SERVER/'MicrometerConnectorSyncRuntimeObserver.java').read_text(encoding='utf-8')
        config=(SERVER/'IntegrationRuntimeConfiguration.java').read_text(encoding='utf-8')
        for method in ('admitted(', 'resumed(', 'batchApplied(', 'paused(', 'compensationStarted(', 'terminal('):
            self.assertIn(method,observer)
            self.assertIn(method,engine)
        self.assertIn('ConnectorSyncPauseCause',observer)
        self.assertIn('MicrometerConnectorSyncRuntimeObserver',config)
        self.assertIn('ConnectorSyncRuntimeObserver observer',config)
        self.assertIn('PREFIX = "infranexum.integrations.sync."',micrometer)
        for suffix in ('admissions','activations','batches','records','pauses','compensations','terminal','duration'):
            self.assertIn('PREFIX + "'+suffix+'"',micrometer)
        for forbidden in ('failureCode', 'idempotencyKey', 'cursorSha256', 'payload'):
            self.assertNotIn('tag("'+forbidden+'"',micrometer)
        self.assertNotIn('result.failureCode()',micrometer)

    def test_browser_never_receives_raw_cursor_or_provider_credentials(self):
        client=(WEB/'connector-sync.mjs').read_text(encoding='utf-8')
        workspace=(WEB/'integrations-workspace.mjs').read_text(encoding='utf-8')
        self.assertIn("credentials:'same-origin'",client.replace(' ',''))
        self.assertIn("'Idempotency-Key'",client)
        self.assertIn("headers['X-CSRF-Token']",client)
        self.assertNotIn('Authorization',client)
        self.assertNotRegex(client.lower(),r'clientsecret|bearertoken|password')
        self.assertIn('cursorSha256',workspace)
        self.assertNotIn('item.cursor,',workspace)
        self.assertIn("['INBOUND','OUTBOUND','BIDIRECTIONAL']",workspace)

if __name__=='__main__': unittest.main()
