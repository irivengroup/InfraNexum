package io.infranexum.itam.asset.domain;

import io.infranexum.core.contracts.DomainIdentifier;
import java.util.Objects;

/** Current accountable holder represented only by a kind and weak UUID reference. */
public record AssetCustodian(AssetCustodianKind kind, DomainIdentifier referenceId) {
    public AssetCustodian {
        Objects.requireNonNull(kind, "kind");
        if (kind == AssetCustodianKind.NONE && referenceId != null) {
            throw new IllegalArgumentException("NONE custodian cannot carry a reference");
        }
        if (kind != AssetCustodianKind.NONE && referenceId == null) {
            throw new IllegalArgumentException("custodian reference is required");
        }
    }

    public static AssetCustodian none() { return new AssetCustodian(AssetCustodianKind.NONE, null); }
    public static AssetCustodian organization(DomainIdentifier id) {
        return new AssetCustodian(AssetCustodianKind.ORGANIZATION, Objects.requireNonNull(id, "id"));
    }
}
