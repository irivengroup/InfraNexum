# Capabilities, entitlements, quotas et policies

## Finalité

Le composant `components.core.capabilities` constitue l’unique registre décisionnel de la surface fonctionnelle et des limites d’allocation. Les bounded contexts et les interfaces consomment ses décisions ; ils ne déduisent jamais une fonctionnalité du nom d’un profil commercial.

## Modèle de décision

Une capacité est évaluée selon les dimensions suivantes, dans un ordre déterministe :

1. existence dans le catalogue ;
2. profil d’installation autorisé ;
3. composant installé ;
4. rôle de déploiement requis ;
5. topologie autorisée ;
6. trait technique requis ;
7. disponibilité des dépendances ;
8. état d’activation ;
9. entitlement explicite lorsqu’il est requis.

Chaque résultat contient un code stable, une disponibilité booléenne et une raison exploitable. `CapabilityGuard` bloque les opérations lorsque la décision n’est pas disponible. Cette garde doit être appelée à la frontière applicative ou métier concernée, même si l’interface a déjà masqué la fonction.

## Catalogue fonctionnel

Le catalogue courant comporte 21 capacités :

- trois capacités de base disponibles selon l’environnement : authentification locale, PostgreSQL et Discovery agentless ;
- neuf capacités Enterprise uniquement, dont Oracle, Agent distribué, régional et multi-région ;
- neuf capacités Pro ou Enterprise, dont IAM externe, MFA, séparation Web et haute disponibilité.

Le snapshot expose un hash déterministe de surface fonctionnelle. Le tier d’allocation est volontairement exclu de ce hash : passer de Pro Standard à Pro Advanced ou d’Enterprise Standard à Ultimate ne change aucune fonctionnalité.

## Profils et tiers

Profils d’installation :

- `LITE` ;
- `PRO` ;
- `ENTERPRISE`.

Tiers d’allocation :

- `STANDARD` pour tous les profils ;
- `ADVANCED` uniquement avec Pro ;
- `ULTIMATE` uniquement avec Enterprise.

Les tiers ajustent exclusivement les quotas commerciaux. Ils n’autorisent ni nouveau composant, ni nouveau rôle, ni nouvelle topologie, ni nouveau backend.

## Catalogue des quotas

Le registre charge 119 quotas :

- 108 quotas `commercial_scalable` ;
- 11 quotas `architectural_fixed`.

Les quotas architecturaux, notamment les limites de déploiement, ne peuvent pas être modifiés par un manifeste commercial. Les valeurs Lite sont fixes. Les overrides sont validés au chargement et toute clé inconnue provoque un refus explicite.

Le fichier normatif embarqué dans l’archive d’architecture `2.0.0-draft.21` déclare lui-même `catalog_version: 2.0.0-draft.20`. Cette valeur est conservée sans correction implicite.

## Règles d’allocation

### Pro Advanced

Pour chaque quota commercial comparable :

```text
pro_standard <= valeur <= pro_advanced_ceiling
2 × valeur < enterprise_standard
```

L’inégalité est stricte ; une valeur égale à 50 % du quota Enterprise standard est refusée.

### Enterprise Ultimate

```text
enterprise_standard <= valeur <= enterprise_ultimate_ceiling
```

### Réduction de quota

Une réduction ne supprime ni ne masque les données existantes. Lorsque l’usage courant excède la nouvelle limite :

- les lectures, exports et corrections non augmentatives restent autorisés ;
- toute opération augmentant l’usage est bloquée ;
- l’allocation redevient possible lorsque l’usage repasse sous la limite.

### Seuils d’usage

Le moteur distingue les niveaux 80 %, 90 % et 100 % afin de permettre information, avertissement puis blocage. Le calcul protège les dépassements arithmétiques.

## API Server

```text
GET /api/v1/platform/capabilities
GET /api/v1/platform/capabilities/{code}
GET /api/v1/platform/quotas
```

Les réponses utilisent `Cache-Control: no-store`. Ces routes sont diagnostiques et en lecture seule. Une décision exposée ne remplace pas l’autorisation IAM ni la garde métier de l’opération ciblée.

## Invariants automatisés

Le gate `src/validation/capabilities` vérifie notamment :

- les hashes du contract pack ;
- le nombre et les classes des quotas ;
- la parité entre catalogue CSV et policy JSON ;
- les restrictions de capacités ;
- les plafonds Advanced/Ultimate ;
- le ratio strict Pro Advanced/Enterprise ;
- l’impossibilité de modifier les quotas architecturaux ;
- l’absence du tier dans le hash fonctionnel ;
- le câblage Maven, Server, CI et manifestes ;
- l’absence de branchement sur un profil ou un tier dans les sources métier Java.

## Limites de l’incrément

Le registre utilise encore une configuration de démarrage locale. Les manifestes d’activation signés, l’identité d’installation, la validation offline, la révocation, les périodes de grâce et l’anti-retour d’horloge seront traités dans l’incrément d’activation. Le Web ne consomme pas encore le snapshot pour enregistrer dynamiquement ses routes et menus.
