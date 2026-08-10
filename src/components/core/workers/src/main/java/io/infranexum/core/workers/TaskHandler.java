package io.infranexum.core.workers;

/** Executable background task contract registered by a bounded context. */
public interface TaskHandler {
    TaskType taskType();

    RetrySafety retrySafety();

    void execute(TaskExecutionContext context) throws Exception;
}
