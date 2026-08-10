package io.infranexum.core.contracts;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/** Dependency-free behavioral smoke test for offline development environments. */
public final class ContractSmoke {
    private ContractSmoke() {}

    public static void main(String[] args) {
        ContractVersion reader = ContractVersion.parse("1.2.0");
        require(reader.canRead(ContractVersion.parse("1.1.9")), "compatible version rejected");
        require(!reader.canRead(ContractVersion.parse("2.0.0")), "breaking major accepted");

        Clock clock = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC);
        UuidV7Generator generator = new UuidV7Generator(clock, new SecureRandom(new byte[] {9, 8, 7, 6}));
        DomainIdentifier first = generator.next();
        DomainIdentifier second = generator.next();
        require(first.value().version() == 7, "wrong UUID version");
        require(first.value().variant() == 2, "wrong UUID variant");
        require(first.compareTo(second) < 0, "UUIDv7 sequence is not monotonic");
        require(first.unixEpochMillis() == 1_700_000_000_000L, "timestamp extraction failed");
        DomainIdentifier known = DomainIdentifier.parse("018f22b2-7c00-7000-8000-000000000001");
        require(known.unixEpochMillis() == Instant.parse("2024-04-28T03:14:33.600Z").toEpochMilli(),
                "known UUIDv7 timestamp extraction regressed");

        DomainFailure failure = new DomainFailure(new DomainErrorCode("invalid_scope"), "Invalid scope", Map.of());
        require(failure.code().value().equals("INVALID_SCOPE"), "error code normalization failed");

        boolean rejected = false;
        try {
            new DomainIdentifier(UUID.randomUUID());
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        require(rejected, "non-v7 identifier accepted");
        System.out.println("java-contract-smoke: PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
