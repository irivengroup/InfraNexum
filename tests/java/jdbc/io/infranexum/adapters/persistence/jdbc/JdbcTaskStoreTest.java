package io.infranexum.adapters.persistence.jdbc;

import org.junit.jupiter.api.Test;

/** Executes the durable workers JDBC contract against the strict scripted driver. */
class JdbcTaskStoreTest {
    @Test
    void submissionReplayAndConflictContract() {
        JdbcTaskStoreSmoke.provesSubmissionReplayAndConflict();
    }

    @Test
    void submissionRaceAndPersistenceFailureContract() {
        JdbcTaskStoreSmoke.provesSubmissionRaceAndPersistenceFailures();
    }

    @Test
    void claimCheckpointRetryAndCancellationContract() {
        JdbcTaskStoreSmoke.provesClaimCheckpointRetryAndCancellation();
    }

    @Test
    void recoveryAndOracleDialectContract() {
        JdbcTaskStoreSmoke.provesClaimRecoveryVariantsAndOracleDialect();
    }

    @Test
    void completionAndFailureTransitionContract() {
        JdbcTaskStoreSmoke.provesCompletionAndFailureTransitions();
    }

    @Test
    void cancellationAndFindContract() {
        JdbcTaskStoreSmoke.provesCancellationOutcomesAndFind();
    }

    @Test
    void leaseFencingAndRecoveryContract() {
        JdbcTaskStoreSmoke.provesLeaseFencingAndRecovery();
    }

    @Test
    void checkpointFailureDiagnosticContract() {
        JdbcTaskStoreSmoke.provesCheckpointFailureDiagnostics();
    }

    @Test
    void transactionAndDataGuardContract() {
        JdbcTaskStoreSmoke.provesTransactionAndDataGuards();
    }

    @Test
    void configurationGuardContract() {
        JdbcTaskStoreSmoke.provesConfigurationGuards();
    }
}
