package io.infranexum.rsot.application;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.rsot.domain.CanonicalObject;
import io.infranexum.rsot.domain.RsotException;
import io.infranexum.rsot.ports.RsotRepository;
import java.util.List;
import java.util.Objects;

/** Read-side canonical object use cases; consumer reads fail closed on uncertified lifecycle states. */
public final class RsotQueryService {
    private final RsotRepository repository;

    public RsotQueryService(RsotRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public CanonicalObject get(DomainIdentifier canonicalId, boolean governanceView) {
        CanonicalObject object = repository.findCanonicalObject(Objects.requireNonNull(canonicalId, "canonicalId"))
                .orElseThrow(() -> new RsotException("RSOT_CANONICAL_OBJECT_NOT_FOUND", "canonical object not found"));
        requireReadable(object, governanceView);
        return object;
    }

    public List<CanonicalObject> list(int offset, int limit, boolean governanceView) {
        page(offset, limit);
        return repository.listCanonicalObjects(offset, limit).stream()
                .filter(object -> governanceView || object.lifecycle().status().consumerReadable())
                .toList();
    }

    private static void requireReadable(CanonicalObject object, boolean governanceView) {
        if (!governanceView && !object.lifecycle().status().consumerReadable()) {
            throw new RsotException("RSOT_CANONICAL_OBJECT_NOT_READABLE", "canonical object is not in a consumer-readable state");
        }
    }

    private static void page(int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 200) {
            throw new IllegalArgumentException("pagination must use offset >= 0 and limit between 1 and 200");
        }
    }
}
