# InfraNexum 2.0.0-alpha.0.1 — état d’implémentation

## Implémenté dans cet incrément

- catalogue exact des toolchains polyglottes et contrôle de dérive ;
- premier Domain Contract Pack Core versionné ;
- génération UUIDv7 RFC 9562, monotone localement et résistante aux retours d’horloge ;
- erreurs métier stables et compatibilité sémantique des contrats ;
- catalogue logique de migrations appariées PostgreSQL/Oracle ;
- migration `0001` de l’historique de schéma, vérifications et rollback pour les deux moteurs ;
- gates de CI pour toolchains, contrats et migrations.

## Limites explicites

Le produit complet reste **NON TERMINÉ**. Le Web React/TypeScript n’est pas encore matérialisé. Le reactor Java 25, les migrations sur PostgreSQL 18/17 et Oracle 19c/26ai, les scénarios d’upgrade/reprise/restauration et les contrôles de parité sur moteurs réels nécessitent les toolchains et services externes correspondants.

## Prochaine tranche

La tranche suivante doit compléter `PGM-02-E01` avec le shell Web React/TypeScript, configuration validée, health contract et internationalisation DE/EN/ES/FR/IT, puis exécuter le reactor Java 25 et le laboratoire PostgreSQL.
