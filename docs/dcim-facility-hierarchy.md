# PGM-07-E04 — Hiérarchie physique DCIM

## Objet

`alpha.0.81` implémente le registre d’autorité DCIM des emplacements physiques : **Site → Building → Floor → Room**, complété par les **technical Zones** rattachables à un Site, Building, Floor ou Room. L’agrégat commun `FacilityNode` mutualise les invariants de hiérarchie, version et gouvernance sans effacer les contrats propres à chaque type.

Organisation et Subdivision restent des références faibles vers le bounded context Organization. La persistance DCIM n’introduit donc aucune clé étrangère inter-contexte. À l’intérieur de DCIM, `parent_id` est une relation forte et vérifiée. Le scope d’unicité d’un Site est sa Subdivision ; Building/Floor/Room utilisent leur parent ; une Zone utilise le Site racine afin que son code soit unique sur le Site.

## Contrat du Site

Un Site ne peut être créé sans `organizationId`, `subdivisionId`, code, nom, **adresse ligne 1, code postal, ville, code pays ISO alpha-2 et fuseau IANA**. `addressLine2`, latitude et longitude sont optionnels. Le code est normalisé en majuscules et respecte `^[A-Z0-9][A-Z0-9_-]{2,63}$`. Le texte descriptif Site est borné à 2000 caractères.

Cycle de vie : `DRAFT → ACTIVE`; depuis `ACTIVE`, `SUSPENDED` ou `ARCHIVED`; depuis `SUSPENDED`, retour `ACTIVE` ou `ARCHIVED`; puis `ARCHIVED → DELETED`. Un Site ne peut être archivé ou supprimé tant qu’un **Building actif** lui appartient. Une Zone active directement rattachée au Site ne déclenche pas ce verrou, conformément au gate CDC.

## Contrats des enfants

- **Building** : parent Site actif ; `floorCount` strictement positif ; `areaM2`, latitude et longitude optionnels ; cycle `DRAFT/ACTIVE/MAINTENANCE/ARCHIVED/DELETED`.
- **Floor** : parent Building actif ; `levelNumber` obligatoire et peut être négatif ; `areaM2`, `levelHeightM` et `capacityKw` optionnels ; même cycle que Building.
- **Room** : parent Floor actif ; `areaM2` obligatoire ; `capacityKw` optionnel ; accès `open|restricted|secure` ; état supplémentaire `LOCKED` avec événement métier `dcim.room.locked.v1`.
- **Zone** : parent Site/Building/Floor/Room actif ; type obligatoire `cooling|power_distribution|airflow|security` ; cycle `DRAFT/ACTIVE/MAINTENANCE/INACTIVE/ARCHIVED/DELETED`.

Les champs d’un type sont refusés sur les autres types. Le domaine et les contraintes PostgreSQL/Oracle appliquent les mêmes règles afin qu’une écriture SQL directe ne puisse pas créer un état impossible à restaurer par l’application.

## Concurrence, idempotence et événements

Toutes les mutations utilisent `expectedVersion` et échouent sur conflit optimiste. Une `Idempotency-Key` de 8 à 200 caractères protège les répétitions ; la justification d’audit est obligatoire et bornée. La mutation métier, l’écriture JDBC, l’enregistrement d’idempotence et l’outbox sont exécutés dans la même unité de travail.

Les événements génériques `dcim.<kind>.created.v1`, `updated.v1` et `status_changed.v1` sont complétés par `dcim.site.archived.v1`, `dcim.site.deleted.v1` et `dcim.room.locked.v1` lorsque ces transitions surviennent.

## Sécurité et surface publique

La capability `dcim.facilities` est évaluée fail-closed. Les permissions atomiques sont organisation-scoped et séparées par ressource (`dcim.site.*`, `dcim.building.*`, `dcim.floor.*`, `dcim.room.*`, `dcim.zone.*`). Les contrôleurs réévaluent RBAC/ABAC avec l’Organisation réelle issue de la requête ou de l’objet chargé ; ils n’exposent pas l’existence ou le type d’un objet avant autorisation.

OpenAPI 3.1 expose cinq ressources explicites — `sites`, `buildings`, `floors`, `rooms`, `zones` — avec **25 opérations natives**, plutôt qu’une route polymorphe opaque. La CLI Server consomme les mêmes use cases et les mêmes contrôles d’autorisation.

## Interface Web

La fonctionnalité est livrée avec parité Web dans la même tranche. Le workspace DCIM est capability-gated et fournit listes, détail, création, modification et transitions. Les relations ne sont jamais demandées sous forme d’UUID libre : les sélecteurs suivent **Organisation → Subdivision → Site → Building → Floor → Room**. Les Zones choisissent leur parent depuis les catalogues autorisés. Les Sites disposent en plus du filtre pays.

Les libellés et états sont disponibles en **DE/EN/ES/FR/IT**. Les états loading/empty/error/restricted/success sont explicites. La règle `docs/web-functional-parity.md` s’applique : un client JavaScript seul ne constitue jamais une livraison Web.

## Migrations et rollback

- `0026-dcim-facility-hierarchy` : table hiérarchique DCIM, index, unicité, contraintes de type/adresse et idempotence ; PostgreSQL/Oracle avec vérifications et rollback borné aux objets DCIM.
- `0027-identity-access-dcim-facility-permissions` : permissions atomiques DCIM, bootstrap `system.platform_admin`, PostgreSQL/Oracle avec rollback des seules permissions E04.

Un rollback applicatif ne remplace pas une sauvegarde. En environnement cible, l’upgrade, `verify.sql.yaml` et le rollback doivent être rejoués sur PostgreSQL et Oracle avant promotion.
