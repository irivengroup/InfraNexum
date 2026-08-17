from pathlib import Path
import unittest
import yaml

ROOT = Path(__file__).resolve().parents[2]
DOMAIN = ROOT / 'src/components/domains/integrations/main/io/infranexum/integrations'
SERVER = ROOT / 'src/applications/server/main/io/infranexum/server/integrations'
OPENAPI = ROOT / 'src/applications/server/resources/openapi/integrations-connectors.yaml'
WEB = ROOT / 'src/applications/web/public/assets'


class ConnectorGovernanceContractTest(unittest.TestCase):
    def test_domain_policy_is_provider_agnostic_and_has_no_rsot_or_itam_dependency(self):
        sources = '\n'.join(path.read_text(encoding='utf-8') for path in DOMAIN.glob('ConnectorGovernance*.java'))
        self.assertIn('FEDERATED_READ', (DOMAIN / 'ConnectorSyncDirection.java').read_text(encoding='utf-8'))
        self.assertIn('NONE_REQUIRED', (DOMAIN / 'ConnectorRollbackStrategy.java').read_text(encoding='utf-8'))
        self.assertIn('mutating connector direction requires an explicit rollback strategy', sources)
        self.assertNotIn('io.infranexum.rsot', sources)
        self.assertNotIn('io.infranexum.itam', sources)
        self.assertNotIn('io.infranexum.adapters', sources)

    def test_federated_read_policy_is_external_non_mutating_and_rollback_free(self):
        source = (DOMAIN / 'ConnectorGovernancePolicy.java').read_text(encoding='utf-8')
        self.assertIn('ConnectorSyncDirection.FEDERATED_READ', source)
        self.assertIn('ConnectorDataAuthority.EXTERNAL', source)
        self.assertIn('ConnectorDeletionPolicy.IGNORE', source)
        self.assertIn('ConnectorRollbackStrategy.NONE_REQUIRED', source)
        self.assertIn('federated-read must not declare local field mappings', source)

    def test_server_registry_unifies_jira_and_servicenow_and_rejects_duplicate_keys(self):
        source = (SERVER / 'ConfiguredConnectorGovernanceRegistry.java').read_text(encoding='utf-8')
        self.assertIn('JiraAssetsSettings.PROVIDER', source)
        self.assertIn('ServiceNowSettings.PROVIDER', source)
        self.assertIn('duplicate connector key across provider governance registry', source)
        self.assertIn('ConnectorGovernanceNotFoundException', source)

    def test_governance_api_is_read_permission_only_and_sync_plan_is_repeatable_dry_run(self):
        document = yaml.safe_load(OPENAPI.read_text(encoding='utf-8'))
        paths = document['paths']
        operations = [
            paths['/api/v1/integrations/governance']['get'],
            paths['/api/v1/integrations/governance/{connectorKey}']['get'],
            paths['/api/v1/integrations/governance/{connectorKey}/sync-plan']['post'],
        ]
        for operation in operations:
            self.assertEqual('integrations.connectors', operation['x-infranexum-capability'])
            self.assertEqual('integrations.connector.read', operation['x-infranexum-permission']['code'])
        plan = operations[-1]
        self.assertEqual('repeatable', plan['x-infranexum-idempotency'])
        self.assertIn('never mutates', plan['description'])

    def test_sync_plan_fails_closed_on_direction_fields_deletions_and_missing_rollback(self):
        source = (DOMAIN / 'ConnectorGovernancePlanner.java').read_text(encoding='utf-8')
        for invariant in [
            'requested direction is not enabled by connector policy',
            'field is not governed:',
            'mutating synchronization has no rollback strategy',
            'deletion propagation is disabled by connector policy',
        ]:
            self.assertIn(invariant, source)
        self.assertIn('ConnectorSyncPlan.Decision.DENY', source)

    def test_browser_governance_boundary_never_handles_provider_credentials(self):
        client = (WEB / 'connector-governance.mjs').read_text(encoding='utf-8')
        workspace = (WEB / 'integrations-workspace.mjs').read_text(encoding='utf-8')
        self.assertIn("credentials: 'same-origin'", client)
        self.assertIn("headers['X-CSRF-Token']", client)
        self.assertNotIn('Authorization', client)
        self.assertNotRegex(client.lower(), r'clientsecret|bearertoken|password')
        self.assertIn('rollbackStrategy', workspace)
        self.assertIn('client.plan(item.connectorKey', workspace)


if __name__ == '__main__':
    unittest.main()
