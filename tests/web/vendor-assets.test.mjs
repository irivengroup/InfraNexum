import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtemp, readFile, rm, symlink, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

import {
  REDOC_VENDOR_BUNDLE_SIZE,
  REDOC_VENDOR_RELATIVE_DIRECTORY,
  VendorAssetIntegrityVerifier,
} from '../../src/applications/web/runtime/vendor-assets.mjs';
import { writeSyntheticRedocVendor } from './vendor-fixture.mjs';

async function fixture() {
  const root = await mkdtemp(path.join(os.tmpdir(), 'infranexum-redoc-vendor-'));
  await writeFile(path.join(root, 'index.html'), '<!doctype html>');
  const vendor = await writeSyntheticRedocVendor(root);
  return { root, ...vendor };
}

async function manifest(directory) {
  return JSON.parse(await readFile(path.join(directory, 'manifest.json'), 'utf8'));
}

async function writeManifest(directory, document) {
  await writeFile(path.join(directory, 'manifest.json'), `${JSON.stringify(document, null, 2)}\n`, 'utf8');
}

async function syncEntry(directory, filename, bytes) {
  const document = await manifest(directory);
  const entry = document.files.find((candidate) => candidate.path === filename);
  entry.size = bytes.length;
  entry.sha256 = createHash('sha256').update(bytes).digest('hex');
  await writeManifest(directory, document);
}

async function rejectsCode(action, code) {
  await assert.rejects(action, (error) => {
    assert.equal(error.code, code);
    return true;
  });
}

test('vendor verifier accepts the complete deterministic ReDoc tree', async (context) => {
  const { root } = await fixture();
  context.after(() => rm(root, { recursive: true, force: true }));
  const result = await new VendorAssetIntegrityVerifier(root).verify();
  assert.equal(result.component, 'redoc');
  assert.equal(result.version, '2.5.3');
  assert.equal(result.upstreamCommit, '1b2591e');
  assert.ok(result.directory.endsWith(path.join('vendor', 'redoc', '2.5.3')));
});

test('vendor verifier rejects invalid constructor input and a missing vendor directory', async (context) => {
  assert.throws(() => new VendorAssetIntegrityVerifier(''), /staticRoot/);
  const root = await mkdtemp(path.join(os.tmpdir(), 'infranexum-redoc-missing-'));
  context.after(() => rm(root, { recursive: true, force: true }));
  await rejectsCode(() => new VendorAssetIntegrityVerifier(root).verify(), 'WEB_VENDOR_REDOC_DIRECTORY_INVALID');
});

test('vendor verifier rejects a symlinked vendor directory', async (context) => {
  const root = await mkdtemp(path.join(os.tmpdir(), 'infranexum-redoc-symlink-root-'));
  const target = await mkdtemp(path.join(os.tmpdir(), 'infranexum-redoc-symlink-target-'));
  context.after(() => Promise.all([
    rm(root, { recursive: true, force: true }),
    rm(target, { recursive: true, force: true }),
  ]));
  await writeSyntheticRedocVendor(target);
  const link = path.join(root, REDOC_VENDOR_RELATIVE_DIRECTORY);
  await import('node:fs/promises').then(({ mkdir }) => mkdir(path.dirname(link), { recursive: true }));
  await symlink(path.join(target, REDOC_VENDOR_RELATIVE_DIRECTORY), link, 'dir');
  await rejectsCode(() => new VendorAssetIntegrityVerifier(root).verify(), 'WEB_VENDOR_REDOC_DIRECTORY_INVALID');
});

test('vendor verifier rejects malformed and structurally invalid manifests', async (context) => {
  const { root, directory } = await fixture();
  context.after(() => rm(root, { recursive: true, force: true }));

  await writeFile(path.join(directory, 'manifest.json'), '{not-json', 'utf8');
  await rejectsCode(() => new VendorAssetIntegrityVerifier(root).verify(), 'WEB_VENDOR_REDOC_MANIFEST_INVALID');

  await writeFile(path.join(directory, 'manifest.json'), '[]\n', 'utf8');
  await rejectsCode(() => new VendorAssetIntegrityVerifier(root).verify(), 'WEB_VENDOR_REDOC_MANIFEST_INVALID');

  await writeSyntheticRedocVendor(root);
  const document = await manifest(directory);
  document.version = '2.5.2';
  await writeManifest(directory, document);
  await rejectsCode(() => new VendorAssetIntegrityVerifier(root).verify(), 'WEB_VENDOR_REDOC_MANIFEST_INVALID');
});

test('vendor verifier rejects unknown, duplicate and malformed manifest entries', async (context) => {
  const { root, directory } = await fixture();
  context.after(() => rm(root, { recursive: true, force: true }));

  for (const mutate of [
    (document) => { document.files.pop(); },
    (document) => { document.files[2] = { ...document.files[0] }; },
    (document) => { document.files[0].path = 'unexpected.js'; },
    (document) => { document.files[0].size = 0; },
    (document) => { document.files[0].sha256 = 'not-a-sha'; },
  ]) {
    await writeSyntheticRedocVendor(root);
    const document = await manifest(directory);
    mutate(document);
    await writeManifest(directory, document);
    await rejectsCode(() => new VendorAssetIntegrityVerifier(root).verify(), 'WEB_VENDOR_REDOC_MANIFEST_INVALID');
  }
});

