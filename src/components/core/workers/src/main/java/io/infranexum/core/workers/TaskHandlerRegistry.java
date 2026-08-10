package io.infranexum.core.workers;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable registry that rejects duplicate task types at composition time. */
public final class TaskHandlerRegistry {
    private final Map<TaskType, TaskHandler> handlers;

    public TaskHandlerRegistry(Collection<? extends TaskHandler> handlers) {
        Objects.requireNonNull(handlers, "handlers");
        Map<TaskType, TaskHandler> registered = new LinkedHashMap<>();
        for (TaskHandler handler : handlers) {
            TaskHandler candidate = Objects.requireNonNull(handler, "handler");
            TaskType type = Objects.requireNonNull(candidate.taskType(), "handler.taskType");
            Objects.requireNonNull(candidate.retrySafety(), "handler.retrySafety");
            if (registered.putIfAbsent(type, candidate) != null) {
                throw new IllegalArgumentException("duplicate task handler for type " + type);
            }
        }
        this.handlers = Map.copyOf(registered);
    }

    public TaskHandler require(TaskType type) {
        Objects.requireNonNull(type, "type");
        TaskHandler handler = handlers.get(type);
        if (handler == null) {
            throw new IllegalArgumentException("no task handler is registered for type " + type);
        }
        return handler;
    }

    public Optional<TaskHandler> find(TaskType type) {
        return Optional.ofNullable(handlers.get(Objects.requireNonNull(type, "type")));
    }

    public int size() {
        return handlers.size();
    }
}
