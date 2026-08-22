"""Regression and security tests for the ReDoc vendor acquisition boundary."""

from __future__ import annotations

import base64
import hashlib
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest import mock

from tools import vendor_redoc


class FakeFetcher:
    def __init__(self, responses: dict[str, bytes]) -> None:
        self.responses = responses
        self.calls: list[tuple[str, int]] = []

    def get(self, url: str, maximum_bytes: int) -> bytes:
        self.calls.append((url, maximum_bytes))
        try:
            body = self.responses[url]
        except KeyError as exc:
            raise vendor_redoc.VendorAcquisitionError("TEST_MISSING_RESPONSE", url) from exc
        if len(body) > maximum_bytes:
            raise vendor_redoc.VendorAcquisitionError("REDOC_VENDOR_RESPONSE_TOO_LARGE", url)
        return body


def bundle(version: str = vendor_redoc.REDOC_VERSION, commit: str = vendor_redoc.REDOC_COMMIT) -> bytes:
    prefix = f'/*! fixture */\nconst marker = [" ReDoc Version: ","{version}"," Commit: ","{commit}"];\nwindow.Redoc = {{}};\n'.encode()
    body = bytearray(b" " * vendor_redoc.REDOC_BUNDLE_SIZE)
    body[: len(prefix)] = prefix
    return bytes(body)


def license_bytes(valid: bool = True) -> bytes:
    if valid:
        return b"MIT License\n\nPermission is hereby granted, free of charge, to any person obtaining a copy.\n" * 3
    return b"Proprietary license terms. " * 12


def notice_bytes(size: int = 256) -> bytes:
    return (b"ReDoc third-party license notices.\n" * 16)[:size]


def tarball(files: dict[str, bytes] | None = None, symlink_member: str | None = None) -> bytes:
    payloads = files or {
        "package/bundles/redoc.standalone.js": bundle(),
        "package/LICENSE": license_bytes(),
        "package/bundles/redoc.standalone.js.LICENSE.txt": notice_bytes(),
    }
    stream = io.BytesIO()
    with tarfile.open(fileobj=stream, mode="w:gz") as archive:
        for name, body in payloads.items():
            info = tarfile.TarInfo(name)
            if name == symlink_member:
                info.type = tarfile.SYMTYPE
                info.linkname = "elsewhere"
                info.size = 0
                archive.addfile(info)
            else:
                info.size = len(body)
                archive.addfile(info, io.BytesIO(body))
    return stream.getvalue()


def metadata(tar: bytes, **overrides: object) -> bytes:
    document: dict[str, object] = {
        "name": "redoc",
        "version": vendor_redoc.REDOC_VERSION,
        "license": "MIT",
        "gitHead": vendor_redoc.REDOC_COMMIT + "000000000000000000000000000000000",
        "dist": {
            "tarball": vendor_redoc.NPM_TARBALL_URL,
            "integrity": "sha512-" + base64.b64encode(hashlib.sha512(tar).digest()).decode(),
        },
    }
    document.update(overrides)
    return json.dumps(document).encode()


def responses(*, npm_bundle: bytes | None = None, jsdelivr: bytes | None = None, nelmio: bytes | None = None,
              license_body: bytes | None = None, notice_body: bytes | None = None) -> dict[str, bytes]:
    npm = npm_bundle or bundle()
    tar = tarball({
        "package/bundles/redoc.standalone.js": npm,
        "package/LICENSE": license_body or license_bytes(),
        "package/bundles/redoc.standalone.js.LICENSE.txt": notice_body or notice_bytes(),
    })
    return {
        vendor_redoc.NPM_METADATA_URL: metadata(tar),
        vendor_redoc.NPM_TARBALL_URL: tar,
        vendor_redoc.JSDELIVR_BUNDLE_URL: jsdelivr or npm,
        vendor_redoc.NELMIO_BUNDLE_URL: nelmio or npm,
    }


