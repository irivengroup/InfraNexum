# Adapters

Les adaptateurs techniques sont introduits uniquement derrière un port approuvé et avec des tests de contrat.

## Adaptateurs actifs

### `persistence-jdbc`

Implémente `TransactionalEventStore` et `JdbcConnectionAccess` pour PostgreSQL et Oracle à partir d’un `DataSource` fourni par le déploiement. Le code de production n’importe aucun driver propriétaire. Les drivers éventuels restent des dépendances de test ou de packaging de déploiement.

Contrats couverts :

- unité de travail JDBC et rollback ;
- atomicité écritures métier/inbox/outbox ;
- claims concurrents bornés ;
- leases, publication, retry et dead-letter ;
- déduplication inbox ;
- stratégies SQL PostgreSQL/Oracle ;
- intégration Server sans fallback silencieux.

Voir `docs/jdbc-persistence.md`.
