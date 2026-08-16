"""Unit and regression tests for the PGM-10-E05 Python connector SDK."""

from __future__ import annotations

from copy import deepcopy
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import runpy
import sys
import tempfile
import unittest
from unittest.mock import patch

from infranexum_connector_sdk import (
    Connector,
    ConnectorContext,
    ConnectorManifest,
    ConnectorMode,
    ConnectorOutcome,
    ConnectorRequest,
    ConnectorResult,
    InMemoryReplayGuard,
    WebhookSigner,
    WebhookVerificationError,
    WebhookVerifier,
    canonical_json,
    compare_semver,
    manifest_schema,
    parse_unix_timestamp,
    validate_manifest,
)
from infranexum_connector_sdk.certify import certify_path, main
from infranexum_connector_sdk.models import immutable_mapping, require_delivery_id, require_semver, require_text, require_token
from infranexum_connector_sdk.webhook import MAX_WEBHOOK_BODY_BYTES

NOW = datetime(2026, 8, 16, 12, 0, tzinfo=timezone.utc)
SECRET = b"s" * 32


def valid_manifest() -> dict[str, object]:
    return {
        "schema": "infranexum.connector-manifest/v1",
        "id": "acme.inventory",
        "name": "ACME Inventory",
        "version": "1.2.3",
        "sdk": {"contractVersion": "1.0.0", "minimumVersion": "1.0.0"},
        "certification": {"level": "certified", "requiresIsolation": True, "evidence": ["sha256:" + "a" * 64]},
        "provider": {"product": "ACME CMDB", "supportedVersions": ["2026.1", "2026.2"]},
        "modes": ["pull", "webhook"],
        "capabilities": ["rsot.core"],
        "permissions": ["rsot.read"],
        "secrets": [{"name": "api-token", "purpose": "Provider API authentication", "required": True}],
        "egress": [{"scheme": "https", "host": "api.acme.example", "port": 443}],
        "authority": {
            "direction": "inbound",
            "conflictStrategy": "prefer-authority",
            "deletionPolicy": "tombstone",
            "fields": [{"field": "asset.serial", "authority": "external"}],
        },
        "delivery": {
            "idempotencyRequired": True,
            "checkpointing": True,
            "replay": "controlled",
            "maximumAttempts": 5,
            "initialBackoffSeconds": 5,
            "maximumBackoffSeconds": 300,
        },
        "webhook": {"incoming": True, "outgoing": False, "signature": "hmac-sha256", "maximumClockSkewSeconds": 300},
        "data": {"fields": [{"name": "asset.serial", "purpose": "Match authoritative asset", "classification": "internal"}]},
        "contracts": [{"name": "rsot.asset", "version": "1.0.0"}],
        "limits": {"timeoutSeconds": 30, "maximumConcurrency": 4, "maximumPayloadBytes": 65536},
        "support": {"owner": "team.automation-ecosystem", "endOfSupport": "2028-12-31"},
    }