test('vendor verifier rejects missing files and symlink substitutions', async (context) => {
  const { root, directory } = await fixture();
  context.after(() => rm(root, { recursive: true, force: true }));
  const notice = path.join(directory, 'redoc.standalone.js.LICENSE.txt');
  await rm(notice);
  await rejectsCode(() => new VendorAssetIntegrityVerifier(root).verify(), 'WEB_VENDOR_REDOC_FILE_INVALID');

  await writeSyntheticRedocVendor(root);
  const license = path.join(directory, 'LICENSE');
  const replacement = path.join(root, 'replacement-license');
  await writeFile(replacement, 'MIT License\nPermission is hereby granted\n'.repeat(8));
  await rm(license);
  await symlink(replacement, license);
  await rejectsCode(() => new VendorAssetIntegrityVerifier(root).verify(), 'WEB_VENDOR_REDOC_FILE_INVALID');
});


test('vendor verifier rejects manifest size claims that differ from the file bytes', async (context) => {
  const { root, directory } = await fixture();
  context.after(() => rm(root, { recursive: true, force: true }));
  const document = await manifest(directory);
  const licenseEntry = document.files.find((entry) => entry.path === 'LICENSE');
  licenseEntry.size += 1;
  await writeManifest(directory, document);
  await rejectsCode(() => new VendorAssetIntegrityVerifier(root).verify(), 'WEB_VENDOR_REDOC_SIZE_MISMATCH');
});

test('vendor verifier rejects vendor directories that escape through a symlinked ancestor', async (context) => {
  const root = await mkdtemp(path.join(os.tmpdir(), 'infranexum-redoc-ancestor-root-'));
  const outside = await mkdtemp(path.join(os.tmpdir(), 'infranexum-redoc-ancestor-outside-'));
  context.after(() => Promise.all([
    rm(root, { recursive: true, force: true }),
    rm(outside, { recursive: true, force: true }),
  ]));
  await writeSyntheticRedocVendor(outside);
  const assets = path.join(root, 'assets');
  await symlink(path.join(outside, 'assets'), assets, 'dir');
  await rejectsCode(() => new VendorAssetIntegrityVerifier(root).verify(), 'WEB_VENDOR_REDOC_DIRECTORY_INVALID');
});

test('vendor verifier rejects bundle size substitution even with a matching manifest hash', async (context) => {
  const { root, directory } = await fixture();
  context.after(() => rm(root, { recursive: true, force: true }));
  const bytes = Buffer.from('/*! fixture */\nconst marker = [" ReDoc Version: ","2.5.3"," Commit: ","1b2591e"];\nwindow.Redoc = {};\n');
  await writeFile(path.join(directory, 'redoc.standalone.js'), bytes);
  await syncEntry(directory, 'redoc.standalone.js', bytes);
  await rejectsCode(() => new VendorAssetIntegrityVerifier(root).verify(), 'WEB_VENDOR_REDOC_SIZE_MISMATCH');
});

test('vendor verifier rejects a SHA-256 mismatch without trusting the on-disk file', async (context) => {
  const { root, directory } = await fixture();
  context.after(() => rm(root, { recursive: true, force: true }));
  const bundlePath = path.join(directory, 'redoc.standalone.js');
  const bundle = await readFile(bundlePath);
  bundle[bundle.length - 1] = bundle[bundle.length - 1] === 0x20 ? 0x21 : 0x20;
  assert.equal(bundle.length, REDOC_VENDOR_BUNDLE_SIZE);
  await writeFile(bundlePath, bundle);
  await rejectsCode(() => new VendorAssetIntegrityVerifier(root).verify(), 'WEB_VENDOR_REDOC_SHA256_MISMATCH');
});

test('vendor verifier rejects bundle identity markers even when size and SHA-256 agree', async (context) => {
  const { root, directory } = await fixture();
  context.after(() => rm(root, { recursive: true, force: true }));
  const bundlePath = path.join(directory, 'redoc.standalone.js');
  const bundle = Buffer.alloc(REDOC_VENDOR_BUNDLE_SIZE, 0x20);
  Buffer.from('/*! fixture */\nconst marker = [" ReDoc Version: ","2.5.2"," Commit: ","deadbee"];\n').copy(bundle);
  await writeFile(bundlePath, bundle);
  await syncEntry(directory, 'redoc.standalone.js', bundle);
  await rejectsCode(() => new VendorAssetIntegrityVerifier(root).verify(), 'WEB_VENDOR_REDOC_IDENTITY_MISMATCH');
});

test('vendor verifier rejects invalid MIT license content and undersized notices', async (context) => {
  const { root, directory } = await fixture();
  context.after(() => rm(root, { recursive: true, force: true }));

  const invalidLicense = Buffer.from('Proprietary license. '.repeat(20));
  await writeFile(path.join(directory, 'LICENSE'), invalidLicense);
  await syncEntry(directory, 'LICENSE', invalidLicense);
  await rejectsCode(() => new VendorAssetIntegrityVerifier(root).verify(), 'WEB_VENDOR_REDOC_LICENSE_INVALID');

  await writeSyntheticRedocVendor(root);
  const shortNotice = Buffer.from('short notice');
  await writeFile(path.join(directory, 'redoc.standalone.js.LICENSE.txt'), shortNotice);
  await syncEntry(directory, 'redoc.standalone.js.LICENSE.txt', shortNotice);
  await rejectsCode(() => new VendorAssetIntegrityVerifier(root).verify(), 'WEB_VENDOR_REDOC_FILE_INVALID');
});
