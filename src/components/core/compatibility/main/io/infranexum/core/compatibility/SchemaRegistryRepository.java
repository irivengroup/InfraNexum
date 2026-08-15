package io.infranexum.core.compatibility;

import io.infranexum.core.contracts.DomainIdentifier;
import java.util.List;
import java.util.Optional;

/** Persistence port for the Core Contracts/Compatibility registry. Mutations require an active unit of work. */
public interface SchemaRegistryRepository {
    Optional<RegisteredSchema> findSchema(DomainIdentifier id);
    Optional<RegisteredSchema> findSchemaVersion(String schemaKey, String version);
    Optional<RegisteredSchema> latestPublishedSchema(String schemaKey);
    List<RegisteredSchema> listSchemas(String schemaKey, SchemaKind kind, RegistryStatus status, int offset, int limit);
    void insertSchema(RegisteredSchema schema);
    void updateDraftSchema(RegisteredSchema schema);
    void publishSchema(RegisteredSchema schema);
    void deprecateSchema(RegisteredSchema schema);

    Optional<SchemaProfile> findProfile(DomainIdentifier id);
    Optional<SchemaProfile> findProfileVersion(String code, String version);
    List<SchemaProfile> listProfiles(String code, RegistryStatus status, int offset, int limit);
    void insertProfile(SchemaProfile profile);
    void publishProfile(SchemaProfile profile);
    void deprecateProfile(SchemaProfile profile);
}