class ManifestValidationTest(unittest.TestCase):
    def test_valid_manifest_is_canonical_and_immutable(self) -> None:
        document = valid_manifest()
        report = validate_manifest(document)
        self.assertTrue(report.valid, report.errors)
        self.assertEqual("acme.inventory", report.connector_id)
        self.assertEqual("1.2.3", report.connector_version)
        manifest = ConnectorManifest.from_json(json.dumps(document).encode())
        self.assertEqual(report.digest_sha256, manifest.digest_sha256)
        self.assertEqual(canonical_json(document), canonical_json(manifest.data))
        self.assertEqual("acme.inventory", manifest.connector_id)
        self.assertEqual("1.2.3", manifest.version)
        with self.assertRaises(TypeError):
            manifest.data["id"] = "mutated"  # type: ignore[index]
        with self.assertRaises(TypeError):
            manifest.data["authority"]["direction"] = "outbound"  # type: ignore[index]
        self.assertIsInstance(manifest.data["modes"], tuple)
        schema = manifest_schema()
        self.assertEqual("urn:infranexum:schema:connector-manifest:v1", schema["$id"])
        self.assertFalse(schema["additionalProperties"])
        with self.assertRaises(TypeError):
            schema["title"] = "mutated"  # type: ignore[index]

    def test_invalid_root_json_and_canonical_values_are_rejected(self) -> None:
        with self.assertRaises(ValueError):
            ConnectorManifest.from_json("[]")
        with self.assertRaises(ValueError):
            ConnectorManifest.from_json("{")
        with self.assertRaises(TypeError):
            ConnectorManifest.from_json(12)  # type: ignore[arg-type]
        with self.assertRaises(ValueError):
            ConnectorManifest.from_json("x" * 1_048_577)
        report = validate_manifest({"value": float("nan")})
        self.assertFalse(report.valid)
        self.assertIn("not canonical JSON", report.errors[0])
        root = validate_manifest([])  # type: ignore[arg-type]
        self.assertFalse(root.valid)

    def test_unknown_fields_and_bad_identity_are_rejected(self) -> None:
        document = valid_manifest()
        document["unexpected"] = True
        document["id"] = "ACME/unsafe"
        document["name"] = "\n"
        document["version"] = "01.2.3"
        report = validate_manifest(document)
        self.assertFalse(report.valid)
        self.assertTrue(any("unknown top-level" in item for item in report.errors))
        self.assertTrue(any("invalid id" in item for item in report.errors))
        self.assertTrue(any("invalid name" in item for item in report.errors))
        self.assertTrue(any("invalid version" in item for item in report.errors))
        with self.assertRaises(ValueError):
            ConnectorManifest(document)

    def test_sdk_certification_and_provider_contracts_are_strict(self) -> None:
        document = valid_manifest()
        document["sdk"] = {"contractVersion": "2.0.0", "minimumVersion": "bad", "extra": 1}
        document["certification"] = {"level": "community", "requiresIsolation": False, "evidence": ["bad"], "extra": 1}
        document["provider"] = {"product": "", "supportedVersions": ["*"], "extra": 1}
        report = validate_manifest(document)
        self.assertFalse(report.valid)
        joined = " | ".join(report.errors)
        self.assertIn("sdk contains unknown fields", joined)
        self.assertIn("contractVersion", joined)
        self.assertIn("community/validated", joined)
        self.assertIn("certification.evidence", joined)
        self.assertIn("provider.supportedVersions must not use wildcard", joined)
        document = valid_manifest()
        document["sdk"] = {"contractVersion": "1.0.0", "minimumVersion": "2.0.0"}
        self.assertTrue(any("newer SDK" in item for item in validate_manifest(document).errors))

    def test_community_connector_requires_isolation_and_warns(self) -> None:
        document = valid_manifest()
        document["certification"] = {"level": "community", "requiresIsolation": True, "evidence": []}
        report = validate_manifest(document)
        self.assertTrue(report.valid, report.errors)
        self.assertEqual(1, len(report.warnings))

    def test_modes_tokens_secrets_and_egress_are_bounded(self) -> None:
        document = valid_manifest()
        document["modes"] = ["pull", "pull", "invalid"]
        document["capabilities"] = ["RSOT.bad"]
        document["permissions"] = ["rsot.read", "rsot.read"]
        document["secrets"] = [{"name": "api-token", "purpose": "x", "required": True, "value": "forbidden"}]
        document["egress"] = [
            {"scheme": "http", "host": "*.example.com", "port": 0},
            {"scheme": "https", "host": "127.0.0.1", "port": 443},
        ]
        report = validate_manifest(document)
        self.assertFalse(report.valid)
        joined = " | ".join(report.errors)
        for needle in ("modes must be unique", "invalid modes", "invalid capabilities entry", "permissions must be a unique", "secret declaration", "egress.scheme", "exact DNS hostname", "not an IP literal", "egress.port"):
            self.assertIn(needle, joined)

    def test_authority_delivery_webhook_data_contract_limits_and_support_are_strict(self) -> None:
        document = valid_manifest()
        document["authority"] = {"direction": "bidirectional", "conflictStrategy": "bad", "deletionPolicy": "bad", "fields": []}
        document["delivery"] = {"idempotencyRequired": False, "checkpointing": "yes", "replay": "automatic", "maximumAttempts": 0, "initialBackoffSeconds": 100, "maximumBackoffSeconds": 10}
        document["webhook"] = {"incoming": False, "outgoing": False, "signature": "none", "maximumClockSkewSeconds": 0}
        document["data"] = {"fields": [{"name": "Bad Field", "purpose": "", "classification": "secret"}]}
        document["contracts"] = [{"name": "Bad", "version": "bad"}]
        document["limits"] = {"timeoutSeconds": 0, "maximumConcurrency": 0, "maximumPayloadBytes": 2_000_000}
        document["support"] = {"owner": "", "endOfSupport": "31-12-2028"}
        report = validate_manifest(document)
        self.assertFalse(report.valid)
        joined = " | ".join(report.errors)
        for needle in ("bidirectional authority", "invalid authority.conflictStrategy", "idempotencyRequired", "maximumAttempts", ">= initialBackoffSeconds", "webhook.signature", "webhook mode requires", "classification", "contract.name", "limits.timeoutSeconds", "support.endOfSupport"):
            self.assertIn(needle, joined)
        document = valid_manifest()
        document["support"] = {"owner": "team.automation-ecosystem", "endOfSupport": "2028-02-30"}
        self.assertTrue(any("real calendar date" in item for item in validate_manifest(document).errors))

    def test_webhook_flags_require_webhook_mode(self) -> None:
        document = valid_manifest()
        document["modes"] = ["pull"]
        report = validate_manifest(document)
        self.assertFalse(report.valid)
        self.assertTrue(any("webhook support requires webhook mode" in item for item in report.errors))

    def test_duplicate_nested_names_and_unknown_nested_fields_are_rejected(self) -> None:
        document = valid_manifest()
        document["secrets"] = [
            {"name": "api-token", "purpose": "one", "required": True},
            {"name": "api-token", "purpose": "two", "required": False},
        ]
        document["egress"] = [
            {"scheme": "https", "host": "api.acme.example", "port": 443},
            {"scheme": "https", "host": "api.acme.example", "port": 443},
        ]
        document["authority"]["fields"] = [  # type: ignore[index]
            {"field": "asset.serial", "authority": "external"},
            {"field": "asset.serial", "authority": "manual"},
        ]
        document["data"]["fields"] = [  # type: ignore[index]
            {"name": "asset.serial", "purpose": "one", "classification": "internal"},
            {"name": "asset.serial", "purpose": "two", "classification": "internal"},
        ]
        document["contracts"] = [
            {"name": "rsot.asset", "version": "1.0.0"},
            {"name": "rsot.asset", "version": "1.1.0"},
        ]
        report = validate_manifest(document)
        self.assertFalse(report.valid)
        joined = " | ".join(report.errors)
        for needle in ("secret names must be unique", "egress destinations must be unique", "authority.field mappings must be unique", "data field names must be unique", "contract names must be unique"):
            self.assertIn(needle, joined)


    def test_each_manifest_boundary_fails_closed(self) -> None:
        cases = [
            ("schema", lambda d: d.__setitem__("schema", "wrong"), "schema must be"),
            ("certification-level", lambda d: d.__setitem__("certification", {"level": "invalid", "requiresIsolation": "yes", "evidence": []}), "invalid certification.level"),
            ("provider-empty-versions", lambda d: d.__setitem__("provider", {"product": "ACME", "supportedVersions": []}), "supportedVersions must contain"),
            ("provider-bad-version", lambda d: d.__setitem__("provider", {"product": "ACME", "supportedVersions": [None]}), "supportedVersions entry must be a string"),
            ("secret-value-shape", lambda d: d.__setitem__("secrets", [{"name": "api-token", "purpose": None, "required": "yes"}]), "secret.purpose must be a string"),
            ("egress-item-shape", lambda d: d.__setitem__("egress", ["bad"]), "egress destination"),
            ("authority-unknown", lambda d: d.__setitem__("authority", {"direction": "sideways", "conflictStrategy": "reject", "deletionPolicy": "ignore", "fields": [], "extra": True}), "invalid authority.direction"),
            ("authority-fields-shape", lambda d: d.__setitem__("authority", {"direction": "inbound", "conflictStrategy": "reject", "deletionPolicy": "ignore", "fields": {}}), "authority.fields must be"),
            ("authority-item-shape", lambda d: d.__setitem__("authority", {"direction": "inbound", "conflictStrategy": "reject", "deletionPolicy": "ignore", "fields": ["bad"]}), "authority field mappings"),
            ("authority-item-values", lambda d: d.__setitem__("authority", {"direction": "inbound", "conflictStrategy": "reject", "deletionPolicy": "ignore", "fields": [{"field": "Bad Field", "authority": "nobody"}]}), "invalid field authority"),
            ("delivery-values", lambda d: d.__setitem__("delivery", {"idempotencyRequired": True, "checkpointing": True, "replay": "controlled", "maximumAttempts": 1, "initialBackoffSeconds": False, "maximumBackoffSeconds": False, "extra": 1}), "delivery contains unknown fields"),
            ("webhook-shape", lambda d: d.__setitem__("webhook", {"incoming": "yes", "outgoing": 1, "signature": "hmac-sha256", "maximumClockSkewSeconds": False, "extra": 1}), "webhook incoming/outgoing"),
            ("data-fields-shape", lambda d: d.__setitem__("data", {"fields": {}}), "data.fields must be"),
            ("data-item-shape", lambda d: d.__setitem__("data", {"fields": ["bad"]}), "data field declaration"),
            ("contract-item-shape", lambda d: d.__setitem__("contracts", ["bad"]), "contract declaration"),
            ("limits-shape", lambda d: d.__setitem__("limits", {"timeoutSeconds": 30, "maximumConcurrency": 4, "maximumPayloadBytes": 1, "extra": 1}), "limits contains invalid fields"),
            ("support-shape", lambda d: d.__setitem__("support", {"owner": "team", "endOfSupport": "2028-12-31", "extra": 1}), "support contains invalid fields"),
        ]
        for name, mutate, expected in cases:
            with self.subTest(name=name):
                document = valid_manifest()
                mutate(document)
                report = validate_manifest(document)
                self.assertFalse(report.valid)
                self.assertTrue(any(expected in error for error in report.errors), report.errors)

    def test_bad_container_shapes_are_rejected_without_crashing(self) -> None:
        fields = ["sdk", "certification", "provider", "authority", "delivery", "webhook", "data", "limits", "support"]
        for field in fields:
            with self.subTest(field=field):
                document = valid_manifest()
                document[field] = []
                self.assertFalse(validate_manifest(document).valid)
        for field in ["modes", "capabilities", "permissions", "secrets", "egress", "contracts"]:
            with self.subTest(field=field):
                document = valid_manifest()
                document[field] = {}
                self.assertFalse(validate_manifest(document).valid)


