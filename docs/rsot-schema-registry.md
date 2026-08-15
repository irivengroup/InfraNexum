# PGM-06-E03 — Core Schema Registry et profils RSOT composables

## Portée

`alpha.0.76` implémente le registre d’autorité **Core Contracts/Compatibility** pour les contrats JSON versionnés utilisés par RSOT et les autres consommateurs autorisés. Le registre est disponible lorsque la capability `rsot.core` est effectivement composée ; cette capability appartient au minimum fonctionnel Lite et reste disponible sur Pro et Enterprise sans dépendre d’une activation commerciale.

La tranche couvre :

- schémas JSON versionnés par clé métier et version sémantique ;
- cycle de vie `DRAFT → PUBLISHED → DEPRECATED` ;
- immutabilité après publication ;
- révision optimiste indépendante de la version sémantique, exposée en HTTP par `ETag`/`If-Match` ;
- analyse de compatibilité avant publication ;
- approbation d’architecture obligatoire pour une rupture détectée ;
- blocage fail-closed lorsqu’une compatibilité ne peut pas être prouvée automatiquement ;
- profils composables versionnés constitués uniquement de schémas publiés ;
- persistance PostgreSQL/Oracle symétrique ;
- audit Core et événements transactionnels ;
- RBAC, API HTTP/OpenAPI, CLI Server et client Web.

## Frontière de sécurité déclarative

Le registre stocke uniquement des **données de schéma**. Il ne charge ni n’exécute aucun script, expression ou référence distante. Pour les `RSOT_EXTENSION`, le validateur parcourt récursivement le document et refuse les mots-clés orientés exécution ou I/O, notamment shell, script, Python, JavaScript, processus, réseau et fichier. Les `$ref` externes sont interdits ; seuls les pointeurs internes commençant par `#` sont acceptés.

La validation autorise exclusivement une racine JSON Schema de type `object`. Si `$schema` est fourni, il doit désigner JSON Schema 2020-12. La définition est bornée à 1 MiB au niveau du modèle métier.

## Compatibilité

La publication compare un brouillon avec la dernière version publiée ou dépréciée de la même clé. Le résultat est l’un des trois verdicts suivants :

- `COMPATIBLE` : la publication peut continuer ;
- `BREAKING` : la publication exige une référence d’approbation d’architecture explicite ;
- `INDETERMINATE` : la publication est refusée ; le changement doit être reformulé ou traité par une évolution ultérieure explicitement gouvernée.

Sont notamment classés `BREAKING` : suppression de propriété, réduction de l’ensemble des types acceptés, passage optionnel → requis, retrait d’une valeur d’énumération, changement de format et retrait du schéma d’éléments d’un tableau. Les modifications de contraintes dont la compatibilité n’est pas démontrable localement (`pattern`, bornes, `oneOf`, `additionalProperties`, etc.) sont classées `INDETERMINATE` plutôt que supposées compatibles.

## Concurrence et identité des versions

La **version sémantique** (`1.0.0`, `2.0.0`, …) est l’identité publique du contrat. La **révision** (`1`, `2`, …) protège les mutations concurrentes d’un même enregistrement. Une mutation HTTP sur une ressource existante doit présenter l’`ETag` courant via `If-Match`; une révision périmée produit un conflit au lieu d’écraser silencieusement une modification concurrente.

## Profils composables

Un profil est identifié par `code + version`. Il contient une séquence ordonnée et bornée de 1 à 128 références de schémas. La création et la publication refusent tout membre qui n’est pas `PUBLISHED`. La composition publiée est immutable ; une évolution se fait par une nouvelle version du profil. La dépréciation exige une date de fin future et une raison.

## Autorisation

Les permissions atomiques introduites par la migration `0018` sont :

| Permission | Usage |
|---|---|
| `rsot.schema.create` | créer un schéma ou profil |
| `rsot.schema.read` | rechercher, lire et prévisualiser la compatibilité |
| `rsot.schema.update` | modifier un brouillon de schéma |
| `rsot.schema.publish` | publier un schéma ou profil |
| `rsot.schema.deprecate` | déprécier un schéma ou profil |
| `rsot.audit` | permission réservée aux usages d’audit RSOT |

L’enforcement HTTP et CLI reste deny-by-default via le service RBAC existant. La migration attribue les nouvelles permissions système au rôle protégé `system.platform_admin` afin qu’une mise à niveau n’enferme pas l’administrateur bootstrap hors de la nouvelle surface.

## API HTTP

La base est `/api/v1/rsot`. Les collections sont `/schemas` et `/schema-profiles`. Les mutations utilisent CSRF sur la surface navigateur et `If-Match` pour les transitions d’une ressource existante. Les erreurs sont des `application/problem+json` sans définition de schéma ni donnée sensible dans le détail.

Le contrat source de vérité est :

`src/applications/server/resources/openapi/rsot-schema-registry.yaml`

Chaque opération porte explicitement `x-infranexum-capability: rsot.core` et sa permission RBAC.

## CLI Server

La CLI reprend les mêmes use cases :

```text
infranexum rsot schema <create|list|show|update|compatibility|publish|deprecate>
infranexum rsot schema-profile <create|list|show|publish|deprecate>
```

Les secrets sont lus par `--password-file`; les schémas JSON par `--definition-file`. Les mutations acceptent `--dry-run`. Les sorties peuvent être `text` ou `json`. Les codes de sortie sont stables : `0` succès, `2` usage, `3` authentification, `4` autorisation/capability, `5` règle métier et `70` erreur interne.

## Persistance et migrations

`0017-core-schema-registry` appartient au contexte `core-compatibility` et crée le registre, les profils et leurs membres. Aucune clé étrangère vers IAM, Organisation ou RSOT n’est créée. PostgreSQL utilise `JSONB`; Oracle utilise un `CLOB` avec contrainte `IS JSON`.

`0018-identity-access-rsot-schema-permissions` appartient à IAM et ne contient que les permissions atomiques et leur attribution au rôle système de bootstrap. Ce découpage évite qu’une migration Core modifie directement le stockage IAM.

Les deux migrations disposent d’un rollback et de requêtes de vérification PostgreSQL/Oracle. Un rollback de `0017` détruit uniquement les objets du registre Core ; un rollback de `0018` retire uniquement les attributions et permissions introduites par cette migration.

## Événements et audit

Les mutations sont effectuées dans l’unité de travail transactionnelle existante et émettent au minimum :

- `rsot.schema.created.v1` ;
- `rsot.schema.updated.v1` ;
- `rsot.schema.published.v1` ;
- `rsot.schema.deprecated.v1` ;
- `rsot.schema.profile.created.v1` ;
- `rsot.schema.profile.published.v1` ;
- `rsot.schema.profile.deprecated.v1`.

Les payloads contiennent uniquement les identifiants, clé/code, version, statut et checksum. Le document JSON du schéma n’est pas recopié dans l’audit ou l’événement.

## Rollback applicatif

Avant publication en environnement cible :

1. sauvegarder la base ;
2. appliquer `0017`, puis `0018` ;
3. exécuter les vérifications de migration ;
4. vérifier `rsot.core`, RBAC, API et CLI ;
5. n’autoriser la publication de nouveaux schémas qu’après les smokes.

Tant qu’aucune donnée du registre n’est requise par une tranche ultérieure, le rollback consiste à arrêter la nouvelle version, exécuter `0018` puis `0017` en sens inverse et redéployer la version précédente. Après qu’un consommateur dépend d’un schéma publié, la suppression physique du registre ne constitue plus un rollback sûr : la restauration doit conserver les contrats publiés.
