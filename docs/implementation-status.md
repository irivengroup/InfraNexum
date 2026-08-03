# Implementation status — 2.0.0-alpha.0

## Completed scope in this source increment

| Scope | State | Evidence |
|---|---|---|
| PGM-01-E01 repository topology | Implemented | eight spaces, manifests, `CHECK-ARCH-*` regression tests |
| PGM-01-E02 foundation toolchains | Partial | Java 25, Go 1.26.5, Python 3.13 and Maven 3.9.16 pins |
| PGM-01-E03 Architecture-as-Code | Partial | ownership, dependencies, languages, namespaces, brand, repository hygiene and secret checks |
| PGM-02-E01 Agent bootstrap | Implemented and locally executed | strict configuration, health/build endpoints, scope rule, race tests, coverage and static build |
| PGM-02-E01 Server bootstrap | Source implemented, target build pending | Spring Boot/Spring Modulith composition root, strict configuration, health and build endpoint tests |

## Validation environment

The local environment provides Python 3.13.5, Go 1.23.2 and OpenJDK 21.0.10. Consequently:

- Python architecture validation is executable locally;
- the Agent can be compatibility-tested locally with `GOTOOLCHAIN=local`, including the race detector;
- the exact Go 1.26.5 target remains a CI validation;
- the Java 25 Maven reactor cannot be executed locally.

## Not completed

The overall product remains **NON TERMINÉ**. PGM-03 through PGM-16, the TypeScript/C++ toolchains, complete quality/security/licence gates, and the executable Web bootstrap remain outside this increment. The Java build must run with:

```bash
./mvnw --batch-mode --no-transfer-progress verify
```

Prerequisites are JDK 25 plus outbound HTTPS to the Maven distribution host and Maven Central. Expected results are successful compilation, Spring context and Modulith verification, unit/integration tests, JaCoCo line/branch coverage of at least 98%, and executable Server JAR packaging.
