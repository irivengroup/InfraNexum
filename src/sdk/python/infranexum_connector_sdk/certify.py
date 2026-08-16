"""Offline connector-manifest certification CLI."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

from .manifest import MAX_MANIFEST_BYTES, validate_manifest


def certify_path(path: Path) -> dict[str, object]:
    """Read and validate one manifest without importing connector code."""
    try:
        raw = path.read_bytes()
    except OSError as exc:
        return _failure(f"cannot read manifest: {exc.__class__.__name__}")
    if len(raw) > MAX_MANIFEST_BYTES:
        return _failure("manifest exceeds 1048576 bytes")
    try:
        document = json.loads(raw)
    except (json.JSONDecodeError, UnicodeDecodeError):
        return _failure("manifest is not valid UTF-8 JSON")
    report = validate_manifest(document)
    return report.as_dict()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="infranexum-connector-certify", description="Validate an InfraNexum connector manifest offline.")
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--output", choices=("json", "text"), default="json")
    args = parser.parse_args(argv)
    report = certify_path(args.manifest)
    if args.output == "json":
        print(json.dumps(report, ensure_ascii=False, sort_keys=True, separators=(",", ":")))
    else:
        state = "VALID" if report["valid"] else "INVALID"
        print(f"{state} {report.get('connectorId') or '-'} {report.get('connectorVersion') or '-'} {report['digestSha256']}")
        for error in report.get("errors", []):
            print(f"ERROR {error}", file=sys.stderr)
        for warning in report.get("warnings", []):
            print(f"WARNING {warning}", file=sys.stderr)
    return 0 if report["valid"] else 2


def _failure(message: str) -> dict[str, object]:
    return {
        "schema": "infranexum.connector-certification-report/v1",
        "valid": False,
        "digestSha256": "0" * 64,
        "connectorId": None,
        "connectorVersion": None,
        "errors": [message],
        "warnings": [],
    }


if __name__ == "__main__":
    raise SystemExit(main())
