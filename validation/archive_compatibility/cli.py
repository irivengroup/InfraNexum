from __future__ import annotations

import argparse
import json
from pathlib import Path

from .checker import ArchiveCompatibilityChecker


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate portable InfraNexum source ZIP extraction")
    parser.add_argument("--archive", required=True, type=Path)
    parser.add_argument("--repository-root", type=Path)
    parser.add_argument("--json-report", type=Path)
    args = parser.parse_args()
    violations = ArchiveCompatibilityChecker(args.archive, args.repository_root).run()
    payload = {
        "schema": "infranexum.archive-compatibility-validation/v1",
        "violation_count": len(violations),
        "violations": [item.__dict__ for item in violations],
    }
    rendered = json.dumps(payload, indent=2, sort_keys=True)
    print(rendered)
    if args.json_report is not None:
        args.json_report.parent.mkdir(parents=True, exist_ok=True)
        args.json_report.write_text(rendered + "\n", encoding="utf-8")
    return 0 if not violations else 2


if __name__ == "__main__":
    raise SystemExit(main())
