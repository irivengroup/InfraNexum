"""Architecture and same-tranche Web parity regressions for PGM-08-E01 DDI/IPAM."""

from __future__ import annotations

import json
import unittest
from pathlib import Path

import yaml


class DdiIpamArchitectureTest(unittest.TestCase):
    ROOT = Path(__file__).resolve().parents[2]
    DOMAIN = ROOT / "src/components/domains/ddi/main/io/infranexum/ddi/ipam"
    SERVER = ROOT / "src/applications/server"
    WEB = ROOT / "src/applications/web"
    MIGRATION = ROOT / "src/distribution/migrations/0030-ddi-ipam-foundation"

    def test_domain_models_routing_network_pool_address_and_atomic_allocation(self) -> None:
        service = (self.DOMAIN / "application/IpamApplicationService.java").read_text(encoding="utf-8")
        cidr = (self.DOMAIN / "domain/IpCidr.java").read_text(encoding="utf-8")
        for token in (
            "lockRoutingEnvironment",
            "lockPool",
            "networkOverlaps",
            "poolOverlaps",
            "addressInUse",
            "DDI_CIDR_OVERLAP",
            "DDI_ADDRESS_CONFLICT",
            "DDI_POOL_REQUIRED",
        ):
            self.assertIn(token, service)
        self.assertIn("firstSortKey", cidr)
        self.assertIn("lastSortKey", cidr)
        self.assertIn("sortKey", cidr)

    def test_jdbc_overlap_checks_are_indexable_and_not_bounded_list_scans(self) -> None:
        jdbc = (self.ROOT / "src/components/adapters/jdbc/main/io/infranexum/adapters/persistence/jdbc/JdbcIpamRepository.java").read_text(encoding="utf-8")
        self.assertIn("FOR UPDATE", jdbc)
        self.assertIn("first_key<=?", jdbc)
        self.assertIn("last_key>=?", jdbc)
        self.assertIn("start_key<=?", jdbc)
        self.assertIn("end_key>=?", jdbc)
        self.assertNotIn("networks(organizationId, vrfId, 500)", jdbc)
        self.assertNotIn("pools(networkId, 500)", jdbc)

    def test_authority_boundaries_use_weak_cross_context_references(self) -> None:
        pg = (self.MIGRATION / "postgresql.sql").read_text(encoding="utf-8").lower()
        oracle = (self.MIGRATION / "oracle.sql").read_text(encoding="utf-8").lower()
        logical = json.loads((self.MIGRATION / "logical-model.json").read_text(encoding="utf-8"))
        for forbidden in (
            "references infranexum_org",
            "references infranexum_iam",
            "references infranexum_rsot",
            "references infranexum_itam",
        ):
            self.assertNotIn(forbidden, pg)
            self.assertNotIn(forbidden, oracle)
        address = next(item for item in logical["objects"] if item["name"] == "ipam_address")
        self.assertEqual(
            {"organization_id", "rsot_object_id", "dcim_equipment_id"},
            set(address["weak_references"]),
        )

    def test_openapi_exposes_native_ipam_operations_with_unique_ids(self) -> None:
        spec = yaml.safe_load((self.SERVER / "resources/openapi/ddi-ipam.yaml").read_text(encoding="utf-8"))
        operations = []
        for item in spec["paths"].values():
            operations.extend(value for key, value in item.items() if key in {"get", "post", "patch", "put", "delete"})
        self.assertEqual("3.1.0", spec["openapi"])
        self.assertEqual(15, len(operations))
        self.assertEqual(15, len({op["operationId"] for op in operations}))
        self.assertTrue(all(op["x-infranexum-capability"] == "ddi.ipam" for op in operations))
        self.assertIn("x-tagGroups", spec)

    def test_web_parity_has_first_level_ddi_route_and_governed_selectors(self) -> None:
        html = (self.WEB / "public/index.html").read_text(encoding="utf-8")
        shell = (self.WEB / "public/assets/admin-shell.mjs").read_text(encoding="utf-8")
        bootstrap = (self.WEB / "public/assets/bootstrap.mjs").read_text(encoding="utf-8")
        workspace = (self.WEB / "public/assets/ddi-ipam-workspace.mjs").read_text(encoding="utf-8")
        client = (self.WEB / "public/assets/ddi-ipam.mjs").read_text(encoding="utf-8")
        self.assertRegex(html, r'id="nav-ddi"[^>]+data-route="ddi"')
        self.assertRegex(html, r'id="ddi-workspace"[^>]+data-view="ddi"')
        self.assertIn("ddi: Object.freeze", shell)
        self.assertIn("initializeDdiIpamWorkspace(document, configuration, fetch)", bootstrap)
        self.assertIn("setDdiAvailability(documentObject, configuration.ddiIpamEnabled)", bootstrap)
        governed_selects = {
            "vrfId": ("ddi-network-vrf", "ddi-address-vrf"),
            "vlanId": ("ddi-network-vlan",),
            "parentNetworkId": ("ddi-network-parent",),
            "networkId": ("ddi-pool-network", "ddi-address-network"),
            "poolId": ("ddi-address-pool",),
            "rsotObjectId": ("ddi-address-rsot",),
            "dcimEquipmentId": ("ddi-address-equipment",),
        }
        for name, element_ids in governed_selects.items():
            for element_id in element_ids:
                self.assertRegex(workspace, rf'<select[^>]+name="{name}"[^>]+id="{element_id}"|<select[^>]+id="{element_id}"[^>]+name="{name}"')
        for name in ("vrfId", "parentNetworkId", "networkId", "poolId", "rsotObjectId", "dcimEquipmentId"):
            self.assertNotRegex(workspace, rf'<input[^>]+name=["\']{name}["\']')
        for token in ("createVrf", "createVlan", "createNetwork", "createPool", "allocate", "release", "updateNetwork"):
            self.assertIn(token, client)
        self.assertIn("If-Match", client)
        self.assertIn("Idempotency-Key", client)

    def test_runtime_uses_the_current_read_only_rsot_repository_contract(self) -> None:
        runtime = (self.SERVER / "main/io/infranexum/server/ddi/IpamRuntimeConfiguration.java").read_text(encoding="utf-8")
        repository = (self.ROOT / "src/components/adapters/jdbc/main/io/infranexum/adapters/persistence/jdbc/JdbcRsotRepository.java").read_text(encoding="utf-8")
        self.assertIn("JdbcRsotRepository(DataSource dataSource, JdbcDatabaseDialect dialect)", repository)
        self.assertIn("new JdbcRsotRepository(ds,dialect)", runtime)
        self.assertNotIn("new JdbcRsotRepository(ds,events,dialect)", runtime)

    def test_same_tranche_is_wired_into_compose_jdbc_and_foundation_gate(self) -> None:
        makefile = (self.ROOT / "Makefile").read_text(encoding="utf-8")
        compose = (self.ROOT / "docker/compose.yaml").read_text(encoding="utf-8")
        self.assertIn("java-ddi-ipam-smoke", makefile)
        self.assertIn("java-ddi-ipam-smoke", makefile[makefile.index("verify-foundation"):])
        self.assertIn("domains/ddi/main/io/infranexum/ddi/ipam/domain/*.java", makefile)
        self.assertIn("domains/ddi/main/io/infranexum/ddi/ipam/ports/*.java", makefile)
        self.assertIn("INFRANEXUM_DDI_IPAM_API_ENABLED", compose)
        self.assertIn("INFRANEXUM_WEB_DDI_IPAM_ENABLED", compose)


if __name__ == "__main__":
    unittest.main()
