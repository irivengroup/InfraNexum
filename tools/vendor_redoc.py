"""Acquire and attest the pinned ReDoc runtime assets for offline InfraNexum use."""

from __future__ import annotations

import argparse
import base64
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import tarfile
import tempfile
from typing import Mapping, Protocol
from urllib.parse import urlparse
from urllib.request import Request, urlopen

REDOC_VERSION = "2.5.3"
REDOC_COMMIT = "1b2591e"
REDOC_BUNDLE_SIZE = 1_097_270
NPM_METADATA_URL = f"https://registry.npmjs.org/redoc/{REDOC_VERSION}"
NPM_TARBALL_URL = f"https://registry.npmjs.org/redoc/-/redoc-{REDOC_VERSION}.tgz"
JSDELIVR_BUNDLE_URL = f"https://cdn.jsdelivr.net/npm/redoc@{REDOC_VERSION}/bundles/redoc.standalone.js"
NELMIO_COMMIT = "2c13846cb4c504b2eb0a993241d6971c3c027a65"
NELMIO_BUNDLE_URL = (
    "https://raw.githubusercontent.com/nelmio/NelmioApiDocBundle/"
    f"{NELMIO_COMMIT}/public/redocly/redoc.standalone.js"
)
ALLOWED_HOSTS = frozenset(
    {
        "registry.npmjs.org",
        "cdn.jsdelivr.net",
        "raw.githubusercontent.com",
    }
)
MAX_METADATA_BYTES = 512 * 1024
MAX_TARBALL_BYTES = 16 * 1024 * 1024
MAX_BUNDLE_BYTES = 2 * 1024 * 1024
MAX_TEXT_BYTES = 2 * 1024 * 1024


