
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from .checker import ArchitectureChecker


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Validate InfraNexum repository architecture")
    parser.add_argument("--root", type=Path, default=Path.cwd(), help="repository root")
    parser.add_argument("--policy", type=Path, required=True, help="architecture policy JSON")
    parser.add_argument("--json-report", type=Path, help="write deterministic JSON evidence")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    report = ArchitectureChecker(args.root, args.policy).run()
    payload = report.to_dict()
    if args.json_report:
        args.json_report.parent.mkdir(parents=True, exist_ok=True)
        args.json_report.write_text(
            json.dumps(payload, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
    if report.ok:
        print("Architecture-as-Code: PASS")
        return 0
    print(f"Architecture-as-Code: FAIL ({len(report.violations)} violation(s))", file=sys.stderr)
    for violation in report.violations:
        print(f"{violation.check_id} {violation.path}: {violation.message}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
