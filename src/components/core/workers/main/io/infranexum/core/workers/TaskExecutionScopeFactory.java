package io.infranexum.core.workers;

import java.util.Objects;

/** Opens infrastructure context around one handler invocation without coupling Core to logging APIs. */
@FunctionalInterface
public interface TaskExecutionScopeFactory {
    TaskExecutionScope open(TaskExecutionContext context);

    /** Returns a scope factory used by runtimes that do not need contextual infrastructure. */
    static TaskExecutionScopeFactory noop() {
        return ignored -> TaskExecutionScope.NOOP;
    }

    /** Bounded scope closed after one handler invocation. */
    @FunctionalInterface
    interface TaskExecutionScope extends AutoCloseable {
        TaskExecutionScope NOOP = () -> {};

        @Override
        void close();

        static TaskExecutionScope require(TaskExecutionScope scope) {
            return Objects.requireNonNull(scope, "scope");
        }
    }
}