class ModelContractTest(unittest.TestCase):
    def test_validation_helpers_cover_nominal_and_invalid_values(self) -> None:
        self.assertEqual("hello", require_text(" hello ", "field", 10))
        self.assertEqual("rsot.read", require_token("rsot.read"))
        self.assertEqual("1.2.3-alpha.1+build", require_semver("1.2.3-alpha.1+build"))
        self.assertEqual("delivery-1", require_delivery_id("delivery-1"))
        for function, value in ((require_token, "Bad"), (require_semver, "1.0.0-01"), (require_delivery_id, "bad delivery")):
            with self.assertRaises(ValueError):
                function(value)  # type: ignore[arg-type]
        self.assertEqual(-1, compare_semver("1.0.0-alpha", "1.0.0"))
        self.assertEqual(1, compare_semver("1.1.0", "1.0.9"))
        self.assertEqual(0, compare_semver("1.0.0+one", "1.0.0+two"))
        self.assertEqual(-1, compare_semver("1.0.0-alpha.1", "1.0.0-alpha.beta"))
        self.assertEqual(1, compare_semver("1.0.0-beta", "1.0.0-alpha.9"))
        self.assertEqual(1, compare_semver("1.0.0-alpha.2", "1.0.0-alpha.1"))
        self.assertEqual(1, compare_semver("1.0.0-alpha.1.1", "1.0.0-alpha.1"))
        with self.assertRaises(TypeError):
            require_text(12, "field", 10)  # type: ignore[arg-type]
        self.assertEqual({}, immutable_mapping(None, "metadata"))
        with self.assertRaises(ValueError):
            immutable_mapping([], "metadata")  # type: ignore[arg-type]

    def test_context_request_and_result_enforce_governance(self) -> None:
        context = ConnectorContext("instance-1", "correlation-1", NOW + timedelta(minutes=1), frozenset({"rsot.core"}), {"tenant.id": "x"})
        request = ConnectorRequest(ConnectorMode.PULL, "asset.sync", "idem-1", {"page": 1}, "cp-1")
        result = ConnectorResult(ConnectorOutcome.RETRY, {"accepted": 2}, "cp-2", timedelta(seconds=10), "provider.busy")
        self.assertEqual("instance-1", context.connector_instance_id)
        self.assertEqual(ConnectorMode.PULL, request.mode)
        self.assertEqual(ConnectorOutcome.RETRY, result.outcome)
        with self.assertRaises(ValueError):
            ConnectorContext("instance", "correlation", datetime(2026, 1, 1), frozenset(), {})
        with self.assertRaises(ValueError):
            ConnectorRequest("pull", "Bad Operation", "x", {})  # type: ignore[arg-type]
        with self.assertRaises(ValueError):
            ConnectorResult("retry")  # type: ignore[arg-type]
        with self.assertRaises(ValueError):
            ConnectorResult("success", retry_after=timedelta(seconds=1))  # type: ignore[arg-type]
        with self.assertRaises(ValueError):
            ConnectorResult("failure", retry_after=timedelta(days=2))  # type: ignore[arg-type]

    def test_connector_abstract_contract_can_be_implemented(self) -> None:
        class Example(Connector):
            @property
            def manifest(self):
                return valid_manifest()

            def execute(self, context, request):
                return ConnectorResult(ConnectorOutcome.SUCCESS, {"operation": request.operation})

        connector = Example()
        result = connector.execute(ConnectorContext("i", "c", NOW + timedelta(seconds=30)), ConnectorRequest(ConnectorMode.PULL, "asset.sync", "key", {}))
        self.assertEqual(ConnectorOutcome.SUCCESS, result.outcome)