class RedocVendorAcquirerTests(unittest.TestCase):
    def test_nominal_acquisition_cross_checks_sources_and_writes_manifest(self) -> None:
        fetcher = FakeFetcher(responses())
        with tempfile.TemporaryDirectory() as directory:
            target = vendor_redoc.RedocVendorAcquirer(fetcher).acquire(Path(directory))
            self.assertEqual(target.name, vendor_redoc.REDOC_VERSION)
            self.assertEqual((target / "redoc.standalone.js").stat().st_size, vendor_redoc.REDOC_BUNDLE_SIZE)
            manifest = json.loads((target / "manifest.json").read_text())
            self.assertEqual(manifest["schema"], "infranexum.vendor.redoc/v1")
            self.assertFalse(manifest["source"]["runtimeNetworkRequired"])
            self.assertEqual(len(manifest["files"]), 3)
            self.assertEqual(
                [call[0] for call in fetcher.calls],
                [
                    vendor_redoc.NPM_METADATA_URL,
                    vendor_redoc.NPM_TARBALL_URL,
                    vendor_redoc.JSDELIVR_BUNDLE_URL,
                    vendor_redoc.NELMIO_BUNDLE_URL,
                ],
            )

    def test_existing_target_is_replaced_atomically(self) -> None:
        fetcher = FakeFetcher(responses())
        with tempfile.TemporaryDirectory() as directory:
            static = Path(directory)
            target = static / "assets/vendor/redoc" / vendor_redoc.REDOC_VERSION
            target.mkdir(parents=True)
            (target / "old.txt").write_text("old")
            result = vendor_redoc.RedocVendorAcquirer(fetcher).acquire(static)
            self.assertEqual(result, target)
            self.assertFalse((target / "old.txt").exists())

    def test_metadata_must_be_utf8_json_object_with_exact_identity(self) -> None:
        tar = tarball()
        base = responses()
        cases = [
            b"\xff",
            b"[]",
            metadata(tar, name="not-redoc"),
            metadata(tar, version="2.5.2"),
            metadata(tar, license="GPL"),
            metadata(tar, gitHead="deadbeef"),
            metadata(tar, dist={"tarball": "https://example.invalid/redoc.tgz", "integrity": "sha512-x"}),
            metadata(tar, dist={"tarball": vendor_redoc.NPM_TARBALL_URL, "integrity": 42}),
        ]
        for body in cases:
            with self.subTest(body=body[:40]):
                mapping = dict(base)
                mapping[vendor_redoc.NPM_METADATA_URL] = body
                with tempfile.TemporaryDirectory() as directory:
                    with self.assertRaisesRegex(vendor_redoc.VendorAcquisitionError, "metadata") as captured:
                        vendor_redoc.RedocVendorAcquirer(FakeFetcher(mapping)).acquire(Path(directory))
                    self.assertEqual(captured.exception.code, "REDOC_VENDOR_METADATA_INVALID")

    def test_npm_integrity_requires_sha512_valid_base64_and_matching_bytes(self) -> None:
        tar = tarball()
        with self.assertRaises(vendor_redoc.VendorAcquisitionError) as captured:
            vendor_redoc.RedocVendorAcquirer._verify_npm_integrity(tar, "sha256-abc")
        self.assertEqual(captured.exception.code, "REDOC_VENDOR_NPM_INTEGRITY_INVALID")
        with self.assertRaises(vendor_redoc.VendorAcquisitionError) as captured:
            vendor_redoc.RedocVendorAcquirer._verify_npm_integrity(tar, "sha512-@@@")
        self.assertEqual(captured.exception.code, "REDOC_VENDOR_NPM_INTEGRITY_INVALID")
        wrong = "sha512-" + base64.b64encode(b"0" * 64).decode()
        with self.assertRaises(vendor_redoc.VendorAcquisitionError) as captured:
            vendor_redoc.RedocVendorAcquirer._verify_npm_integrity(tar, wrong)
        self.assertEqual(captured.exception.code, "REDOC_VENDOR_NPM_INTEGRITY_MISMATCH")

    def test_invalid_tarball_and_missing_or_symlinked_members_are_rejected(self) -> None:
        acquirer = vendor_redoc.RedocVendorAcquirer(FakeFetcher({}))
        with self.assertRaises(vendor_redoc.VendorAcquisitionError) as captured:
            acquirer._extract_tarball(b"not-gzip")
        self.assertEqual(captured.exception.code, "REDOC_VENDOR_TARBALL_INVALID")

        missing = tarball({"package/LICENSE": license_bytes()})
        with self.assertRaises(vendor_redoc.VendorAcquisitionError) as captured:
            acquirer._extract_tarball(missing)
        self.assertEqual(captured.exception.code, "REDOC_VENDOR_TARBALL_INVALID")

        linked = tarball(symlink_member="package/bundles/redoc.standalone.js")
        with self.assertRaises(vendor_redoc.VendorAcquisitionError) as captured:
            acquirer._extract_tarball(linked)
        self.assertEqual(captured.exception.code, "REDOC_VENDOR_TARBALL_INVALID")

    def test_bundle_identity_license_and_notice_are_fail_closed(self) -> None:
        with self.assertRaises(vendor_redoc.VendorAcquisitionError) as captured:
            vendor_redoc.RedocVendorAcquirer._verify_bundle_identity(b"stub")
        self.assertEqual(captured.exception.code, "REDOC_VENDOR_BUNDLE_INVALID")
        with self.assertRaises(vendor_redoc.VendorAcquisitionError) as captured:
            vendor_redoc.RedocVendorAcquirer._verify_bundle_identity(bundle(version="2.5.2", commit="deadbee"))
        self.assertEqual(captured.exception.code, "REDOC_VENDOR_BUNDLE_INVALID")
        with self.assertRaises(vendor_redoc.VendorAcquisitionError) as captured:
            vendor_redoc.RedocVendorAcquirer._verify_license(license_bytes(False))
        self.assertEqual(captured.exception.code, "REDOC_VENDOR_LICENSE_INVALID")
        with self.assertRaises(vendor_redoc.VendorAcquisitionError) as captured:
            vendor_redoc.RedocVendorAcquirer._verify_notice(b"short")
        self.assertEqual(captured.exception.code, "REDOC_VENDOR_NOTICE_INVALID")

    def test_independent_bundle_distributions_must_be_byte_identical(self) -> None:
        mapping = responses(jsdelivr=bundle(commit="deadbee"))
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(vendor_redoc.VendorAcquisitionError) as captured:
                vendor_redoc.RedocVendorAcquirer(FakeFetcher(mapping)).acquire(Path(directory))
            self.assertEqual(captured.exception.code, "REDOC_VENDOR_DISTRIBUTION_MISMATCH")
        mapping = responses(nelmio=bundle(commit="deadbee"))
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(vendor_redoc.VendorAcquisitionError) as captured:
                vendor_redoc.RedocVendorAcquirer(FakeFetcher(mapping)).acquire(Path(directory))
            self.assertEqual(captured.exception.code, "REDOC_VENDOR_DISTRIBUTION_MISMATCH")

    def test_url_fetcher_rejects_non_https_and_unapproved_hosts_before_network(self) -> None:
        fetcher = vendor_redoc.UrlLibFetcher()
        for url in ["http://registry.npmjs.org/redoc", "https://example.invalid/redoc"]:
            with self.subTest(url=url), self.assertRaises(vendor_redoc.VendorAcquisitionError) as captured:
                fetcher.get(url, 10)
            self.assertEqual(captured.exception.code, "REDOC_VENDOR_URL_REJECTED")


    def test_url_fetcher_accepts_bounded_body_and_rejects_redirect_or_size_overflow(self) -> None:
        class Response:
            def __init__(self, body: bytes, final_url: str, content_length: str | None = None) -> None:
                self.body = body
                self.final_url = final_url
                self.headers = {} if content_length is None else {"Content-Length": content_length}
            def __enter__(self):
                return self
            def __exit__(self, *_args):
                return False
            def geturl(self) -> str:
                return self.final_url
            def read(self, maximum: int) -> bytes:
                return self.body[:maximum]

        fetcher = vendor_redoc.UrlLibFetcher(timeout_seconds=1)
        with mock.patch.object(vendor_redoc, "urlopen", return_value=Response(b"ok", vendor_redoc.NPM_METADATA_URL, "2")):
            self.assertEqual(fetcher.get(vendor_redoc.NPM_METADATA_URL, 10), b"ok")
        with mock.patch.object(vendor_redoc, "urlopen", return_value=Response(b"ok", "https://example.invalid/redoc")):
            with self.assertRaises(vendor_redoc.VendorAcquisitionError) as captured:
                fetcher.get(vendor_redoc.NPM_METADATA_URL, 10)
            self.assertEqual(captured.exception.code, "REDOC_VENDOR_REDIRECT_REJECTED")
        with mock.patch.object(vendor_redoc, "urlopen", return_value=Response(b"x" * 11, vendor_redoc.NPM_METADATA_URL, "11")):
            with self.assertRaises(vendor_redoc.VendorAcquisitionError) as captured:
                fetcher.get(vendor_redoc.NPM_METADATA_URL, 10)
            self.assertEqual(captured.exception.code, "REDOC_VENDOR_RESPONSE_TOO_LARGE")
        with mock.patch.object(vendor_redoc, "urlopen", return_value=Response(b"x" * 11, vendor_redoc.NPM_METADATA_URL)):
            with self.assertRaises(vendor_redoc.VendorAcquisitionError) as captured:
                fetcher.get(vendor_redoc.NPM_METADATA_URL, 10)
            self.assertEqual(captured.exception.code, "REDOC_VENDOR_RESPONSE_TOO_LARGE")

    def test_tar_member_size_unreadable_and_truncation_paths_are_rejected(self) -> None:
        acquirer = vendor_redoc.RedocVendorAcquirer(FakeFetcher({}))
        empty_bundle = tarball({
            "package/bundles/redoc.standalone.js": b"",
            "package/LICENSE": license_bytes(),
            "package/bundles/redoc.standalone.js.LICENSE.txt": notice_bytes(),
        })
        with self.assertRaises(vendor_redoc.VendorAcquisitionError) as captured:
            acquirer._extract_tarball(empty_bundle)
        self.assertEqual(captured.exception.code, "REDOC_VENDOR_TARBALL_INVALID")

        class Member:
            name = "package/bundles/redoc.standalone.js"
            size = 10
            def isfile(self): return True
            def issym(self): return False
            def islnk(self): return False

        class Archive:
            def __init__(self, stream): self.stream = stream
            def __enter__(self): return self
            def __exit__(self, *_args): return False
            def getmembers(self): return [Member()]
            def extractfile(self, _member): return self.stream

        with mock.patch.object(vendor_redoc.tarfile, "open", return_value=Archive(None)):
            with self.assertRaises(vendor_redoc.VendorAcquisitionError) as captured:
                acquirer._extract_tarball(b"placeholder")
            self.assertEqual(captured.exception.code, "REDOC_VENDOR_TARBALL_INVALID")
        with mock.patch.object(vendor_redoc.tarfile, "open", return_value=Archive(io.BytesIO(b"short"))):
            with self.assertRaises(vendor_redoc.VendorAcquisitionError) as captured:
                acquirer._extract_tarball(b"placeholder")
            self.assertEqual(captured.exception.code, "REDOC_VENDOR_TARBALL_INVALID")

    def test_promotion_cleans_stale_backup_and_handles_failure_without_previous_target(self) -> None:
        files = {
            "redoc.standalone.js": bundle(),
            "LICENSE": license_bytes(),
            "redoc.standalone.js.LICENSE.txt": notice_bytes(),
        }
        with tempfile.TemporaryDirectory() as directory:
            parent = Path(directory)
            target = parent / "2.5.3"
            backup = parent / ".2.5.3.backup"
            backup.mkdir()
            (backup / "stale").write_text("stale")
            vendor_redoc.RedocVendorAcquirer._promote(target, files, {"schema": "test"})
            self.assertTrue(target.is_dir())
            self.assertFalse(backup.exists())

        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "2.5.3"
            original_rename = Path.rename
            def fail_staging(path_obj: Path, target_obj: Path):
                if path_obj.name.startswith(".2.5.3.staging-"):
                    raise OSError("promotion failed")
                return original_rename(path_obj, target_obj)
            with mock.patch.object(Path, "rename", autospec=True, side_effect=fail_staging):
                with self.assertRaises(vendor_redoc.VendorAcquisitionError) as captured:
                    vendor_redoc.RedocVendorAcquirer._promote(target, files, {"schema": "test"})
            self.assertEqual(captured.exception.code, "REDOC_VENDOR_PROMOTION_FAILED")
            self.assertFalse(target.exists())

    def test_url_fetcher_translates_transport_errors(self) -> None:
        fetcher = vendor_redoc.UrlLibFetcher()
        with mock.patch.object(vendor_redoc, "urlopen", side_effect=OSError("network down")):
            with self.assertRaises(vendor_redoc.VendorAcquisitionError) as captured:
                fetcher.get(vendor_redoc.NPM_METADATA_URL, 10)
        self.assertEqual(captured.exception.code, "REDOC_VENDOR_DOWNLOAD_FAILED")

    def test_promotion_failure_rolls_back_previous_tree(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            parent = Path(directory)
            target = parent / "2.5.3"
            target.mkdir()
            (target / "sentinel").write_text("old")
            files = {
                "redoc.standalone.js": bundle(),
                "LICENSE": license_bytes(),
                "redoc.standalone.js.LICENSE.txt": notice_bytes(),
            }
            manifest = {"schema": "test"}
            original_rename = Path.rename

            def failing_rename(path_obj: Path, target_obj: Path):
                if path_obj.name.startswith(".2.5.3.staging-"):
                    raise OSError("promotion failed")
                return original_rename(path_obj, target_obj)

            with mock.patch.object(Path, "rename", autospec=True, side_effect=failing_rename):
                with self.assertRaises(vendor_redoc.VendorAcquisitionError) as captured:
                    vendor_redoc.RedocVendorAcquirer._promote(target, files, manifest)
            self.assertEqual(captured.exception.code, "REDOC_VENDOR_PROMOTION_FAILED")
            self.assertEqual((target / "sentinel").read_text(), "old")

    def test_cli_reports_success_and_failure_as_stable_json(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "assets/vendor/redoc/2.5.3"
            with mock.patch.object(vendor_redoc.RedocVendorAcquirer, "acquire", return_value=target), \
                    mock.patch("builtins.print") as printer:
                self.assertEqual(vendor_redoc.main(["--static-root", directory]), 0)
                self.assertIn('"status": "PASS"', printer.call_args.args[0])
            failure = vendor_redoc.VendorAcquisitionError("REDOC_VENDOR_DOWNLOAD_FAILED", "offline")
            with mock.patch.object(vendor_redoc.RedocVendorAcquirer, "acquire", side_effect=failure), \
                    mock.patch("builtins.print") as printer:
                self.assertEqual(vendor_redoc.main(["--static-root", directory]), 1)
                self.assertIn("REDOC_VENDOR_DOWNLOAD_FAILED", printer.call_args.args[0])


if __name__ == "__main__":
    unittest.main()
