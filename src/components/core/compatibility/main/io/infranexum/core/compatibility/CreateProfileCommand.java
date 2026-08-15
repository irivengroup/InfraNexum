package io.infranexum.core.compatibility;

import io.infranexum.core.contracts.DomainIdentifier;
import java.util.List;

/** Inputs for a composable profile draft. Member order is contract-significant. */
public record CreateProfileCommand(String code, String owner, String version, List<DomainIdentifier> schemaIds) {}
