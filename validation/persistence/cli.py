from __future__ import annotations

import argparse
import json
from pathlib import Path

from .checker import PersistenceChecker


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate InfraNexum JDBC persistence contracts")
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--json-report", type=Path)
    args = parser.parse_args()
    violations = PersistenceChecker(args.root).run()
    payload = {
        "schema": "infranexum.persistence-validation/v1",
        "violation_count": len(violations),
        "violations": [item.__dict__ for item in violations],
    }
    if args.json_report:
        args.json_report.parent.mkdir(parents=True, exist_ok=True)
        args.json_report.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(payload, indent=2, sort_keys=True))
    return 1 if violations else 0


if __name__ == "__main__":
    raise SystemExit(main())
