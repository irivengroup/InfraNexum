package io.infranexum.core.events;

/** Best-effort signal executed after durable commit; failures never roll back committed data. */
@FunctionalInterface
public interface PostCommitAction {
    void run() throws Exception;
}
