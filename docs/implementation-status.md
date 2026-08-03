# InfraNexum 2.0.0-alpha.0.3 — état d’implémentation

## Organisation des sources

Toutes les sources applicatives, composants, migrations, tests, validateurs et outils sont regroupés sous `src/`. Les preuves générées sont séparées sous `artifacts/validation/`. Un gate Architecture-as-Code interdit toute nouvelle source placée hors de `src/`.

## Implémenté dans cet incrément

- enveloppe événementielle canonique, versionnée et limitée aux huit champs normatifs ;
- schéma JSON strict et gate de dérive entre contrat, schéma, record Java, modèle logique et SQL ;
- port `TransactionalEventStore` indépendant des frameworks et des moteurs de données ;
- unité de travail copy-on-write atomique de référence ;
- outbox visible uniquement après commit et hooks exécutés strictement post-commit ;
- claims bornés avec lease, récupération des leases expirées et concurrence sans double attribution ;
- retries exponentiels bornés, jitter déterministe injectable et passage en dead-letter ;
- inbox transactionnelle et déduplication par `(consumerName, eventId)` ;
- atomicité entre handler, receipt inbox et nouveaux événements outbox ;
- non-régressions de rollback, reprise, interruption, concurrence et idempotence ;
- migration `0002-core-transactional-events` appariée PostgreSQL/Oracle avec contraintes UUIDv7, contrat d’événement, index, vérification et rollback ;
- intégration des gates événementiels au Makefile, au reactor Maven et à GitHub Actions.

Les incréments précédents restent présents : monorepo huit espaces, Architecture-as-Code, catalogue de toolchains, Agent Go, Server Java, runtime Web, Domain Contract Pack Core et migration `0001`.

## Limites explicites

Le produit complet reste **NON TERMINÉ**.

`InMemoryEventStore` est exclusivement un adaptateur de référence pour les contrats et les tests. Les éléments suivants ne sont pas encore implémentés ou certifiés :

- adaptateurs JDBC PostgreSQL et Oracle ;
- exécution des migrations `0001` et `0002` sur moteurs réels ;
- transport Kafka 4.3.x KRaft ;
- DLQ broker durable, replay autorisé et audit du replay ;
- worker/scheduler de production, métriques de backpressure et runbooks ;
- shell React/TypeScript piloté par capabilities et internationalisation DE/EN/ES/FR/IT ;
- bounded contexts métier, IAM, activation, audit, installateurs et packaging de production.

Le reactor Java 25, Go 1.26.5 exact et Node.js 24.18.1/pnpm 11.17.0 restent non exécutés localement dans cet environnement.

## Prochaine tranche

La prochaine tranche doit compléter le prérequis `PGM-04-E01` par les adaptateurs JDBC PostgreSQL/Oracle, les transactions réelles, l’application/reprise/rollback des migrations et les tests de concurrence sur moteurs supportés. Après cette fermeture, `PGM-02-E03` pourra intégrer le transport Kafka, la DLQ durable et le replay audité sans modifier les contrats du cœur.
