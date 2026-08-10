"""CLI for InfraNexum source-integrity validation."""

from __future__ import annotations

import argparse
import json
from dataclasses import asdict
from pathlib import Path

from .checker import INVENTORY_PATH, SCHEMA, SourceIntegrityChecker


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate InfraNexum canonical source inventory")
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--json-report", type=Path)
    parser.add_argument("--require-git-tracking", action="store_true")
    parser.add_argument("--update-inventory", action="store_true")
    args = parser.parse_args()

    checker = SourceIntegrityChecker(args.root, require_git_tracking=True if args.require_git_tracking else None)
    if args.update_inventory:
        target = args.root.resolve() / INVENTORY_PATH
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(
            json.dumps({"schema": SCHEMA, "paths": sorted(checker.canonical_files())}, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        print(f"updated {target}")
        return 0

    violations = checker.run()
    payload = {
        "schema": "infranexum.source-integrity-validation/v1",
        "violation_count": len(violations),
        "violations": [asdict(item) for item in violations],
    }
    rendered = json.dumps(payload, indent=2, sort_keys=True)
    print(rendered)
    if args.json_report:
        args.json_report.parent.mkdir(parents=True, exist_ok=True)
        args.json_report.write_text(rendered + "\n", encoding="utf-8")
    return 1 if violations else 0


if __name__ == "__main__":
    raise SystemExit(main())
