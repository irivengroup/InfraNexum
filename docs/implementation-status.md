# InfraNexum 2.0.0-alpha.0.5 — état d’implémentation

## Organisation des sources

Toutes les sources applicatives, composants, migrations, tests, validateurs et outils sont regroupés sous `src/`. Les preuves générées sont séparées sous `artifacts/validation/`. Architecture-as-Code interdit toute nouvelle source placée hors de `src/`.

## Implémenté dans cet incrément

### Registre central des capacités

- 21 capacités gouvernées par un catalogue versionné ;
- décisions fondées sur profil installé, composant, rôle, topologie, trait, dépendance, activation et entitlement ;
- codes de raison stables et explications déterministes ;
- snapshot immutable avec hash de surface fonctionnelle ;
- `CapabilityGuard` pour bloquer les opérations non disponibles ;
- absence de dépendance du cœur aux frameworks Web ou Spring.

### Profils et tiers

- profils `LITE`, `PRO`, `ENTERPRISE` ;
- tiers `STANDARD`, `ADVANCED`, `ULTIMATE` validés selon le profil ;
- Pro Advanced et Enterprise Ultimate limités aux quotas ;
- tier exclu du hash de capacités ;
- gate interdisant les branches métier Java fondées sur le nom du profil ou du tier.

### Quotas

- 119 quotas normatifs chargés depuis le catalogue documentaire ;
- 108 quotas commerciaux ajustables et 11 limites architecturales fixes ;
- validation des overrides, clés, classes et plafonds ;
- ratio strict `2 × Pro Advanced < Enterprise Standard` ;
- réduction non destructive des limites ;
- blocage des allocations augmentatives à la limite ou au-delà ;
- niveaux d’usage et protection contre les dépassements arithmétiques.

### Intégration Server

- configuration typée `infranexum.platform` ;
- snapshot calculé une seule fois au démarrage ;
- endpoints read-only de snapshot, explication et quotas ;
- réponses `Cache-Control: no-store` ;
- valeurs Lite sûres par défaut, sans fallback vers une capacité non installée.

### Validation

- nouveau gate statique des capabilities/quotas ;
- smoke Java dépendance-zéro couvrant catalogue, hash, profils, tiers et quotas ;
- intégration dans `verify-foundation` et GitHub Actions ;
- maintien des gates architecture, toolchains, migrations, événements, JDBC, Agent et Web.

## Limites explicites

Le produit complet reste **NON TERMINÉ**.

Restent à valider sur les toolchains cibles :

- reactor Maven, tests Spring/JUnit/Modulith et JaCoCo sous Java 25 ;
- Agent sous Go 1.26.5 ;
- Web sous Node.js 24.18.1 et pnpm 11.17.0 ;
- contrats PostgreSQL 17/18 et Oracle 19c/26ai sur moteurs réels.

Restent non implémentés dans cette tranche :

- manifeste d’activation signé et identité d’installation ;
- validation offline, révocation, grâce et anti-retour d’horloge ;
- consommation de la surface fonctionnelle par le shell React ;
- transport Kafka, DLQ et replay audité ;
- bounded contexts métier, IAM complet, audit, installateur et packaging de production.

## Prochaine tranche

La prochaine tranche logique est `PGM-02-E05` : identité d’installation et activation signée, avec validation offline, liaison client/installation, expiration, période de grâce, révocation, compte de secours et protection contre les retours d’horloge.
