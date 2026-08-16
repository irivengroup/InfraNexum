from __future__ import annotations

import copy
import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable

import yaml

_HTTP_METHODS = {"get", "post", "put", "patch", "delete", "head", "options"}
_MUTATING_METHODS = {"post", "put", "patch", "delete"}
_PROHIBITED_TAGS = {"default", "misc", "utils", "helpers", "common", "divers", "autres", "général", "general"}
_LIST_OPERATION = re.compile(r"^(list|search)", re.IGNORECASE)
_COMPONENT_REF = re.compile(r"^#/components/([^/]+)/([^/]+)$")
_SAFE_COMPONENT = re.compile(r"[^A-Za-z0-9_.-]+")


class _UniqueKeyLoader(yaml.SafeLoader):
    """Safe YAML loader that rejects duplicate mapping keys."""


def _construct_unique_mapping(loader: _UniqueKeyLoader, node: yaml.nodes.MappingNode, deep: bool = False) -> dict[Any, Any]:
    explicit: set[Any] = set()
    for key_node, _ in node.value:
        if key_node.tag == "tag:yaml.org,2002:merge":
            continue
        key = loader.construct_object(key_node, deep=False)
        if key in explicit:
            raise yaml.constructor.ConstructorError(
                "while constructing a mapping",
                node.start_mark,
                f"found duplicate key {key!r}",
                key_node.start_mark,
            )
        explicit.add(key)
    loader.flatten_mapping(node)
    mapping: dict[Any, Any] = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        mapping[key] = loader.construct_object(value_node, deep=deep)
    return mapping


_UniqueKeyLoader.add_constructor(
    yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG,
    _construct_unique_mapping,
)


@dataclass(frozen=True, order=True)
class ApiContractViolation:
    check_id: str
    path: str
    message: str


@dataclass(frozen=True)
class ApiContractDebt:
    idempotency: frozenset[str]
    pagination: frozenset[str]
    capability: frozenset[str]
    permission: frozenset[str]

    @classmethod
    def empty(cls) -> "ApiContractDebt":
        return cls(frozenset(), frozenset(), frozenset(), frozenset())

    def as_dict(self) -> dict[str, list[str]]:
        return {
            "idempotency": sorted(self.idempotency),
            "pagination": sorted(self.pagination),
            "capability": sorted(self.capability),
            "permission": sorted(self.permission),
        }


@dataclass(frozen=True)
class _Operation:
    source: Path
    path: str
    method: str
    operation_id: str
    operation: dict[str, Any]
    path_item: dict[str, Any]
    document: dict[str, Any]


