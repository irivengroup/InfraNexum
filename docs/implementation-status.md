# InfraNexum 2.0.0-alpha.0.4 — état d’implémentation

## Organisation des sources

Toutes les sources applicatives, composants, migrations, tests, validateurs et outils sont regroupés sous `src/`. Les preuves générées sont séparées sous `artifacts/validation/`. Un gate Architecture-as-Code interdit toute nouvelle source placée hors de `src/`.

## Implémenté dans cet incrément

### Adaptateur JDBC de production

- port `JdbcConnectionAccess` exposant uniquement la connexion de l’unité de travail courante ;
- `JdbcTransactionalEventStore` thread-safe et unités de travail confinées au thread appelant ;
- refus explicite des unités de travail imbriquées ;
- même connexion physique pour les écritures métier, l’inbox et l’outbox ;
- commit avant exécution des hooks post-commit ;
- rollback préservant la cause d’origine et ses erreurs supprimées éventuelles ;
- isolation JDBC configurable, sans autoriser `TRANSACTION_NONE` ;
- absence de dépendance aux drivers PostgreSQL/Oracle dans le code de production.

### Sémantiques outbox/inbox

- claims bornés de 1 à 1 000 événements ;
- PostgreSQL : claim atomique par CTE, `FOR UPDATE SKIP LOCKED` et `UPDATE ... RETURNING` ;
- Oracle : sélection verrouillée ordonnée et mise à jour dans la même transaction ;
- récupération des leases expirées ;
- contrôle strict du propriétaire avant publication ou échec ;
- retries exponentiels et passage en `DEAD_LETTER` ;
- réservation inbox `PROCESSING` avant handler, puis `COMPLETED` au commit ;
- rollback intégral du handler et de la réservation en cas d’échec ;
- déduplication durable par `(consumerName, eventId)`.

### Schéma et intégration Server

- migration appariée `0003-core-inbox-reservation` PostgreSQL/Oracle ;
- modèle logique, vérifications, rollback conditionnel et checksums ;
- modes Server exclusifs `MEMORY`, `POSTGRESQL`, `ORACLE` ;
- mode mémoire limité au standalone local ;
- absence de fallback silencieux lorsqu’un `DataSource` JDBC manque ;
- aucune auto-configuration implicite d’un pool ou de secrets ;
- job GitHub Actions PostgreSQL 17/18 appliquant `0001→0003` et exécutant les contrats JDBC réels.

### Validation locale

- driver JDBC simulé sans dépendance externe couvrant commit, rollback, outbox, inbox, claims, retry, dead-letter et lease ownership ;
- gate statique de persistance couvrant architecture, SQL, migrations, composition Server et reactor Maven ;
- maintien des gates Architecture-as-Code, toolchains, migrations, événements, Agent et Web.

## Limites explicites

Le produit complet reste **NON TERMINÉ**.

Sont implémentés mais non exécutés sur les cibles réelles dans l’environnement local :

- reactor Maven sous Java 25 ;
- contrats JDBC sur PostgreSQL 17 et 18 ;
- migrations, transactions et concurrence sur Oracle 19c/26ai ;
- wiring d’un pool de connexions de déploiement et de ses secrets externes.

Restent non implémentés dans cette tranche :

- transport Kafka 4.3.x KRaft ;
- DLQ broker durable et replay autorisé/audité ;
- worker/scheduler de production, métriques de backpressure et runbooks ;
- shell React/TypeScript piloté par capabilities et internationalisation DE/EN/ES/FR/IT ;
- bounded contexts métier, IAM, activation, audit, installateurs et packaging de production.

## Prochaine tranche

Après exécution de la CI PostgreSQL et du laboratoire Oracle, la prochaine tranche logique est l’adaptateur de transport Kafka avec publication post-commit, retry borné, DLQ durable, backpressure observable et replay audité, sans modifier le contrat événementiel canonique.