class WebhookContractTest(unittest.TestCase):
    def test_sign_verify_and_replay_detection(self) -> None:
        body = b'{"asset":"a-1"}'
        signature = WebhookSigner.sign(SECRET, body, "delivery-1", NOW)
        guard = InMemoryReplayGuard(2)
        verifier = WebhookVerifier(timedelta(minutes=5), guard)
        verifier.verify(SECRET, body, "delivery-1", NOW, signature, NOW)
        with self.assertRaisesRegex(WebhookVerificationError, "replayed"):
            verifier.verify(SECRET, body, "delivery-1", NOW, signature, NOW)

    def test_bad_signature_timestamp_body_and_secret_fail_closed(self) -> None:
        body = b"{}"
        signature = WebhookSigner.sign(SECRET, body, "delivery-2", NOW)
        verifier = WebhookVerifier()
        with self.assertRaisesRegex(WebhookVerificationError, "timestamp"):
            verifier.verify(SECRET, body, "delivery-2", NOW - timedelta(minutes=6), signature, NOW)
        with self.assertRaisesRegex(WebhookVerificationError, "signature"):
            verifier.verify(SECRET, body, "delivery-2", NOW, "sha256=" + "0" * 64, NOW)
        with self.assertRaises(ValueError):
            WebhookSigner.sign(b"short", body, "delivery-2", NOW)
        with self.assertRaises(WebhookVerificationError):
            WebhookSigner.sign(SECRET, b"x" * (MAX_WEBHOOK_BODY_BYTES + 1), "delivery-2", NOW)
        with self.assertRaises(ValueError):
            WebhookSigner.sign(SECRET, body, "delivery-2", datetime(2026, 1, 1))
        with self.assertRaises(TypeError):
            WebhookSigner.sign(SECRET, "{}", "delivery-2", NOW)  # type: ignore[arg-type]
        with self.assertRaises(ValueError):
            WebhookVerifier(timedelta(hours=2))

    def test_replay_guard_expires_and_evicts_bounded_entries(self) -> None:
        guard = InMemoryReplayGuard(2)
        self.assertTrue(guard.reserve("one", NOW + timedelta(seconds=1), NOW))
        self.assertFalse(guard.reserve("one", NOW + timedelta(seconds=1), NOW))
        self.assertTrue(guard.reserve("two", NOW + timedelta(minutes=1), NOW))
        self.assertTrue(guard.reserve("three", NOW + timedelta(minutes=1), NOW))
        self.assertTrue(guard.reserve("one", NOW + timedelta(minutes=2), NOW + timedelta(seconds=2)))
        expiry_guard = InMemoryReplayGuard(3)
        self.assertTrue(expiry_guard.reserve("expired", NOW + timedelta(seconds=1), NOW))
        self.assertTrue(expiry_guard.reserve("fresh", NOW + timedelta(minutes=1), NOW + timedelta(seconds=2)))
        with self.assertRaises(ValueError):
            InMemoryReplayGuard(0)
        with self.assertRaises(ValueError):
            guard.reserve("bad id", NOW + timedelta(minutes=1), NOW)
        with self.assertRaises(ValueError):
            guard.reserve("four", NOW, NOW)

    def test_unix_timestamp_parser_is_strict(self) -> None:
        parsed = parse_unix_timestamp(str(int(NOW.timestamp())))
        self.assertEqual(NOW, parsed)
        for value in ("not-a-number", "1234567890123", "-1", "999999999999"):
            with self.assertRaises(WebhookVerificationError):
                parse_unix_timestamp(value)
        body = b"{}"
        signature = WebhookSigner.sign(SECRET, body, "no-guard", NOW)
        WebhookVerifier().verify(SECRET, body, "no-guard", NOW, signature, NOW)