class ApiContractChecker:
    """Validates the canonical OpenAPI fragment registry and ratchets historical debt."""

    def __init__(self, root: Path) -> None:
        self.root = root.resolve()
        self.openapi_root = self.root / "src/applications/server/resources/openapi"
        self.catalogue_path = self.openapi_root / "catalogue.yaml"
        self.baseline_path = self.root / "validation/api_contracts/baseline.json"
        self.version_path = self.root / "VERSION"
        self.violations: list[ApiContractViolation] = []
        self.documents: dict[str, dict[str, Any]] = {}
        self.operations: list[_Operation] = []
        self.current_debt = ApiContractDebt.empty()

    def run(self) -> tuple[ApiContractViolation, ...]:
        self.violations.clear()
        self.documents.clear()
        self.operations.clear()
        version = self._read_version()
        catalogue = self._load_yaml(self.catalogue_path, "CHECK-API-001")
        if not isinstance(catalogue, dict):
            if catalogue is not None:
                self._add("CHECK-API-001", self.catalogue_path, "OpenAPI catalogue must be a mapping")
            return self._result()
        self._validate_catalogue(catalogue, version)
        self._validate_documents(version)
        self._validate_operations()
        self.current_debt = self._calculate_debt()
        self._validate_debt_ratchet(self.current_debt)
        return self._result()

    def build_product_spec(self) -> dict[str, Any]:
        """Build one deterministic OpenAPI 3.1 product contract from certified fragments."""
        if self.run():
            raise ValueError("cannot assemble product OpenAPI while API contract violations exist")
        version = self._read_version()
        product: dict[str, Any] = {
            "openapi": "3.1.0",
            "info": {
                "title": "InfraNexum — Product API",
                "version": version,
                "description": "Generated complete product contract; installation availability remains capability-gated.",
            },
            "servers": [{"url": "/"}],
            "tags": [],
            "x-tagGroups": [],
            "x-infranexum-contract": "product-complete",
            "x-infranexum-generated-from": sorted(self.documents),
            "paths": {},
            "components": {},
        }
        tag_names: set[str] = set()
        groups: dict[str, list[str]] = {}
        for filename in sorted(self.documents):
            document = self.documents[filename]
            prefix = _SAFE_COMPONENT.sub("_", Path(filename).stem).strip("_")
            rewritten = self._rewrite_fragment(document, prefix)
            for tag in rewritten.get("tags", []) or []:
                name = tag.get("name") if isinstance(tag, dict) else None
                if isinstance(name, str) and name not in tag_names:
                    product["tags"].append(copy.deepcopy(tag))
                    tag_names.add(name)
            for group in rewritten.get("x-tagGroups", []) or []:
                if not isinstance(group, dict):
                    continue
                name = group.get("name")
                tags = group.get("tags")
                if not isinstance(name, str) or not isinstance(tags, list):
                    continue
                target = groups.setdefault(name, [])
                for tag in tags:
                    if isinstance(tag, str) and tag not in target:
                        target.append(tag)
            for path, item in (rewritten.get("paths") or {}).items():
                if path not in product["paths"]:
                    product["paths"][path] = copy.deepcopy(item)
                    continue
                target = product["paths"][path]
                for key, value in item.items():
                    if key in _HTTP_METHODS and key in target:
                        raise ValueError(f"duplicate operation while assembling {key.upper()} {path}")
                    if key == "parameters":
                        target.setdefault("parameters", [])
                        target["parameters"].extend(copy.deepcopy(value or []))
                    elif key not in target:
                        target[key] = copy.deepcopy(value)
            for category, values in (rewritten.get("components") or {}).items():
                target = product["components"].setdefault(category, {})
                for name, value in (values or {}).items():
                    if name in target:
                        raise ValueError(f"duplicate component while assembling {category}/{name}")
                    target[name] = copy.deepcopy(value)
        product["x-tagGroups"] = [{"name": name, "tags": tags} for name, tags in sorted(groups.items())]
        return product

    def write_product_spec(self, destination: Path) -> None:
        destination.parent.mkdir(parents=True, exist_ok=True)
        payload = self.build_product_spec()
        destination.write_text(
            yaml.safe_dump(payload, sort_keys=False, allow_unicode=True, width=120),
            encoding="utf-8",
        )

    def report(self) -> dict[str, Any]:
        violations = self.run()
        return {
            "schema": "infranexum.api-contract-validation/v1",
            "version": self._read_version(),
            "fragments": len(self.documents),
            "operations": len(self.operations),
            "debt": self.current_debt.as_dict(),
            "debt_counts": {key: len(value) for key, value in self.current_debt.as_dict().items()},
            "violations": [
                {"check_id": item.check_id, "path": item.path, "message": item.message}
                for item in violations
            ],
        }

    def _read_version(self) -> str:
        try:
            value = self.version_path.read_text(encoding="utf-8").strip()
        except OSError as error:
            self._add("CHECK-API-002", self.version_path, f"VERSION cannot be read: {error}")
            return ""
        if not re.fullmatch(r"2\.0\.0-alpha\.0\.[0-9]+", value):
            self._add("CHECK-API-002", self.version_path, "implementation version is not an InfraNexum 2.0 alpha version")
        return value

    def _validate_catalogue(self, catalogue: dict[str, Any], version: str) -> None:
        if catalogue.get("schema") != "infranexum.openapi-catalogue/v1":
            self._add("CHECK-API-003", self.catalogue_path, "unsupported OpenAPI catalogue schema")
        if catalogue.get("version") != version:
            self._add("CHECK-API-004", self.catalogue_path, "catalogue version must match VERSION")
        fragments = catalogue.get("fragments")
        if not isinstance(fragments, list) or not fragments:
            self._add("CHECK-API-005", self.catalogue_path, "catalogue fragments must be a non-empty list")
            return
        listed: list[str] = []
        for item in fragments:
            if not isinstance(item, dict):
                self._add("CHECK-API-005", self.catalogue_path, "each catalogue fragment must be an object")
                continue
            filename = item.get("file")
            component = item.get("component")
            context = item.get("context")
            if not all(isinstance(value, str) and value.strip() for value in (filename, component, context)):
                self._add("CHECK-API-005", self.catalogue_path, "fragment file/component/context are mandatory strings")
                continue
            listed.append(filename)
        if len(listed) != len(set(listed)):
            self._add("CHECK-API-006", self.catalogue_path, "catalogue contains duplicate fragment entries")
        actual = sorted(path.name for path in self.openapi_root.glob("*.yaml") if path.name != "catalogue.yaml")
        if sorted(listed) != actual:
            self._add(
                "CHECK-API-007",
                self.catalogue_path,
                f"catalogue/openapi directory mismatch; listed={sorted(listed)}, actual={actual}",
            )
        for filename in listed:
            path = self.openapi_root / filename
            document = self._load_yaml(path, "CHECK-API-008")
            if isinstance(document, dict):
                self.documents[filename] = document

    def _validate_documents(self, version: str) -> None:
        for filename, document in sorted(self.documents.items()):
            path = self.openapi_root / filename
            openapi = str(document.get("openapi", ""))
            if not openapi.startswith("3.1."):
                self._add("CHECK-API-009", path, "public OpenAPI documents must use OpenAPI 3.1.x")
            info = document.get("info")
            if not isinstance(info, dict) or info.get("version") != version:
                self._add("CHECK-API-010", path, "info.version must match VERSION")
            tags = document.get("tags")
            groups = document.get("x-tagGroups")
            if not isinstance(tags, list) or not tags or not isinstance(groups, list) or not groups:
                self._add("CHECK-API-011", path, "tags and x-tagGroups are mandatory")
            safe_tags = tags if isinstance(tags, list) else []
            safe_groups = groups if isinstance(groups, list) else []
            defined = {tag.get("name") for tag in safe_tags if isinstance(tag, dict) and isinstance(tag.get("name"), str)}
            grouped: list[str] = []
            for group in safe_groups:
                if isinstance(group, dict) and isinstance(group.get("tags"), list):
                    grouped.extend(tag for tag in group["tags"] if isinstance(tag, str))
            if defined != set(grouped) or len(grouped) != len(set(grouped)):
                self._add("CHECK-API-012", path, "every declared tag must appear exactly once in x-tagGroups")
            for tag in defined:
                lowered = tag.casefold().strip()
                if lowered in _PROHIBITED_TAGS:
                    self._add("CHECK-API-013", path, f"prohibited technical tag: {tag}")
                if " / " not in tag:
                    self._add("CHECK-API-014", path, f"tag must encode component/context hierarchy: {tag}")
            paths = document.get("paths")
            if not isinstance(paths, dict):
                self._add("CHECK-API-015", path, "paths must be an object")
                continue
            for api_path, path_item in paths.items():
                if not isinstance(api_path, str) or not api_path.startswith("/api/v1/"):
                    self._add("CHECK-API-016", path, f"public path must start with /api/v1/: {api_path}")
                    continue
                if not isinstance(path_item, dict):
                    self._add("CHECK-API-015", path, f"path item must be an object: {api_path}")
                    continue
                for method, operation in path_item.items():
                    if method not in _HTTP_METHODS or not isinstance(operation, dict):
                        continue
                    operation_id = operation.get("operationId")
                    if not isinstance(operation_id, str) or not operation_id.strip():
                        self._add("CHECK-API-017", path, f"missing operationId on {method.upper()} {api_path}")
                        continue
                    self.operations.append(_Operation(path, api_path, method, operation_id, operation, path_item, document))

    def _validate_operations(self) -> None:
        seen_operation_ids: dict[str, _Operation] = {}
        seen_routes: dict[tuple[str, str], _Operation] = {}
        for item in self.operations:
            path = item.source
            previous = seen_operation_ids.get(item.operation_id)
            if previous is not None:
                self._add("CHECK-API-018", path, f"duplicate operationId {item.operation_id} also used by {previous.source.name}")
            seen_operation_ids[item.operation_id] = item
            route = (item.method, item.path)
            previous_route = seen_routes.get(route)
            if previous_route is not None:
                self._add("CHECK-API-019", path, f"duplicate route {item.method.upper()} {item.path}")
            seen_routes[route] = item
            summary = item.operation.get("summary")
            if not isinstance(summary, str) or not summary.strip():
                self._add("CHECK-API-020", path, f"summary is mandatory for {item.operation_id}")
            tags = item.operation.get("tags")
            if not isinstance(tags, list) or len(tags) != 1 or not isinstance(tags[0], str):
                self._add("CHECK-API-021", path, f"{item.operation_id} must have exactly one functional tag")
            else:
                declared = {tag.get("name") for tag in item.document.get("tags", []) if isinstance(tag, dict)}
                if tags[0] not in declared:
                    self._add("CHECK-API-021", path, f"{item.operation_id} references undeclared tag {tags[0]}")
            security = item.operation.get("security", item.document.get("security"))
            if security is None:
                self._add("CHECK-API-022", path, f"security must be explicitly inherited or declared for {item.operation_id}")
            responses = item.operation.get("responses")
            if not isinstance(responses, dict) or not responses:
                self._add("CHECK-API-023", path, f"responses are mandatory for {item.operation_id}")
            else:
                for status, response in responses.items():
                    if not str(status).startswith(("4", "5")):
                        continue
                    if isinstance(response, dict) and "$ref" in response:
                        continue
                    content = response.get("content") if isinstance(response, dict) else None
                    if not isinstance(content, dict) or "application/problem+json" not in content:
                        self._add(
                            "CHECK-API-024",
                            path,
                            f"{item.operation_id} HTTP {status} must use application/problem+json",
                        )
            self._validate_local_refs(item)

    def _validate_local_refs(self, item: _Operation) -> None:
        components = item.document.get("components") if isinstance(item.document.get("components"), dict) else {}
        for ref in self._collect_refs(item.operation):
            match = _COMPONENT_REF.fullmatch(ref)
            if not match:
                continue
            category, name = match.groups()
            values = components.get(category) if isinstance(components, dict) else None
            if not isinstance(values, dict) or name not in values:
                self._add("CHECK-API-025", item.source, f"unresolved reference {ref} in {item.operation_id}")

    def _calculate_debt(self) -> ApiContractDebt:
        idempotency: set[str] = set()
        pagination: set[str] = set()
        capability: set[str] = set()
        permission: set[str] = set()
        for item in self.operations:
            params = self._parameter_names(item)
            if item.method in _MUTATING_METHODS and "Idempotency-Key" not in params:
                idempotency.add(item.operation_id)
            if _LIST_OPERATION.match(item.operation_id):
                has_size = "limit" in params or "page_size" in params
                has_position = any(name in params for name in ("cursor", "offset", "after_sequence", "after_version"))
                if not (has_size and has_position):
                    pagination.add(item.operation_id)
            if not item.operation.get("x-infranexum-capability"):
                capability.add(item.operation_id)
            if not item.operation.get("x-infranexum-permission"):
                permission.add(item.operation_id)
        return ApiContractDebt(
            frozenset(idempotency),
            frozenset(pagination),
            frozenset(capability),
            frozenset(permission),
        )

    def _validate_debt_ratchet(self, current: ApiContractDebt) -> None:
        baseline = self._load_json(self.baseline_path, "CHECK-API-026")
        if not isinstance(baseline, dict):
            return
        if baseline.get("schema") != "infranexum.api-contract-debt/v1":
            self._add("CHECK-API-027", self.baseline_path, "unsupported API contract debt schema")
            return
        debt = baseline.get("debt")
        if not isinstance(debt, dict):
            self._add("CHECK-API-027", self.baseline_path, "baseline debt must be an object")
            return
        for category, values in current.as_dict().items():
            allowed = debt.get(category)
            if not isinstance(allowed, list) or any(not isinstance(value, str) for value in allowed):
                self._add("CHECK-API-027", self.baseline_path, f"baseline category {category} must be a string list")
                continue
            new_debt = sorted(set(values) - set(allowed))
            if new_debt:
                self._add(
                    "CHECK-API-028",
                    self.baseline_path,
                    f"API contract debt may only decrease; new {category} debt={new_debt}",
                )

    def _parameter_names(self, item: _Operation) -> set[str]:
        result: set[str] = set()
        parameters = list(item.path_item.get("parameters") or []) + list(item.operation.get("parameters") or [])
        component_parameters = ((item.document.get("components") or {}).get("parameters") or {})
        for parameter in parameters:
            if not isinstance(parameter, dict):
                continue
            resolved = parameter
            ref = parameter.get("$ref")
            if isinstance(ref, str):
                match = _COMPONENT_REF.fullmatch(ref)
                if match and match.group(1) == "parameters":
                    candidate = component_parameters.get(match.group(2))
                    if isinstance(candidate, dict):
                        resolved = candidate
            name = resolved.get("name")
            if isinstance(name, str):
                result.add(name)
        return result

    def _rewrite_fragment(self, document: dict[str, Any], prefix: str) -> dict[str, Any]:
        rewritten = copy.deepcopy(document)
        rename: dict[tuple[str, str], str] = {}
        components = rewritten.get("components")
        if isinstance(components, dict):
            for category, values in components.items():
                if not isinstance(values, dict):
                    continue
                for name in values:
                    rename[(category, name)] = f"{prefix}__{name}"
            new_components: dict[str, dict[str, Any]] = {}
            for category, values in components.items():
                if not isinstance(values, dict):
                    continue
                target: dict[str, Any] = {}
                for name, value in values.items():
                    target[rename[(category, name)]] = value
                new_components[category] = target
            rewritten["components"] = new_components
        self._rewrite_refs(rewritten, rename)
        root_security = rewritten.get("security")
        paths = rewritten.get("paths")
        if isinstance(paths, dict):
            for path_item in paths.values():
                if not isinstance(path_item, dict):
                    continue
                for method, operation in path_item.items():
                    if method not in _HTTP_METHODS or not isinstance(operation, dict):
                        continue
                    if "security" not in operation and root_security is not None:
                        operation["security"] = copy.deepcopy(root_security)
        rewritten.pop("security", None)
        return rewritten

    def _rewrite_refs(self, value: Any, rename: dict[tuple[str, str], str]) -> None:
        if isinstance(value, dict):
            ref = value.get("$ref")
            if isinstance(ref, str):
                match = _COMPONENT_REF.fullmatch(ref)
                if match:
                    key = (match.group(1), match.group(2))
                    if key in rename:
                        value["$ref"] = f"#/components/{key[0]}/{rename[key]}"
            for child in value.values():
                self._rewrite_refs(child, rename)
        elif isinstance(value, list):
            for child in value:
                self._rewrite_refs(child, rename)

    @staticmethod
    def _collect_refs(value: Any) -> Iterable[str]:
        if isinstance(value, dict):
            ref = value.get("$ref")
            if isinstance(ref, str):
                yield ref
            for child in value.values():
                yield from ApiContractChecker._collect_refs(child)
        elif isinstance(value, list):
            for child in value:
                yield from ApiContractChecker._collect_refs(child)

    def _load_yaml(self, path: Path, check_id: str) -> Any | None:
        try:
            return yaml.load(path.read_text(encoding="utf-8"), Loader=_UniqueKeyLoader)
        except (OSError, yaml.YAMLError) as error:
            self._add(check_id, path, f"invalid YAML: {error}")
            return None

    def _load_json(self, path: Path, check_id: str) -> Any | None:
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as error:
            self._add(check_id, path, f"invalid JSON: {error}")
            return None

    def _add(self, check_id: str, path: Path, message: str) -> None:
        try:
            relative = path.resolve().relative_to(self.root).as_posix()
        except (ValueError, OSError):
            relative = str(path)
        self.violations.append(ApiContractViolation(check_id, relative, message))

    def _result(self) -> tuple[ApiContractViolation, ...]:
        return tuple(sorted(set(self.violations)))
