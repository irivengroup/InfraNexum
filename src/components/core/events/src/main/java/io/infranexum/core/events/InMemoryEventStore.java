package io.infranexum.core.events;

import io.infranexum.core.contracts.DomainIdentifier;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Deterministic reference implementation of the transactional event store.
 *
 * <p>It serializes unit-of-work commits with a fair lock and copy-on-write state.
 * Production adapters must provide equivalent semantics through the database
 * transaction used by the bounded context; this implementation is intended for
 * contract tests, local development and non-persistent smoke validation only.
 */
public final class InMemoryEventStore implements TransactionalEventStore {
    private static final int MAX_BATCH_SIZE = 1_000;
    private static final int MAX_FAILURE_LENGTH = 1_024;

    private final ReentrantLock lock = new ReentrantLock(true);
    private State state = new State();

    /** Executes work atomically and runs notification hooks after the committed state is visible. */
    @Override
    public <T> TransactionOutcome<T> execute(TransactionalWork<T> work) {
        Objects.requireNonNull(work, "work");
        List<PostCommitAction> actions;
        T value;
        lock.lock();
        try {
            State working = state.copy();
            Transaction transaction = new Transaction(working);
            try {
                value = work.execute(transaction);
                transaction.validateComplete();
            } catch (Exception error) {
                if (error instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new TransactionExecutionException("transactional work rolled back", error);
            }
            state = working;
            actions = List.copyOf(transaction.postCommitActions);
        } finally {
            lock.unlock();
        }

        List<String> failures = new ArrayList<>();
        for (PostCommitAction action : actions) {
            try {
                action.run();
            } catch (Exception error) {
                if (error instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                failures.add(sanitizeFailure(error));
            }
        }
        return new TransactionOutcome<>(value, failures);
    }

    /** Claims a bounded ordered batch, recovering records whose lease expired. */
    @Override
    public List<OutboxRecord> claimBatch(String workerId, int limit, Instant now, Duration leaseDuration) {
        String normalizedWorker = requireText(workerId, "workerId", 160);
        if (limit < 1 || limit > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_BATCH_SIZE);
        }
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }

        lock.lock();
        try {
            recoverExpiredLeases(now);
            List<MutableOutboxRecord> candidates = state.outbox.values().stream()
                    .filter(record -> record.status == OutboxStatus.PENDING && !record.availableAt.isAfter(now))
                    .sorted(Comparator
                            .comparing((MutableOutboxRecord record) -> record.availableAt)
                            .thenComparing(record -> record.event.occurredAt())
                            .thenComparing(record -> record.event.eventId()))
                    .limit(limit)
                    .toList();
            Instant leaseUntil = safeAdd(now, leaseDuration);
            List<OutboxRecord> claimed = new ArrayList<>(candidates.size());
            for (MutableOutboxRecord record : candidates) {
                record.status = OutboxStatus.IN_FLIGHT;
                record.attempts++;
                record.leaseOwner = normalizedWorker;
                record.leaseUntil = leaseUntil;
                claimed.add(record.snapshot());
            }
            return List.copyOf(claimed);
        } finally {
            lock.unlock();
        }
    }

    /** Marks a leased record published by the same worker. */
    @Override
    public void markPublished(DomainIdentifier eventId, String workerId, Instant publishedAt) {
        Objects.requireNonNull(eventId, "eventId");
        String normalizedWorker = requireText(workerId, "workerId", 160);
        Objects.requireNonNull(publishedAt, "publishedAt");
        lock.lock();
        try {
            MutableOutboxRecord record = requireLeased(eventId, normalizedWorker);
            record.status = OutboxStatus.PUBLISHED;
            record.publishedAt = publishedAt;
            record.leaseOwner = null;
            record.leaseUntil = null;
            record.lastFailure = null;
        } finally {
            lock.unlock();
        }
    }

    /** Releases a leased record for bounded retry or moves it to dead letter. */
    @Override
    public OutboxStatus markFailed(
            DomainIdentifier eventId,
            String workerId,
            Instant failedAt,
            RetryPolicy retryPolicy,
            Throwable failure) {
        Objects.requireNonNull(eventId, "eventId");
        String normalizedWorker = requireText(workerId, "workerId", 160);
        Objects.requireNonNull(failedAt, "failedAt");
        Objects.requireNonNull(retryPolicy, "retryPolicy");
        Objects.requireNonNull(failure, "failure");
        lock.lock();
        try {
            MutableOutboxRecord record = requireLeased(eventId, normalizedWorker);
            record.lastFailure = sanitizeFailure(failure);
            record.leaseOwner = null;
            record.leaseUntil = null;
            if (record.attempts >= retryPolicy.maximumAttempts()) {
                record.status = OutboxStatus.DEAD_LETTER;
                return record.status;
            }
            record.status = OutboxStatus.PENDING;
            record.availableAt = safeAdd(failedAt, retryPolicy.delayAfterFailure(record.attempts));
            return record.status;
        } finally {
            lock.unlock();
        }
    }

    /** Returns a stable ordered snapshot for diagnostics and contract tests. */
    public List<OutboxRecord> outboxSnapshot() {
        lock.lock();
        try {
            return state.outbox.values().stream()
                    .map(MutableOutboxRecord::snapshot)
                    .sorted(Comparator.comparing(record -> record.event().eventId()))
                    .toList();
        } finally {
            lock.unlock();
        }
    }

    /** Returns committed inbox receipts ordered by consumer and event. */
    public List<InboxReceipt> inboxSnapshot() {
        lock.lock();
        try {
            return state.inbox.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(Map.Entry::getValue)
                    .toList();
        } finally {
            lock.unlock();
        }
    }