class CertificationCliTest(unittest.TestCase):
    def test_certify_path_and_cli_exit_codes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            valid = root / "valid.json"
            valid.write_text(json.dumps(valid_manifest()), encoding="utf-8")
            report = certify_path(valid)
            self.assertTrue(report["valid"])
            self.assertEqual(0, main([str(valid), "--output", "text"]))
            invalid = root / "invalid.json"
            invalid.write_text("{", encoding="utf-8")
            self.assertFalse(certify_path(invalid)["valid"])
            self.assertEqual(2, main([str(invalid)]))
            self.assertFalse(certify_path(root / "missing.json")["valid"])
            huge = root / "huge.json"
            huge.write_bytes(b" " * 1_048_577)
            self.assertFalse(certify_path(huge)["valid"])

            community = root / "community.json"
            community_document = valid_manifest()
            community_document["certification"] = {"level": "community", "requiresIsolation": True, "evidence": []}
            community.write_text(json.dumps(community_document), encoding="utf-8")
            self.assertEqual(0, main([str(community), "--output", "text"]))
            self.assertEqual(2, main([str(invalid), "--output", "text"]))

            with patch.object(sys, "argv", ["infranexum_connector_sdk", str(valid)]):
                with self.assertRaises(SystemExit) as exit_info:
                    runpy.run_module("infranexum_connector_sdk.__main__", run_name="__main__")
                self.assertEqual(0, exit_info.exception.code)


if __name__ == "__main__":
    unittest.main()
