from __future__ import annotations

import re
import sys
from pathlib import Path
from typing import Any

import yaml

HTTP_METHODS = {"get", "post", "put", "patch", "delete", "head", "options"}
PARAMETER = re.compile(r"\{([^{}]+)\}")
SAMPLE = "00000000-0000-7000-8000-000000000001"


def resolved_parameter(document: dict[str, Any], parameter: Any) -> dict[str, Any] | None:
    if not isinstance(parameter, dict):
        return None
    ref = parameter.get("$ref")
    if isinstance(ref, str) and ref.startswith("#/components/parameters/"):
        candidate = (document.get("components", {}).get("parameters", {}) or {}).get(ref.rsplit("/", 1)[1])
        return candidate if isinstance(candidate, dict) else None
    return parameter


def concrete_path(document: dict[str, Any], path: str, path_item: dict[str, Any], operation: dict[str, Any]) -> str:
    values: dict[str, str] = {}
    for raw in list(path_item.get("parameters") or []) + list(operation.get("parameters") or []):
        parameter = resolved_parameter(document, raw)
        if not parameter or parameter.get("in") != "path" or not isinstance(parameter.get("name"), str):
            continue
        schema = parameter.get("schema") if isinstance(parameter.get("schema"), dict) else {}
        enum = schema.get("enum")
        values[parameter["name"]] = str(enum[0]) if isinstance(enum, list) and enum else SAMPLE
    return PARAMETER.sub(lambda match: values.get(match.group(1), SAMPLE), path)


def main() -> int:
    if len(sys.argv) != 3:
        raise SystemExit("usage: generate_cases.py <openapi-dir> <destination>")
    root = Path(sys.argv[1])
    destination = Path(sys.argv[2])
    catalogue = yaml.safe_load((root / "catalogue.yaml").read_text(encoding="utf-8"))
    rows: list[tuple[str, str, str]] = []
    for fragment in catalogue["fragments"]:
        document = yaml.safe_load((root / fragment["file"]).read_text(encoding="utf-8"))
        for path, item in document.get("paths", {}).items():
            if not isinstance(item, dict):
                continue
            for method, operation in item.items():
                if method not in HTTP_METHODS or not isinstance(operation, dict):
                    continue
                rows.append((
                    concrete_path(document, path, item, operation),
                    operation["x-infranexum-capability"],
                    operation["operationId"],
                ))
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text("".join(f"{path}\t{capability}\t{operation_id}\n" for path, capability, operation_id in rows), encoding="utf-8")
    print(f"api-capability-cases: operations={len(rows)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
