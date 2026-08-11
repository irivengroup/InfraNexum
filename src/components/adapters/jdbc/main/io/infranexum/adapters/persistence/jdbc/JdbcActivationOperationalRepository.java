package io.infranexum.adapters.persistence.jdbc;

import io.infranexum.core.capabilities.AllocationTier;
import io.infranexum.core.capabilities.InstallationProfile;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.entitlements.AcceptedSequence;
import io.infranexum.core.entitlements.ActivationManifest;
import io.infranexum.core.entitlements.ActivationManifestPayload;
import io.infranexum.core.entitlements.ActivationVerificationResult;
import io.infranexum.core.entitlements.CanonicalJson;
import io.infranexum.core.entitlements.EntitlementRuntimePhase;
import io.infranexum.core.entitlements.EntitlementRuntimeRepository;
import io.infranexum.core.entitlements.EntitlementRuntimeStatus;
import io.infranexum.core.entitlements.EntitlementStateRecord;
import io.infranexum.core.entitlements.InstallationIdentity;
import io.infranexum.core.entitlements.IntegrityProof;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import javax.sql.DataSource;

/** JDBC implementation for atomic activation acceptance and authoritative runtime state refresh. */
public final class JdbcActivationOperationalRepository implements EntitlementRuntimeRepository {
    private final DataSource dataSource;
    private final JdbcDatabaseDialect dialect;

