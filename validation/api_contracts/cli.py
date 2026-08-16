from __future__ import annotations

import argparse
import json
from pathlib import Path

from .checker import ApiContractChecker


def parser() -> argparse.ArgumentParser:
    value = argparse.ArgumentParser(description="Validate InfraNexum REST/OpenAPI contracts.")
    value.add_argument("--root", type=Path, default=Path("."))
    value.add_argument("--json-report", type=Path)
    value.add_argument("--product-spec", type=Path)
    return value


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    checker = ApiContractChecker(args.root)
    report = checker.report()
    if args.json_report:
        args.json_report.parent.mkdir(parents=True, exist_ok=True)
        args.json_report.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    if report["violations"]:
        for violation in report["violations"]:
            print(f"{violation['check_id']} {violation['path']}: {violation['message']}")
        return 1
    if args.product_spec:
        checker.write_product_spec(args.product_spec)
    counts = report["debt_counts"]
    print(
        "api-contracts: PASS "
        f"fragments={report['fragments']} operations={report['operations']} "
        f"debt(idempotency={counts['idempotency']},pagination={counts['pagination']},"
        f"capability={counts['capability']},permission={counts['permission']})"
    )
    return 0


if __name__ == "__main__":  # pragma: no cover - module entry point
    raise SystemExit(main())
