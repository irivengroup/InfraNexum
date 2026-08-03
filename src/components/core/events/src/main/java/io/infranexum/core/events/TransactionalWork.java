package io.infranexum.core.events;

/** Unit-of-work callback executed against one isolated transaction state. */
@FunctionalInterface
public interface TransactionalWork<T> {
    T execute(EventTransaction transaction) throws Exception;
}