    public JdbcActivationOperationalRepository(DataSource dataSource, JdbcDatabaseDialect dialect) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
    }

    @Override
    public Optional<InstallationIdentity> installationIdentity() {
        String sql = "SELECT installation_id,fingerprint_version,fingerprint,created_at FROM "
                + table("core_installation_identity");
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                return Optional.empty();
            }
            InstallationIdentity identity = new InstallationIdentity(
                    dialect.readIdentifier(result, "installation_id"),
                    result.getString("fingerprint_version"),
                    result.getString("fingerprint"),
                    JdbcTemporal.readWholeSecondRequired(result, "created_at"));
            if (result.next()) {
                throw new SQLException("multiple installation identities");
            }
            return Optional.of(identity);
        } catch (SQLException error) {
            throw new JdbcPersistenceException("load installation identity", error);
        }
    }

    @Override
    public AcceptedSequence acceptedSequence(InstallationIdentity identity) {
        String sql = "SELECT max_activation_sequence,accepted_activation_id FROM "
                + table("core_entitlement_state") + " WHERE installation_id=?";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            dialect.bindIdentifier(statement, 1, identity.installationId());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return AcceptedSequence.none();
                }
                long value = result.getLong("max_activation_sequence");
                return new AcceptedSequence(value, nullableIdentifier(result, "accepted_activation_id"));
            }
        } catch (SQLException error) {
            throw new JdbcPersistenceException("load accepted activation sequence", error);
        }
    }

    @Override
    public Optional<IntegrityProof> databaseProof(InstallationIdentity identity) {
        String sql = "SELECT installation_id,fingerprint,evaluation_started_at,last_reliable_at,generation,mac_base64 FROM "
                + table("core_entitlement_integrity_proof") + " WHERE installation_id=?";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            dialect.bindIdentifier(statement, 1, identity.installationId());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(readProof(result));
            }
        } catch (SQLException error) {
            throw new JdbcPersistenceException("load database integrity proof", error);
        }
    }

    @Override
    public Optional<EntitlementStateRecord> entitlementState(InstallationIdentity identity) {
        String sql = "SELECT profile,allocation_tier,evaluation_started_at,last_reliable_at,time_generation,"
                + "max_activation_sequence,accepted_activation_id,activation_state,valid_until,grace_until,updated_at FROM "
                + table("core_entitlement_state") + " WHERE installation_id=?";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            dialect.bindIdentifier(statement, 1, identity.installationId());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                long sequence = result.getLong("max_activation_sequence");
                return Optional.of(new EntitlementStateRecord(
                        InstallationProfile.valueOf(result.getString("profile")),
                        AllocationTier.valueOf(result.getString("allocation_tier")),
                        JdbcTemporal.readWholeSecondNullable(result, "evaluation_started_at"),
                        JdbcTemporal.readWholeSecondRequired(result, "last_reliable_at"),
                        result.getLong("time_generation"),
                        new AcceptedSequence(sequence, nullableIdentifier(result, "accepted_activation_id")),
                        EntitlementRuntimePhase.valueOf(result.getString("activation_state")),
                        JdbcTemporal.readWholeSecondNullable(result, "valid_until"),
                        JdbcTemporal.readWholeSecondNullable(result, "grace_until"),
                        JdbcTemporal.readWholeSecondRequired(result, "updated_at")));
            }
        } catch (SQLException | IllegalArgumentException error) {
            throw new JdbcPersistenceException("load entitlement runtime state", error);
        }
    }

    @Override
    public Optional<String> acceptedManifestDocument(InstallationIdentity identity) {
        String sql = dialect == JdbcDatabaseDialect.POSTGRESQL
                ? "SELECT jsonb_build_object("
                    + "'schema','infranexum.activation-manifest/v2',"
                    + "'activation_id',m.activation_id::text,"
                    + "'customer',jsonb_build_object('customer_id',m.customer_id,'legal_name',m.customer_legal_name),"
                    + "'installation',jsonb_build_object('installation_id',m.installation_id::text,"
                    + "'fingerprint_version',i.fingerprint_version,'fingerprint',i.fingerprint),"
                    + "'profile',lower(m.profile),'allocation_tier',lower(m.allocation_tier),"
                    + "'catalog_version',m.catalog_version,'host_limit',m.host_limit,"
                    + "'capabilities',m.capabilities_json,'quotas',m.quotas_json,"
                    + "'valid_from',to_char(m.valid_from AT TIME ZONE 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"'),"
                    + "'valid_until',to_char(m.valid_until AT TIME ZONE 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"'),"
                    + "'grace_period_days',m.grace_period_days,"
                    + "'issued_at',to_char(m.issued_at AT TIME ZONE 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"'),"
                    + "'issuer',m.issuer,'sequence',m.sequence,'key_id',m.key_id,'signature',m.signature_base64)::text "
                    + "FROM core_entitlement_state s JOIN core_activation_manifest m "
                    + "ON m.activation_id=s.accepted_activation_id JOIN core_installation_identity i "
                    + "ON i.installation_id=s.installation_id WHERE s.installation_id=?"
                : "SELECT JSON_OBJECT("
                    + "'schema' VALUE 'infranexum.activation-manifest/v2',"
                    + "'activation_id' VALUE m.activation_id,"
                    + "'customer' VALUE JSON_OBJECT('customer_id' VALUE m.customer_id,'legal_name' VALUE m.customer_legal_name),"
                    + "'installation' VALUE JSON_OBJECT('installation_id' VALUE m.installation_id,"
                    + "'fingerprint_version' VALUE i.fingerprint_version,'fingerprint' VALUE i.fingerprint),"
                    + "'profile' VALUE LOWER(m.profile),'allocation_tier' VALUE LOWER(m.allocation_tier),"
                    + "'catalog_version' VALUE m.catalog_version,'host_limit' VALUE m.host_limit,"
                    + "'capabilities' VALUE m.capabilities_json FORMAT JSON,'quotas' VALUE m.quotas_json FORMAT JSON,"
                    + "'valid_from' VALUE TO_CHAR(m.valid_from AT TIME ZONE 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"'),"
                    + "'valid_until' VALUE TO_CHAR(m.valid_until AT TIME ZONE 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"'),"
                    + "'grace_period_days' VALUE m.grace_period_days,"
                    + "'issued_at' VALUE TO_CHAR(m.issued_at AT TIME ZONE 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"'),"
                    + "'issuer' VALUE m.issuer,'sequence' VALUE m.sequence,'key_id' VALUE m.key_id,"
                    + "'signature' VALUE m.signature_base64 RETURNING CLOB) "
                    + "FROM CORE_ENTITLEMENT_STATE s JOIN CORE_ACTIVATION_MANIFEST m "
                    + "ON m.ACTIVATION_ID=s.ACCEPTED_ACTIVATION_ID JOIN CORE_INSTALLATION_IDENTITY i "
                    + "ON i.INSTALLATION_ID=s.INSTALLATION_ID WHERE s.INSTALLATION_ID=?";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            dialect.bindIdentifier(statement, 1, identity.installationId());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(readLargeText(result, 1));
            }
        } catch (SQLException | java.io.IOException error) {
            throw new JdbcPersistenceException("load accepted activation document", error);
        }
    }

    @Override
    public void accept(
            InstallationIdentity identity,
            ActivationManifest manifest,
            ActivationVerificationResult result,
            IntegrityProof databaseProof,
            Instant acceptedAt) {
        inTransaction("accept activation manifest", connection -> {
            insertManifest(connection, identity, manifest, acceptedAt);
            upsertProof(connection, databaseProof, acceptedAt);
            upsertPaidState(connection, identity, result, databaseProof, acceptedAt);
        });
    }

    @Override
    public void initializeLite(
            InstallationIdentity identity, IntegrityProof databaseProof, Instant initializedAt) {
        inTransaction("initialize Lite entitlement origin", connection -> {
            upsertProof(connection, databaseProof, initializedAt);
            String sql = "INSERT INTO " + table("core_entitlement_state")
                    + " (installation_id,profile,allocation_tier,evaluation_started_at,last_reliable_at,time_generation,"
                    + "max_activation_sequence,accepted_activation_id,activation_state,valid_until,grace_until,updated_at) "
                    + "VALUES (?,?,?, ?,?,?,0,NULL,?,NULL,NULL,?)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int index = 1;
                dialect.bindIdentifier(statement, index++, identity.installationId());
                statement.setString(index++, InstallationProfile.LITE.name());
                statement.setString(index++, AllocationTier.STANDARD.name());
                JdbcTemporal.bindInstant(statement, index++, databaseProof.evaluationStartedAt());
                JdbcTemporal.bindInstant(statement, index++, databaseProof.lastReliableAt());
                statement.setLong(index++, databaseProof.generation());
                statement.setString(index++, EntitlementRuntimePhase.EVALUATION.name());
                JdbcTemporal.bindInstant(statement, index, initializedAt);
                statement.executeUpdate();
            }
        });
    }

    @Override
    public void updateRuntimeState(
            InstallationIdentity identity,
            EntitlementRuntimeStatus status,
            IntegrityProof databaseProof,
            Instant updatedAt) {
        inTransaction("refresh entitlement runtime state", connection -> {
            upsertProof(connection, databaseProof, updatedAt);
            String sql = "UPDATE " + table("core_entitlement_state")
                    + " SET last_reliable_at=?,time_generation=?,activation_state=?,valid_until=?,grace_until=?,updated_at=?"
                    + " WHERE installation_id=? AND profile=?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                JdbcTemporal.bindInstant(statement, 1, databaseProof.lastReliableAt());
                statement.setLong(2, databaseProof.generation());
                statement.setString(3, status.phase().name());
                JdbcTemporal.bindInstant(statement, 4, status.validUntil());
                JdbcTemporal.bindInstant(statement, 5, status.graceUntil());
                JdbcTemporal.bindInstant(statement, 6, updatedAt);
                dialect.bindIdentifier(statement, 7, identity.installationId());
                statement.setString(8, status.profile().name());
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("entitlement state update did not affect exactly one row");
                }
            }
        });
    }

    private void insertManifest(
            Connection connection,
            InstallationIdentity identity,
            ActivationManifest manifest,
            Instant acceptedAt) throws SQLException {
        ActivationManifestPayload payload = manifest.payload();
        String jsonParameter = dialect.jsonParameter();
        String sql = "INSERT INTO " + table("core_activation_manifest")
                + " (activation_id,installation_id,customer_id,customer_legal_name,profile,allocation_tier,"
                + "catalog_version,host_limit,capabilities_json,quotas_json,valid_from,valid_until,grace_period_days,"
                + "issued_at,issuer,sequence,key_id,signature_base64,manifest_sha256,accepted_at) "
                + "VALUES (?,?,?,?,?,?,?,?," + jsonParameter + "," + jsonParameter + ",?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            dialect.bindIdentifier(statement, index++, payload.activationId());
            dialect.bindIdentifier(statement, index++, identity.installationId());
            statement.setString(index++, payload.customer().customerId());
            statement.setString(index++, payload.customer().legalName());
            statement.setString(index++, payload.profile().name());
            statement.setString(index++, payload.allocationTier().name());
            statement.setString(index++, payload.catalogVersion());
            statement.setLong(index++, payload.hostLimit());
            dialect.bindJson(statement, index++, CanonicalJson.string(payload.capabilities().stream().sorted().toList()));
            dialect.bindJson(statement, index++, CanonicalJson.string(new TreeMap<>(payload.quotas())));
            JdbcTemporal.bindInstant(statement, index++, payload.validFrom());
            JdbcTemporal.bindInstant(statement, index++, payload.validUntil());
            statement.setInt(index++, payload.gracePeriodDays());
            JdbcTemporal.bindInstant(statement, index++, payload.issuedAt());
            statement.setString(index++, payload.issuer());
            statement.setLong(index++, payload.sequence());
            statement.setString(index++, payload.keyId());
            statement.setString(index++, manifest.signature());
            statement.setString(index++, sha256(payload.canonicalBytes()));
            JdbcTemporal.bindInstant(statement, index, acceptedAt);
            statement.executeUpdate();
        }
    }

    private void upsertProof(Connection connection, IntegrityProof proof, Instant now) throws SQLException {
        String sql = dialect == JdbcDatabaseDialect.POSTGRESQL
                ? "INSERT INTO core_entitlement_integrity_proof "
                    + "(installation_id,fingerprint,evaluation_started_at,last_reliable_at,generation,mac_base64,updated_at) "
                    + "VALUES (?,?,?,?,?,?,?) ON CONFLICT (installation_id) DO UPDATE SET "
                    + "fingerprint=EXCLUDED.fingerprint,evaluation_started_at=EXCLUDED.evaluation_started_at,"
                    + "last_reliable_at=EXCLUDED.last_reliable_at,generation=EXCLUDED.generation,"
                    + "mac_base64=EXCLUDED.mac_base64,updated_at=EXCLUDED.updated_at"
                : "MERGE INTO CORE_ENTITLEMENT_INTEGRITY_PROOF t USING "
                    + "(SELECT ? INSTALLATION_ID,? FINGERPRINT,? EVALUATION_STARTED_AT,? LAST_RELIABLE_AT,"
                    + "? GENERATION,? MAC_BASE64,? UPDATED_AT FROM dual) s ON (t.INSTALLATION_ID=s.INSTALLATION_ID) "
                    + "WHEN MATCHED THEN UPDATE SET t.FINGERPRINT=s.FINGERPRINT,"
                    + "t.EVALUATION_STARTED_AT=s.EVALUATION_STARTED_AT,t.LAST_RELIABLE_AT=s.LAST_RELIABLE_AT,"
                    + "t.GENERATION=s.GENERATION,t.MAC_BASE64=s.MAC_BASE64,t.UPDATED_AT=s.UPDATED_AT "
                    + "WHEN NOT MATCHED THEN INSERT (INSTALLATION_ID,FINGERPRINT,EVALUATION_STARTED_AT,"
                    + "LAST_RELIABLE_AT,GENERATION,MAC_BASE64,UPDATED_AT) VALUES "
                    + "(s.INSTALLATION_ID,s.FINGERPRINT,s.EVALUATION_STARTED_AT,s.LAST_RELIABLE_AT,"
                    + "s.GENERATION,s.MAC_BASE64,s.UPDATED_AT)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            dialect.bindIdentifier(statement, 1, proof.installationId());
            statement.setString(2, proof.fingerprint());
            JdbcTemporal.bindInstant(statement, 3, proof.evaluationStartedAt());
            JdbcTemporal.bindInstant(statement, 4, proof.lastReliableAt());
            statement.setLong(5, proof.generation());
            statement.setString(6, proof.mac());
            JdbcTemporal.bindInstant(statement, 7, now);
            statement.executeUpdate();
        }
    }

    private void upsertPaidState(
            Connection connection,
            InstallationIdentity identity,
            ActivationVerificationResult result,
            IntegrityProof proof,
            Instant now) throws SQLException {
        ActivationManifestPayload manifest = result.payload();
        String sql = dialect == JdbcDatabaseDialect.POSTGRESQL
                ? "INSERT INTO core_entitlement_state "
                    + "(installation_id,profile,allocation_tier,evaluation_started_at,last_reliable_at,time_generation,"
                    + "max_activation_sequence,accepted_activation_id,activation_state,valid_until,grace_until,updated_at) "
                    + "VALUES (?,?,?,NULL,?,?,?,?,?,?,?,?) ON CONFLICT (installation_id) DO UPDATE SET "
                    + "profile=EXCLUDED.profile,allocation_tier=EXCLUDED.allocation_tier,evaluation_started_at=NULL,"
                    + "last_reliable_at=EXCLUDED.last_reliable_at,time_generation=EXCLUDED.time_generation,"
                    + "max_activation_sequence=EXCLUDED.max_activation_sequence,"
                    + "accepted_activation_id=EXCLUDED.accepted_activation_id,activation_state=EXCLUDED.activation_state,"
                    + "valid_until=EXCLUDED.valid_until,grace_until=EXCLUDED.grace_until,updated_at=EXCLUDED.updated_at"
                : "MERGE INTO CORE_ENTITLEMENT_STATE t USING "
                    + "(SELECT ? INSTALLATION_ID,? PROFILE,? ALLOCATION_TIER,? LAST_RELIABLE_AT,? TIME_GENERATION,"
                    + "? MAX_ACTIVATION_SEQUENCE,? ACCEPTED_ACTIVATION_ID,? ACTIVATION_STATE,? VALID_UNTIL,"
                    + "? GRACE_UNTIL,? UPDATED_AT FROM dual) s ON (t.INSTALLATION_ID=s.INSTALLATION_ID) "
                    + "WHEN MATCHED THEN UPDATE SET t.PROFILE=s.PROFILE,t.ALLOCATION_TIER=s.ALLOCATION_TIER,"
                    + "t.EVALUATION_STARTED_AT=NULL,t.LAST_RELIABLE_AT=s.LAST_RELIABLE_AT,"
                    + "t.TIME_GENERATION=s.TIME_GENERATION,t.MAX_ACTIVATION_SEQUENCE=s.MAX_ACTIVATION_SEQUENCE,"
                    + "t.ACCEPTED_ACTIVATION_ID=s.ACCEPTED_ACTIVATION_ID,t.ACTIVATION_STATE=s.ACTIVATION_STATE,"
                    + "t.VALID_UNTIL=s.VALID_UNTIL,t.GRACE_UNTIL=s.GRACE_UNTIL,t.UPDATED_AT=s.UPDATED_AT "
                    + "WHEN NOT MATCHED THEN INSERT (INSTALLATION_ID,PROFILE,ALLOCATION_TIER,EVALUATION_STARTED_AT,"
                    + "LAST_RELIABLE_AT,TIME_GENERATION,MAX_ACTIVATION_SEQUENCE,ACCEPTED_ACTIVATION_ID,"
                    + "ACTIVATION_STATE,VALID_UNTIL,GRACE_UNTIL,UPDATED_AT) VALUES "
                    + "(s.INSTALLATION_ID,s.PROFILE,s.ALLOCATION_TIER,NULL,s.LAST_RELIABLE_AT,s.TIME_GENERATION,"
                    + "s.MAX_ACTIVATION_SEQUENCE,s.ACCEPTED_ACTIVATION_ID,s.ACTIVATION_STATE,s.VALID_UNTIL,"
                    + "s.GRACE_UNTIL,s.UPDATED_AT)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            dialect.bindIdentifier(statement, index++, identity.installationId());
            statement.setString(index++, manifest.profile().name());
            statement.setString(index++, manifest.allocationTier().name());
            JdbcTemporal.bindInstant(statement, index++, proof.lastReliableAt());
            statement.setLong(index++, proof.generation());
            statement.setLong(index++, manifest.sequence());
            dialect.bindIdentifier(statement, index++, manifest.activationId());
            statement.setString(index++, result.state().name());
            JdbcTemporal.bindInstant(statement, index++, manifest.validUntil());
            JdbcTemporal.bindInstant(statement, index++, result.graceUntil());
            JdbcTemporal.bindInstant(statement, index, now);
            statement.executeUpdate();
        }
    }

    private IntegrityProof readProof(ResultSet result) throws SQLException {
        return new IntegrityProof(
                dialect.readIdentifier(result, "installation_id"),
                result.getString("fingerprint"),
                JdbcTemporal.readWholeSecondRequired(result, "evaluation_started_at"),
                JdbcTemporal.readWholeSecondRequired(result, "last_reliable_at"),
                result.getLong("generation"),
                result.getString("mac_base64"));
    }

    private DomainIdentifier nullableIdentifier(ResultSet result, String column) throws SQLException {
        Object raw = result.getObject(column);
        if (raw == null) {
            return null;
        }
        if (raw instanceof java.util.UUID uuid) {
            return new DomainIdentifier(uuid);
        }
        return DomainIdentifier.parse(raw.toString());
    }

    private String table(String logicalName) {
        return dialect == JdbcDatabaseDialect.POSTGRESQL ? logicalName : logicalName.toUpperCase(java.util.Locale.ROOT);
    }

    private void inTransaction(String operation, SqlWork work) {
        try (Connection connection = dataSource.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                work.execute(connection);
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        } catch (SQLException error) {
            throw new JdbcPersistenceException(operation, error);
        }
    }


    private static String readLargeText(ResultSet result, int column) throws SQLException, java.io.IOException {
        try (java.io.Reader reader = result.getCharacterStream(column)) {
            if (reader == null) {
                throw new SQLException("activation document is null");
            }
            StringBuilder value = new StringBuilder();
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                value.append(buffer, 0, read);
                if (value.length() > 4 * 1024 * 1024) {
                    throw new SQLException("activation document exceeds the 4 MiB runtime limit");
                }
            }
            return value.toString();
        }
    }

    private static String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is required", error);
        }
    }

    @FunctionalInterface
    private interface SqlWork {
        void execute(Connection connection) throws SQLException;
    }
}
