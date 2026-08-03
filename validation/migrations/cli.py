from __future__ import annotations

import argparse
import json
from pathlib import Path

from .checker import MigrationChecker

def main() -> int:
    parser = argparse.ArgumentParser(description="Validate InfraNexum paired migration catalogue")
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--json-report", type=Path)
    args = parser.parse_args()
    violations = MigrationChecker(args.root).run()
    payload = {
        "schema": "infranexum.migration-validation/v1",
        "root": args.root.as_posix(),
        "violation_count": len(violations),
        "violations": [v.__dict__ for v in violations],
    }
    if args.json_report:
        args.json_report.parent.mkdir(parents=True, exist_ok=True)
        args.json_report.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(payload, indent=2, sort_keys=True))
    return 1 if violations else 0

if __name__ == "__main__":
    raise SystemExit(main())
