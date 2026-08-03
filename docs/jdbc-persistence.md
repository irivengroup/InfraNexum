# Persistance JDBC transactionnelle

## Objectif

L’adaptateur `components.adapters.persistence-jdbc` garantit que les écritures métier, les réservations inbox et les événements outbox utilisent la même transaction JDBC. Il implémente le port `TransactionalEventStore` sans introduire de dépendance de driver dans le code de production.

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
make persistence-test persistence-check java-jdbc-smoke
./mvnw --batch-mode --no-transfer-progress verify
```

La CI PostgreSQL applique les migrations `0001`, `0002`, `0003`, puis vérifie l’atomicité métier/outbox, le rollback, l’inbox, la déduplication, les claims concurrents, les retries et l’ownership des leases sur PostgreSQL 17 et 18.

## Limites opérationnelles

- aucun pool n’est packagé dans cet incrément ;
- aucun secret de connexion n’est accepté dans le dépôt ;
- Oracle réel n’est pas exécuté localement ;
- le dispatcher de production, ses métriques, son backpressure et son runbook ne sont pas encore intégrés ;
- aucune garantie exactly-once ou d’ordre global n’est revendiquée.
