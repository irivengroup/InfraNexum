# InfraNexum 2.0.0-alpha.0.7 — état d’implémentation

## Organisation des sources

Toutes les sources applicatives, composants, migrations, tests, validateurs et outils sont regroupés sous `src/`. Les preuves générées sont séparées sous `artifacts/validation/`. Architecture-as-Code interdit toute nouvelle source placée hors de `src/`.

## Implémenté dans cet incrément

### Contrats d’identité et d’activation

- identité d’installation UUIDv7 et fingerprint SHA-256 versionné ;
- schéma strict `infranexum.activation-manifest/v2` ;
- JSON canonique UTF-8 déterministe ;
- signature et vérification Ed25519 entièrement hors ligne ;
- trust store public, périodes de validité et registres de révocation ;
- liaison client, installation, fingerprint, profil et catalogue ;
- contrôle de séquence monotone et anti-rejeu ;
- validation des 21 capacités et 119 quotas du catalogue ;
- checksum du schéma dans le contract pack.

### Cycle Lite et profils activés

- frontières Lite exactes J180 et J210 ;
- fenêtre de conversion lecture seule de 30 jours ;
- grâce Pro/Enterprise fixe de 30 jours ;
- états `ACTIVE`, `GRACE`, `CONVERSION_REQUIRED` et `HARD_STOPPED` ;
- garde de démarrage et garde de mutation avec codes d’erreur stables ;
- aucun manifeste autorisé pour Lite.

### Anti-retour d’horloge

- preuve temporelle HMAC-SHA-256 ;
- double preuve base/stockage indépendant ;
- génération monotone ;
- liaison à l’identité d’installation ;
- refus fermé sur divergence, altération ou recul de l’heure.

### Persistance et gouvernance

- migration appariée `0004-core-entitlements` PostgreSQL/Oracle ;
- tables d’identité, état, preuve, manifeste et révocation ;
- contraintes profil/tier, validité, grâce et séquence ;
- nouveau gate statique entitlements à 100 % de couverture ;
- smoke Java dépendance-zéro couvrant signature, révocation, états, quotas, Lite et garde d’accès ;
- maintien sans régression des gates architecture, toolchains, migrations, événements, JDBC, capabilities, Agent et Web.

## Limites explicites

Le produit complet reste **NON TERMINÉ**.

Restent à valider sur les toolchains cibles :

- reactor Maven, tests Spring/JUnit/Modulith et JaCoCo sous Java 25 ;
- Agent sous Go 1.26.5 ;
- Web sous Node.js 24.18.1 et pnpm 11.17.0 ;
- migrations et contrats sur PostgreSQL 17/18 et Oracle 19c/26ai réels.

Restent non implémentés dans cette tranche :

- repositories JDBC et import transactionnel des manifestes ;
- stockage indépendant réel, TPM/HSM ou ancre distante anti-restauration coordonnée ;
- raccordement autoritatif Server, endpoints status/preflight/import et filtrage Web ;
- notifications, renouvellement, révocation distante et upgrade de profil ;
- générateurs externes Python/PHP ;
- transport Kafka, audit append-only, bounded contexts métier, IAM complet, installateur et packaging de production.

## Prochaine tranche

La prochaine tranche logique est `PGM-02-E06` : audit append-only et export signé. Avant de déclarer `PGM-02-E05` certifié de bout en bout, les adaptateurs de persistance et l’ancre temporelle indépendante devront être fermés avec `PGM-13-E02`.
