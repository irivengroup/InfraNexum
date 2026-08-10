# InfraNexum 2.0.0-alpha.0.12 — état d’implémentation

## Objet de l’incrément

Cet incrément démarre `PGM-02-E06 — audit append-only et export signé` sans modifier les contrats publics d’activation livrés précédemment.

## Implémentation intégrée

### Core Audit

Le module `src/components/core/audit` fournit un modèle d’audit immutable et scoped, une chaîne SHA-256 par scope, une implémentation mémoire thread-safe, un codec JSON canonique pour les métadonnées et un export déterministe signé Ed25519.

Les données sensibles ne peuvent pas être injectées via des clés de métadonnées connues pour transporter des credentials. Les métadonnées sont limitées à 4 KiB UTF-8.

### JDBC et migrations

`JdbcAuditJournal` persiste les entrées sans opération de mutation ou de suppression. La tête cryptographique d’un scope est protégée par `SELECT ... FOR UPDATE`; `READ_COMMITTED` suffit à sérialiser les writers du même scope sans augmenter artificiellement le taux de rollback de concurrence.

La migration `0005-core-audit` est appariée PostgreSQL/Oracle. Elle crée la tête de chaîne, le journal et les tombstones réglementaires, avec indexes de recherche et triggers bloquant `UPDATE` et `DELETE`. Son rollback refuse la destruction lorsque des preuves existent.

### Export signé

Les exports bornés vérifient la continuité de chaîne avant génération. Le ZIP est reproductible et contient le payload JSONL, un manifeste SHA-256 et la signature Ed25519. La vérification indépendante contrôle digest et signature.

### CI

Un gate `audit-test/audit-check` à 100 % de couverture bloque les dérives d’immutabilité, d’intégrité, de signature, de migration et de wiring. Le job PostgreSQL applique désormais la migration `0005` et cible aussi `PostgreSqlJdbcAuditJournalTest`.

## Non-régression exécutée localement

- architecture : 28 tests, 100 %, 0 violation ;
- toolchains : 17 tests, 99 %, 0 violation ;
- migrations : 20 tests, 99 %, 0 violation ;
- eventing : 10 tests, 100 %, 0 violation ;
- persistence : 10 tests, 98 %, 0 violation ;
- capabilities : 10 tests, 99 %, 0 violation ;
- entitlements : 10 tests, 100 %, 0 violation ;
- audit : 8 tests, 100 %, 0 violation ;
- total des tests Python de gates : 113 ;
- huit smokes Java autonomes, dont Core Audit et JDBC Audit ;
- compilation statique des nouveaux tests JUnit/PostgreSQL avec un harnais minimal ;
- Agent : `go vet`, race detector, 98,4 %, build statique, smoke processus et 20 répétitions du runtime ;
- Web : 27/27, 99,65 % lignes, 98,28 % branches, 100 % fonctions.

## Limites explicites

Le statut global reste **NON TERMINÉ**.

Le reactor Maven Java 25, JaCoCo et les tests PostgreSQL/Oracle réels ne sont pas exécutables dans l’environnement local. Le workflow GitHub mis à jour doit confirmer les tests Core Audit et JDBC Audit avec Java 25 ainsi que PostgreSQL 17/18.

La recherche avancée, l’autorisation IAM, l’audit des consultations/exports, le chiffrement des exports, la durée de disponibilité et la purge réglementaire opérationnelle restent à implémenter. Le modèle et le stockage du tombstone ne sont pas présentés comme un workflow de purge complet.

Docker Compose reste non applicable : le JAR Server Java 25, le provisioning d’installation neuve, le runner de migrations et les secrets de démarrage ne sont pas encore fermés.
