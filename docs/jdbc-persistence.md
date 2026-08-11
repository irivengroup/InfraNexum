# Persistance JDBC transactionnelle

## Objectif

L’adaptateur `components.adapters.persistence-jdbc` garantit que les écritures métier, les réservations inbox et les événements outbox utilisent la même transaction JDBC. Il implémente aussi le `TaskStore` durable des workers. Les deux adapters restent pilotés par le `DataSource` du déploiement sans intégrer de secret de connexion au dépôt.

## Frontière transactionnelle

`JdbcTransactionalEventStore.execute(...)` :

1. ouvre une connexion depuis le `DataSource` fourni par le déploiement ;
2. désactive l’autocommit et applique l’isolation configurée ;
3. rend cette connexion accessible aux repositories du bounded context sur le thread courant ;
4. exécute les écritures métier, inbox et outbox ;
5. vérifie que toute réservation inbox acceptée a été complétée ;
6. commit la connexion ;
7. retire la connexion du contexte de thread ;
8. exécute les actions post-commit sans pouvoir annuler le commit.

Les unités de travail imbriquées sont refusées. Les opérations de dispatcher (`claimBatch`, `markPublished`, `markFailed`) ouvrent leurs propres transactions et ne peuvent pas être appelées depuis une unité de travail métier active.

## Concurrence outbox

### PostgreSQL

Le claim utilise une CTE ordonnée, un lot borné, `FOR UPDATE SKIP LOCKED`, puis `UPDATE ... RETURNING` dans une seule instruction. Plusieurs workers peuvent donc réclamer des lots disjoints sans verrou global applicatif.

### Oracle

Le claim sélectionne un lot ordonné avec `FOR UPDATE SKIP LOCKED`, borne les lignes via JDBC, puis met à jour les enregistrements sélectionnés dans la même transaction. Ce chemin reste à certifier sur Oracle 19c et 26ai dans le laboratoire dédié.

## Inbox et idempotence

Une livraison crée une réservation `PROCESSING` avant l’appel du handler. Le handler et ses écritures utilisent la même transaction. La réservation passe à `COMPLETED` uniquement lorsque le handler termine correctement. Un rollback supprime donc la réservation non commitée et permet une reprise ultérieure.

Une clé déjà `COMPLETED` retourne `DUPLICATE`. Une clé concurrente encore `PROCESSING` est refusée afin de ne pas exécuter simultanément deux handlers pour le même couple `(consumerName, eventId)`.


## Persistance durable des workers

`JdbcTaskStore` applique les invariants du Core Workers sur PostgreSQL et Oracle :

- soumission idempotente protégée par une contrainte unique `(task_type, idempotency_key)` ;
- paramètres stockés relationnellement pour conserver une comparaison sémantique exacte ;
- claim ordonné avec `FOR UPDATE SKIP LOCKED` ;
- incrément atomique de `attempts` et `lease_version` ;
- fencing de toutes les mutations par `task_id + lease_owner + lease_version` ;
- checkpoint atomique avec renouvellement du lease ;
- récupération bornée de 1 000 leases expirés par transaction ;
- retry uniquement pour `RETRY_SAFE` et sous le plafond de `RetryPolicy` ;
- `AT_MOST_ONCE` expiré => `FAILED`, résultat externe inconnu, sans retry automatique ;
- annulation immédiate pour `PENDING` et coopérative pour `RUNNING`.

La migration appariée `0006-core-workers` crée `worker_task` et `worker_task_parameter`, ainsi que les indexes de due-task et lease expiry. PostgreSQL conserve les tokens/valeurs dans des colonnes bornées à 4096 caractères ; Oracle utilise des `CLOB` et des triggers dédiés aux invariants qui dépendent du contenu LOB. Le rollback est fail-closed dès qu’une tâche durable existe.

La migration `0007-core-installation-uuidv7` impose également le contrat `DomainIdentifier` sur l’identité d’installation persistée. PostgreSQL peut réparer automatiquement l’UUIDv4 introduit par le bootstrap Docker alpha.0.31 uniquement avant toute consommation par Entitlements ; Oracle refuse toute identité legacy invalide et exige une réparation explicite. Après migration, la base refuse les identifiants d’installation non UUIDv7.

## Configuration Server

```yaml
infranexum:
  persistence:
    mode: POSTGRESQL
    isolation: READ_COMMITTED
```

Valeurs de `mode` : `MEMORY`, `POSTGRESQL`, `ORACLE`.

Valeurs d’`isolation` : `READ_COMMITTED`, `SERIALIZABLE`.

En mode PostgreSQL ou Oracle, le déploiement doit fournir un bean `DataSource` configuré avec un pool maintenu et des secrets externalisés. L’absence du `DataSource` provoque un échec de démarrage ; aucun repli mémoire n’est autorisé.

## Validation

```bash
make persistence-test persistence-check java-jdbc-smoke java-jdbc-workers-smoke
make postgresql-test-schema
./mvnw --batch-mode --no-transfer-progress --fail-at-end verify
make java-module-verify
```

Les deux jobs JaCoCo Java de la CI démarrent PostgreSQL 17, appliquent les migrations `0001` à `0006` via le target canonique `postgresql-test-schema`, puis exécutent les tests live. Les contrats PostgreSQL ne sont donc pas autorisés à disparaître du calcul de couverture par absence de DSN. La matrice d’intégration PostgreSQL 17/18 réutilise le même target de migration et vérifie l’atomicité métier/outbox, le rollback, l’inbox, la déduplication, les claims concurrents, les retries, l’ownership des leases, les transitions du `JdbcTaskStore`, la persistance Entitlements et les revocations.

`JdbcInfrastructureCoverageTest` couvre en complément, sans base externe, les branches de dialecte PostgreSQL/Oracle, mapping JDBC, conversions temporelles, erreurs SQL et corruption du proof store. Les tests live restent l’autorité pour la sémantique transactionnelle du moteur réel ; ils ne sont plus le seul moyen d’atteindre les branches d’infrastructure.

## Limites opérationnelles

- aucun pool n’est packagé dans cet incrément ;
- aucun secret de connexion n’est accepté dans le dépôt ;
- Oracle réel n’est pas exécuté localement ;
- le dispatcher de production, ses métriques, son backpressure et son runbook ne sont pas encore intégrés ;
- aucune garantie exactly-once ou d’ordre global n’est revendiquée.