class VendorAcquisitionError(RuntimeError):
    """Stable failure carrying a machine-readable error code for release gates."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


class Fetcher(Protocol):
    """Network boundary used by the acquirer and replaced with a fake in tests."""

    def get(self, url: str, maximum_bytes: int) -> bytes:
        """Return a bounded HTTPS response body or raise VendorAcquisitionError."""


class UrlLibFetcher:
    """Bounded HTTPS client restricted to the explicitly approved upstream hosts."""

    def __init__(self, timeout_seconds: float = 20.0) -> None:
        self._timeout_seconds = timeout_seconds

    def get(self, url: str, maximum_bytes: int) -> bytes:
        parsed = urlparse(url)
        if parsed.scheme != "https" or parsed.hostname not in ALLOWED_HOSTS:
            raise VendorAcquisitionError("REDOC_VENDOR_URL_REJECTED", f"unapproved vendor URL: {url}")
        request = Request(url, headers={"User-Agent": "InfraNexum-ReDoc-Vendor/1"})
        try:
            with urlopen(request, timeout=self._timeout_seconds) as response:  # noqa: S310 - hosts are allowlisted above.
                final_url = response.geturl()
                final = urlparse(final_url)
                if final.scheme != "https" or final.hostname not in ALLOWED_HOSTS:
                    raise VendorAcquisitionError(
                        "REDOC_VENDOR_REDIRECT_REJECTED",
                        f"vendor redirect left the approved host set: {final_url}",
                    )
                content_length = response.headers.get("Content-Length")
                if content_length and int(content_length) > maximum_bytes:
                    raise VendorAcquisitionError("REDOC_VENDOR_RESPONSE_TOO_LARGE", f"vendor response exceeds {maximum_bytes} bytes")
                body = response.read(maximum_bytes + 1)
        except VendorAcquisitionError:
            raise
        except (OSError, ValueError) as exc:
            raise VendorAcquisitionError("REDOC_VENDOR_DOWNLOAD_FAILED", f"unable to download {url}: {exc}") from exc
        if len(body) > maximum_bytes:
            raise VendorAcquisitionError("REDOC_VENDOR_RESPONSE_TOO_LARGE", f"vendor response exceeds {maximum_bytes} bytes")
        return body


class RedocVendorAcquirer:
    """Build-time service that materializes a byte-for-byte attested ReDoc vendor tree."""

    _members = {
        "redoc.standalone.js": "package/bundles/redoc.standalone.js",
        "LICENSE": "package/LICENSE",
        "redoc.standalone.js.LICENSE.txt": "package/bundles/redoc.standalone.js.LICENSE.txt",
    }

    def __init__(self, fetcher: Fetcher | None = None) -> None:
        self._fetcher = fetcher or UrlLibFetcher()

    def acquire(self, static_root: Path) -> Path:
        """Download, cross-check and atomically install the pinned ReDoc files."""
        static_root = static_root.resolve()
        metadata = self._load_metadata()
        tarball = self._fetcher.get(NPM_TARBALL_URL, MAX_TARBALL_BYTES)
        self._verify_npm_integrity(tarball, metadata["dist"]["integrity"])
        files = self._extract_tarball(tarball)
        self._verify_bundle_identity(files["redoc.standalone.js"])
        self._verify_license(files["LICENSE"])
        self._verify_notice(files["redoc.standalone.js.LICENSE.txt"])

        jsdelivr = self._fetcher.get(JSDELIVR_BUNDLE_URL, MAX_BUNDLE_BYTES)
        nelmio = self._fetcher.get(NELMIO_BUNDLE_URL, MAX_BUNDLE_BYTES)
        if files["redoc.standalone.js"] != jsdelivr or files["redoc.standalone.js"] != nelmio:
            raise VendorAcquisitionError(
                "REDOC_VENDOR_DISTRIBUTION_MISMATCH",
                "ReDoc bundle differs between npm, jsDelivr and the pinned Nelmio copy",
            )

        manifest = self._manifest(files, metadata)
        target = static_root / "assets" / "vendor" / "redoc" / REDOC_VERSION
        target.parent.mkdir(parents=True, exist_ok=True)
        self._promote(target, files, manifest)
        return target

    def _load_metadata(self) -> Mapping[str, object]:
        raw = self._fetcher.get(NPM_METADATA_URL, MAX_METADATA_BYTES)
        try:
            document = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise VendorAcquisitionError("REDOC_VENDOR_METADATA_INVALID", "npm metadata is not valid UTF-8 JSON") from exc
        if not isinstance(document, dict):
            raise VendorAcquisitionError("REDOC_VENDOR_METADATA_INVALID", "npm metadata must be an object")
        dist = document.get("dist")
        if (
            document.get("name") != "redoc"
            or document.get("version") != REDOC_VERSION
            or document.get("license") != "MIT"
            or not isinstance(document.get("gitHead"), str)
            or not document["gitHead"].startswith(REDOC_COMMIT)
            or not isinstance(dist, dict)
            or dist.get("tarball") != NPM_TARBALL_URL
            or not isinstance(dist.get("integrity"), str)
        ):
            raise VendorAcquisitionError("REDOC_VENDOR_METADATA_INVALID", "npm metadata identity/provenance fields are invalid")
        return document

    @staticmethod
    def _verify_npm_integrity(tarball: bytes, integrity: str) -> None:
        prefix = "sha512-"
        if not integrity.startswith(prefix):
            raise VendorAcquisitionError("REDOC_VENDOR_NPM_INTEGRITY_INVALID", "npm integrity must use SHA-512")
        try:
            expected = base64.b64decode(integrity[len(prefix) :], validate=True)
        except (ValueError, base64.binascii.Error) as exc:
            raise VendorAcquisitionError("REDOC_VENDOR_NPM_INTEGRITY_INVALID", "npm integrity is malformed") from exc
        actual = hashlib.sha512(tarball).digest()
        if actual != expected:
            raise VendorAcquisitionError("REDOC_VENDOR_NPM_INTEGRITY_MISMATCH", "npm tarball SHA-512 does not match registry metadata")

    def _extract_tarball(self, tarball: bytes) -> dict[str, bytes]:
        try:
            archive = tarfile.open(fileobj=io.BytesIO(tarball), mode="r:gz")
        except (tarfile.TarError, OSError) as exc:
            raise VendorAcquisitionError("REDOC_VENDOR_TARBALL_INVALID", "npm ReDoc tarball is invalid") from exc
        extracted: dict[str, bytes] = {}
        with archive:
            by_name = {member.name: member for member in archive.getmembers()}
            for output_name, member_name in self._members.items():
                member = by_name.get(member_name)
                if member is None or not member.isfile() or member.issym() or member.islnk():
                    raise VendorAcquisitionError("REDOC_VENDOR_TARBALL_INVALID", f"required regular tar member missing: {member_name}")
                maximum = MAX_BUNDLE_BYTES if output_name.endswith(".js") else MAX_TEXT_BYTES
                if member.size <= 0 or member.size > maximum:
                    raise VendorAcquisitionError("REDOC_VENDOR_TARBALL_INVALID", f"tar member has invalid size: {member_name}")
                stream = archive.extractfile(member)
                if stream is None:
                    raise VendorAcquisitionError("REDOC_VENDOR_TARBALL_INVALID", f"tar member cannot be read: {member_name}")
                body = stream.read(maximum + 1)
                if len(body) != member.size:
                    raise VendorAcquisitionError("REDOC_VENDOR_TARBALL_INVALID", f"tar member is truncated: {member_name}")
                extracted[output_name] = body
        return extracted

    @staticmethod
    def _verify_bundle_identity(bundle: bytes) -> None:
        if len(bundle) != REDOC_BUNDLE_SIZE:
            raise VendorAcquisitionError("REDOC_VENDOR_BUNDLE_INVALID", f"ReDoc bundle must be exactly {REDOC_BUNDLE_SIZE} bytes")
        marker = bundle.decode("utf-8", errors="replace")
        if f'" ReDoc Version: ","{REDOC_VERSION}"' not in marker or f'" Commit: ","{REDOC_COMMIT}"' not in marker:
            raise VendorAcquisitionError("REDOC_VENDOR_BUNDLE_INVALID", "ReDoc bundle version/commit markers are invalid")

    @staticmethod
    def _verify_license(license_bytes: bytes) -> None:
        text = license_bytes.decode("utf-8", errors="replace")
        if len(license_bytes) < 128 or "MIT License" not in text or "Permission is hereby granted" not in text:
            raise VendorAcquisitionError("REDOC_VENDOR_LICENSE_INVALID", "ReDoc MIT license is missing or invalid")

    @staticmethod
    def _verify_notice(notice: bytes) -> None:
        if len(notice) < 128:
            raise VendorAcquisitionError("REDOC_VENDOR_NOTICE_INVALID", "ReDoc bundled license notice is unexpectedly small")

    @staticmethod
    def _manifest(files: Mapping[str, bytes], metadata: Mapping[str, object]) -> dict[str, object]:
        dist = metadata["dist"]
        assert isinstance(dist, dict)
        return {
            "schema": "infranexum.vendor.redoc/v1",
            "component": "redoc",
            "version": REDOC_VERSION,
            "upstreamCommit": REDOC_COMMIT,
            "source": {
                "npmPackage": "redoc",
                "npmVersion": REDOC_VERSION,
                "npmTarball": NPM_TARBALL_URL,
                "npmIntegrity": dist["integrity"],
                "jsDelivrBundle": JSDELIVR_BUNDLE_URL,
                "nelmioBundle": NELMIO_BUNDLE_URL,
                "nelmioCommit": NELMIO_COMMIT,
                "runtimeNetworkRequired": False,
            },
            "files": [
                {
                    "path": name,
                    "size": len(body),
                    "sha256": hashlib.sha256(body).hexdigest(),
                }
                for name, body in files.items()
            ],
        }

    @staticmethod
    def _promote(target: Path, files: Mapping[str, bytes], manifest: Mapping[str, object]) -> None:
        parent = target.parent
        staging = Path(tempfile.mkdtemp(prefix=f".{target.name}.staging-", dir=parent))
        backup = parent / f".{target.name}.backup"
        try:
            for name, body in files.items():
                destination = staging / name
                destination.write_bytes(body)
                with destination.open("rb") as stream:
                    os.fsync(stream.fileno())
            manifest_path = staging / "manifest.json"
            manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
            with manifest_path.open("rb") as stream:
                os.fsync(stream.fileno())

            if backup.exists():
                shutil.rmtree(backup)
            if target.exists():
                target.rename(backup)
            try:
                staging.rename(target)
            except OSError:
                if backup.exists() and not target.exists():
                    backup.rename(target)
                raise
            if backup.exists():
                shutil.rmtree(backup)
        except OSError as exc:
            raise VendorAcquisitionError("REDOC_VENDOR_PROMOTION_FAILED", f"unable to promote ReDoc vendor tree: {exc}") from exc
        finally:
            if staging.exists():
                shutil.rmtree(staging, ignore_errors=True)


def _parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--static-root", required=True, type=Path, help="InfraNexum Web public directory")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    """CLI entry point used by maintainers and CI in a network-enabled build stage."""
    args = _parse_args(argv)
    try:
        target = RedocVendorAcquirer().acquire(args.static_root)
    except VendorAcquisitionError as exc:
        print(json.dumps({"status": "ERROR", "code": exc.code, "message": str(exc)}))
        return 1
    print(json.dumps({"status": "PASS", "target": str(target)}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
