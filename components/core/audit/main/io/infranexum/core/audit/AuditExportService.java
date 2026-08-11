package io.infranexum.core.audit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Creates deterministic SHA-256/Ed25519 audit export packages from an immutable snapshot. */
public final class AuditExportService {
    public static final String FORMAT_VERSION = "infranexum.audit-export/v1";
    private final AuditJournal journal;

    public AuditExportService(AuditJournal journal) {
        this.journal = Objects.requireNonNull(journal, "journal");
    }

    /** Exports a bounded contiguous sequence interval. */
    public SignedAuditExport export(
            AuditScope scope,
            long fromSequenceInclusive,
            long toSequenceInclusive,
            int limit,
            String keyId,
            PrivateKey signingKey) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(signingKey, "signingKey");
        if (!"EdDSA".equalsIgnoreCase(signingKey.getAlgorithm()) && !"Ed25519".equalsIgnoreCase(signingKey.getAlgorithm())) {
            throw new IllegalArgumentException("audit export signing key must be Ed25519");
        }
        List<AuditRecord> records = journal.readRange(scope, fromSequenceInclusive, toSequenceInclusive, limit);
        if (records.isEmpty()) throw new IllegalArgumentException("audit export range contains no entries");
        if (records.get(0).sequence() != fromSequenceInclusive
                || records.get(records.size() - 1).sequence() != toSequenceInclusive
                || records.size() != toSequenceInclusive - fromSequenceInclusive + 1) {
            throw new IllegalStateException("audit export range is not contiguous or exceeded its bound");
        }
        verifySlice(records);

        byte[] payload = payload(records);
        AuditRecord first = records.get(0);
        AuditRecord last = records.get(records.size() - 1);
        AuditExportManifest manifest = new AuditExportManifest(
                FORMAT_VERSION,
                keyId,
                scope,
                first.sequence(),
                last.sequence(),
                records.size(),
                latestTimestamp(records),
                AuditCanonicalizer.sha256(payload),
                first.previousHash(),
                last.entryHash());
        byte[] manifestBytes = manifest.canonicalText().getBytes(StandardCharsets.UTF_8);
        byte[] signature = sign(manifestBytes, signingKey);
        return new SignedAuditExport(manifest, payload, signature, archive(payload, manifestBytes, signature));
    }

    private static void verifySlice(List<AuditRecord> records) {
        String previous = records.get(0).previousHash();
        long expectedSequence = records.get(0).sequence();
        for (AuditRecord record : records) {
            if (record.sequence() != expectedSequence || !record.previousHash().equals(previous)) {
                throw new IllegalStateException("audit export slice contains a chain discontinuity");
            }
            String expectedHash = AuditCanonicalizer.hash(record.sequence(), previous, record.entry());
            if (!record.entryHash().equals(expectedHash)) throw new IllegalStateException("audit export slice failed integrity verification");
            previous = record.entryHash();
            expectedSequence++;
        }
    }

    private static byte[] payload(List<AuditRecord> records) {
        StringBuilder out = new StringBuilder(records.size() * 1024);
        for (AuditRecord record : records) {
            out.append('{')
                    .append("\"sequence\":").append(record.sequence()).append(',')
                    .append("\"previousHash\":\"").append(record.previousHash()).append("\",")
                    .append("\"entryHash\":\"").append(record.entryHash()).append("\",")
                    .append("\"entry\":").append(AuditCanonicalizer.canonicalEntry(record.entry()))
                    .append("}\n");
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static Instant latestTimestamp(List<AuditRecord> records) {
        return records.stream().map(record -> record.entry().timestamp()).max(Instant::compareTo).orElseThrow();
    }

    private static byte[] sign(byte[] manifest, PrivateKey key) {
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(key);
            signer.update(manifest);
            return signer.sign();
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("unable to sign audit export", error);
        }
    }

    private static byte[] archive(byte[] payload, byte[] manifest, byte[] signature) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
                stored(zip, "audit.jsonl", payload);
                stored(zip, "manifest.properties", manifest);
                stored(zip, "signature.ed25519.b64", Base64.getEncoder().encode(signature));
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("unable to create in-memory audit export", impossible);
        }
    }

    private static void stored(ZipOutputStream zip, String name, byte[] content) throws IOException {
        CRC32 crc = new CRC32();
        crc.update(content);
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(content.length);
        entry.setCompressedSize(content.length);
        entry.setCrc(crc.getValue());
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(content);
        zip.closeEntry();
    }
}
