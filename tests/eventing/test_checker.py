from __future__ import annotations

import contextlib
import io
import json
import runpy
import shutil
import sys
import tempfile
import unittest
import warnings
from pathlib import Path
from unittest.mock import patch

from validation.eventing.checker import EventingChecker
from validation.eventing.cli import main as cli_main

SOURCE = Path(__file__).resolve().parents[2]
FILES = (
    "components/core/events/event-contract-pack.json",
    "components/core/events/event-envelope.schema.json",
    "components/core/events/main/io/infranexum/core/events/EventEnvelope.java",
    "distribution/migrations/0002-core-transactional-events/logical-model.json",
    "distribution/migrations/0002-core-transactional-events/postgresql.sql",
    "distribution/migrations/0002-core-transactional-events/oracle.sql",
)


class EventingCheckerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name) / "repository"
        self.root.mkdir()
        for relative in FILES:
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(SOURCE / relative, target)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def ids(self) -> set[str]:
        return {item.check_id for item in EventingChecker(self.root).run()}

    def load(self, relative: str) -> dict:
        return json.loads((self.root / relative).read_text(encoding="utf-8"))

    def write(self, relative: str, payload: object) -> None:
        (self.root / relative).write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")

    def test_reference_contract_is_valid(self) -> None:
        self.assertEqual(set(), self.ids())

    def test_invalid_or_missing_json_is_reported(self) -> None:
        path = self.root / "components/core/events/event-contract-pack.json"
        path.write_text("{", encoding="utf-8")
        self.assertIn("CHECK-EVENT-PACK-001", self.ids())
        path.unlink()
        self.assertIn("CHECK-EVENT-PACK-001", self.ids())

    def test_contract_pack_invariants_and_checksum_are_enforced(self) -> None:
        relative = "components/core/events/event-contract-pack.json"
        pack = self.load(relative)
        pack["schema"] = "unexpected"
        pack["envelope_fields"] = list(reversed(pack["envelope_fields"]))
        pack["delivery_guarantee"] = "exactly-once"
        pack["scope_rule"] = "shared-global-outbox"
        pack["envelope_schema_sha256"] = "0" * 64
        self.write(relative, pack)
        self.assertTrue({
            "CHECK-EVENT-PACK-002",
            "CHECK-EVENT-PACK-003",
            "CHECK-EVENT-PACK-004",
            "CHECK-EVENT-PACK-005",
            "CHECK-EVENT-PACK-007",
        } <= self.ids())
        del pack["envelope_schema_sha256"]
        self.write(relative, pack)
        self.assertIn("CHECK-EVENT-PACK-006", self.ids())

    def test_invalid_schema_and_model_json_are_reported(self) -> None:
        schema = self.root / "components/core/events/event-envelope.schema.json"
        model = self.root / "distribution/migrations/0002-core-transactional-events/logical-model.json"
        schema.write_text("{", encoding="utf-8")
        model.write_text("{", encoding="utf-8")
        self.assertTrue({"CHECK-EVENT-SCHEMA-001", "CHECK-EVENT-MODEL-001"} <= self.ids())

    def test_schema_order_closure_and_payload_shape_are_enforced(self) -> None:
        relative = "components/core/events/event-envelope.schema.json"
        schema = self.load(relative)
        schema["additionalProperties"] = True
        schema["required"] = list(reversed(schema["required"]))
        schema["properties"] = dict(reversed(list(schema["properties"].items())))
        schema["properties"]["payload"] = {"type": "string"}
        self.write(relative, schema)
        self.assertTrue({
            "CHECK-EVENT-SCHEMA-002",
            "CHECK-EVENT-SCHEMA-003",
            "CHECK-EVENT-SCHEMA-004",
        } <= self.ids())
        schema["properties"] = self.load("components/core/events/event-envelope.schema.json").get("properties", {})

    def test_payload_shape_branch_is_checked_when_property_order_is_valid(self) -> None:
        relative = "components/core/events/event-envelope.schema.json"
        schema = self.load(relative)
        schema["properties"]["payload"] = {"oneOf": [{"type": "string"}]}
        self.write(relative, schema)
        self.assertIn("CHECK-EVENT-SCHEMA-005", self.ids())

    def test_java_record_presence_and_field_order_are_enforced(self) -> None:
        path = self.root / "components/core/events/main/io/infranexum/core/events/EventEnvelope.java"
        original = path.read_text(encoding="utf-8")
        path.write_text(original.replace("DomainIdentifier eventId,", "DomainIdentifier wrongId,"), encoding="utf-8")
        self.assertIn("CHECK-EVENT-JAVA-003", self.ids())
        path.write_text("public record EventEnvelope(,) {}", encoding="utf-8")
        self.assertIn("CHECK-EVENT-JAVA-003", self.ids())
        path.write_text("package io.infranexum.core.events;", encoding="utf-8")
        self.assertIn("CHECK-EVENT-JAVA-002", self.ids())
        path.unlink()
        self.assertIn("CHECK-EVENT-JAVA-001", self.ids())

    def test_logical_model_requires_local_outbox_envelope_and_inbox_key(self) -> None:
        relative = "distribution/migrations/0002-core-transactional-events/logical-model.json"
        model = self.load(relative)
        model["objects"] = "invalid"
        self.write(relative, model)
        self.assertIn("CHECK-EVENT-MODEL-002", self.ids())

        model = self.load_from_source(relative)
        model["objects"] = [item for item in model["objects"] if item["logical_name"] != "core.outbox_event"]
        self.write(relative, model)
        self.assertIn("CHECK-EVENT-MODEL-003", self.ids())

        model = self.load_from_source(relative)
        outbox = next(item for item in model["objects"] if item["logical_name"] == "core.outbox_event")
        outbox["columns"] = [item for item in outbox["columns"] if item["name"] != "source"]
        inbox = next(item for item in model["objects"] if item["logical_name"] == "core.inbox_receipt")
        inbox["primary_key"] = ["event_id"]
        self.write(relative, model)
        self.assertTrue({"CHECK-EVENT-MODEL-004", "CHECK-EVENT-MODEL-005"} <= self.ids())

    def test_sql_columns_are_required_and_non_normative_fields_are_blocked(self) -> None:
        path = self.root / "distribution/migrations/0002-core-transactional-events/postgresql.sql"
        text = path.read_text(encoding="utf-8")
        path.write_text(
            text.replace("event_source", "removed_source") + "\n-- aggregate_id metadata_json\n",
            encoding="utf-8",
        )
        self.assertTrue({"CHECK-EVENT-SQL-002", "CHECK-EVENT-SQL-003"} <= self.ids())
        path.unlink()
        self.assertIn("CHECK-EVENT-SQL-001", self.ids())

    def test_external_paths_and_cli_are_covered(self) -> None:
        checker = EventingChecker(self.root)
        external = self.root.parent / "outside.json"
        checker._add("TEST", external, "outside")
        self.assertEqual(external.resolve().as_posix(), checker.violations[0].path)

        report = self.root / "reports/eventing.json"
        with patch.object(sys, "argv", ["eventing", "--root", str(self.root), "--json-report", str(report)]):
            with contextlib.redirect_stdout(io.StringIO()) as output:
                self.assertEqual(0, cli_main())
        self.assertEqual(0, json.loads(report.read_text(encoding="utf-8"))["violation_count"])
        self.assertIn("infranexum.eventing-validation/v1", output.getvalue())

        (self.root / "components/core/events/event-envelope.schema.json").write_text("{}", encoding="utf-8")
        with patch.object(sys, "argv", ["eventing", "--root", str(self.root)]):
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(1, cli_main())

        with patch.object(sys, "argv", ["eventing", "--root", str(self.root)]):
            with contextlib.redirect_stdout(io.StringIO()), warnings.catch_warnings():
                warnings.simplefilter("ignore", RuntimeWarning)
                with self.assertRaises(SystemExit) as raised:
                    runpy.run_module("validation.eventing.cli", run_name="__main__")
        self.assertEqual(1, raised.exception.code)

    @staticmethod
    def load_from_source(relative: str) -> dict:
        return json.loads((SOURCE / relative).read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