    private MutableOutboxRecord requireLeased(DomainIdentifier eventId, String workerId) {
        MutableOutboxRecord record = state.outbox.get(eventId);
        if (record == null) {
            throw new IllegalArgumentException("unknown outbox event: " + eventId);
        }
        if (record.status != OutboxStatus.IN_FLIGHT || !workerId.equals(record.leaseOwner)) {
            throw new IllegalStateException("outbox event is not leased by worker " + workerId);
        }
        return record;
    }

    private void recoverExpiredLeases(Instant now) {
        for (MutableOutboxRecord record : state.outbox.values()) {
            if (record.status == OutboxStatus.IN_FLIGHT
                    && record.leaseUntil != null
                    && !record.leaseUntil.isAfter(now)) {
                record.status = OutboxStatus.PENDING;
                record.availableAt = now;
                record.leaseOwner = null;
                record.leaseUntil = null;
            }
        }
    }

    private static Instant safeAdd(Instant value, Duration duration) {
        try {
            return value.plus(duration);
        } catch (java.time.DateTimeException | ArithmeticException error) {
            throw new IllegalArgumentException("time calculation overflow", error);
        }
    }

    private static String requireText(String value, String field, int maximumLength) {
        Objects.requireNonNull(value, field);
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + " exceeds " + maximumLength + " characters");
        }
        return normalized;
    }

    private static String sanitizeFailure(Throwable failure) {
        String type = failure.getClass().getSimpleName();
        String rendered = type.isBlank() ? "Failure" : type;
        return rendered.length() <= MAX_FAILURE_LENGTH ? rendered : rendered.substring(0, MAX_FAILURE_LENGTH);
    }

    private static final class State {
        private final Map<DomainIdentifier, MutableOutboxRecord> outbox;
        private final Map<InboxKey, InboxReceipt> inbox;

        private State() {
            this(new LinkedHashMap<>(), new LinkedHashMap<>());
        }

        private State(
                Map<DomainIdentifier, MutableOutboxRecord> outbox,
                Map<InboxKey, InboxReceipt> inbox) {
            this.outbox = outbox;
            this.inbox = inbox;
        }

        private State copy() {
            Map<DomainIdentifier, MutableOutboxRecord> outboxCopy = new LinkedHashMap<>();
            outbox.forEach((key, value) -> outboxCopy.put(key, value.copy()));
            return new State(outboxCopy, new LinkedHashMap<>(inbox));
        }
    }

    private static final class MutableOutboxRecord {
        private final EventEnvelope event;
        private OutboxStatus status;
        private int attempts;
        private Instant availableAt;
        private String leaseOwner;
        private Instant leaseUntil;
        private Instant publishedAt;
        private String lastFailure;

        private MutableOutboxRecord(EventEnvelope event) {
            this.event = event;
            status = OutboxStatus.PENDING;
            availableAt = event.occurredAt();
        }

        private MutableOutboxRecord copy() {
            MutableOutboxRecord copy = new MutableOutboxRecord(event);
            copy.status = status;
            copy.attempts = attempts;
            copy.availableAt = availableAt;
            copy.leaseOwner = leaseOwner;
            copy.leaseUntil = leaseUntil;
            copy.publishedAt = publishedAt;
            copy.lastFailure = lastFailure;
            return copy;
        }

        private OutboxRecord snapshot() {
            return new OutboxRecord(
                    event, status, attempts, availableAt, leaseOwner, leaseUntil, publishedAt, lastFailure);
        }
    }

    private final class Transaction implements EventTransaction {
        private final State working;
        private final List<PostCommitAction> postCommitActions = new ArrayList<>();
        private final Map<InboxKey, InboxReservation> acceptedInbox = new LinkedHashMap<>();
        private final Set<InboxKey> completedInbox = new HashSet<>();

        private Transaction(State working) {
            this.working = working;
        }

        @Override
        public void append(EventEnvelope event) {
            Objects.requireNonNull(event, "event");
            if (working.outbox.putIfAbsent(event.eventId(), new MutableOutboxRecord(event)) != null) {
                throw new IllegalStateException("duplicate outbox event id: " + event.eventId());
            }
        }

        @Override
        public InboxDecision beginInbox(InboxReservation reservation) {
            Objects.requireNonNull(reservation, "reservation");
            InboxKey key = reservation.key();
            if (working.inbox.containsKey(key)) {
                return InboxDecision.DUPLICATE;
            }
            if (acceptedInbox.putIfAbsent(key, reservation) != null) {
                throw new IllegalStateException("inbox key already reserved in this transaction: " + key);
            }
            return InboxDecision.ACCEPTED;
        }

        @Override
        public void completeInbox(InboxKey key, Instant completedAt) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(completedAt, "completedAt");
            InboxReservation reservation = acceptedInbox.get(key);
            if (reservation == null) {
                throw new IllegalStateException("inbox key was not accepted in this transaction: " + key);
            }
            if (!completedInbox.add(key)) {
                throw new IllegalStateException("inbox key already completed in this transaction: " + key);
            }
            InboxReceipt receipt = new InboxReceipt(
                    key,
                    reservation.eventType(),
                    reservation.payloadSha256(),
                    reservation.receivedAt(),
                    completedAt);
            working.inbox.put(key, receipt);
        }

        @Override
        public void afterCommit(PostCommitAction action) {
            postCommitActions.add(Objects.requireNonNull(action, "action"));
        }

        private void validateComplete() {
            acceptedInbox.keySet().forEach(key -> {
                if (!completedInbox.contains(key)) {
                    throw new IllegalStateException("accepted inbox key was not completed: " + key);
                }
            });
        }
    }
}
