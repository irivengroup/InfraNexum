package io.infranexum.server.rsot;

import io.infranexum.rsot.domain.CanonicalObject;
import java.time.Instant;

/** HTTP projection models for read-only canonical RSOT object administration. */
final class RsotObjectApiModels {
    private RsotObjectApiModels() {}

    record CanonicalObjectResponse(
            String id,
            String objectType,
            long version,
            String organizationId,
            String schemaVersion,
            String status,
            String statusReason,
            Instant effectiveFrom,
            Instant effectiveUntil,
            Instant archivedAt,
            String archivedBy,
            Instant createdAt,
            Instant updatedAt) {
        static CanonicalObjectResponse from(CanonicalObject object) {
            var lifecycle = object.lifecycle();
            return new CanonicalObjectResponse(
                    object.id().toString(),
                    object.objectType(),
                    object.version(),
                    object.organizationId().toString(),
                    object.schemaVersion(),
                    lifecycle.status().name().toLowerCase(java.util.Locale.ROOT),
                    lifecycle.statusReason(),
                    lifecycle.effectiveFrom(),
                    lifecycle.effectiveUntil(),
                    lifecycle.archivedAt(),
                    lifecycle.archivedBy() == null ? null : lifecycle.archivedBy().toString(),
                    object.createdAt(),
                    object.updatedAt());
        }
    }
}
