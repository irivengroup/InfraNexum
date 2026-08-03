# Activation signée, identité d’installation et cycle Lite

## Périmètre

Cet incrément matérialise le noyau hors ligne de `PGM-02-E05` sans introduire de clé privée dans le produit client. Il couvre les contrats, décisions temporelles, vérifications cryptographiques, contrôles d’accès et schémas de persistance nécessaires aux profils Lite, Pro et Enterprise.

## Identité d’installation

`InstallationIdentity` associe :

- un identifiant métier UUIDv7 ;
- une version de fingerprint ;
- un fingerprint SHA-256 construit de manière déterministe à partir d’attributs normalisés ;
- la date UTC de création à la seconde entière.

La fonction de fingerprint trie les attributs, normalise les noms et refuse les valeurs vides, ambiguës ou contenant des caractères de contrôle. La migration `0004-core-entitlements` persiste cette identité dans `core_installation_identity`.

## Cycle Lite

Lite n’accepte aucun manifeste d’activation.

| Intervalle UTC depuis le premier démarrage opérationnel | État | Démarrage | Mutations |
|---|---|---:|---:|
| J0 inclus à J180 exclu | `EVALUATION` | autorisé | autorisées |
| J180 inclus à J210 exclu | `CONVERSION_REQUIRED` | autorisé | refusées |
| À partir de J210 inclus | `HARD_STOPPED` | refusé | refusées |

Le contrôle `EntitlementGuard` applique ces décisions aux frontières de démarrage et de mutation avec des codes d’erreur stables. Les opérations de lecture, export, sauvegarde, diagnostic et import d’activation devront être enregistrées explicitement comme routes autorisées pendant la fenêtre de conversion ; leur routage applicatif n’est pas encore implémenté.

## Manifestes Pro et Enterprise

Le schéma strict est `infranexum.activation-manifest/v2`. Il exige notamment :

- l’identité du client et de l’installation ;
- le profil Pro ou Enterprise et le tier compatible ;
- la version du catalogue ;
- l’ensemble exact des 119 quotas ;
- les capacités autorisées ;
- les bornes de validité UTC ;
- une grâce fixe de 30 jours ;
- une séquence monotone ;
- un identifiant de clé ;
- une signature Ed25519.

La charge signée utilise une représentation JSON canonique UTF-8, sans flottants, avec tri lexicographique des clés. Le contrat pack contient le SHA-256 du schéma afin que le gate détecte toute dérive.

## Validation hors ligne

`ActivationManifestVerifier` refuse par défaut :

- une clé inconnue, expirée ou révoquée ;
- une signature altérée, y compris les erreurs Ed25519 de point invalide ;
- une activation révoquée ;
- une liaison client, installation, fingerprint, profil ou catalogue incorrecte ;
- une séquence inférieure ou conflictuelle ;
- un quota absent, inconnu, négatif ou incompatible avec les politiques ;
- une capacité inconnue ou interdite au profil ;
- un `host_limit` différent de `rsot.managed_hosts.max` ;
- une activation utilisée avant `valid_from`.

Les états produits sont :

- `ACTIVE` avant `valid_until` ;
- `GRACE` pendant les 30 jours suivant `valid_until` ;
- `HARD_STOPPED` à l’issue de cette grâce.

## Protection contre le retour d’horloge

`TrustedTimeGuard` protège une preuve temporelle avec HMAC-SHA-256. La preuve contient l’identité d’installation, le fingerprint, l’origine d’évaluation, le dernier instant fiable et une génération monotone. Deux copies identiques sont attendues : une en base et une dans un stockage indépendant.

Le contrôle refuse :

- une preuve liée à une autre installation ;
- un MAC incorrect ;
- une divergence entre les deux copies ;
- une heure courante antérieure au dernier instant fiable ;
- un débordement de génération.

Limite résiduelle : la restauration coordonnée des deux copies vers le même ancien état ne peut pas être détectée sans ancre monotone externe, par exemple TPM, HSM, service d’attestation ou journal distant append-only. L’adaptateur de stockage indépendant et cette ancre relèvent de la dépendance `PGM-13-E02` et ne sont pas encore implémentés.

## Persistance

La migration appariée PostgreSQL/Oracle `0004-core-entitlements` crée :

- `core_installation_identity` ;
- `core_entitlement_state` ;
- `core_entitlement_integrity_proof` ;
- `core_activation_manifest` ;
- `core_activation_revocation`.

Elle impose les profils et tiers compatibles, la grâce de 30 jours, les séquences monotones et les relations d’intégrité. Les SQL, modèles logiques, vérifications et rollbacks sont validés statiquement. Leur exécution réelle sur PostgreSQL et Oracle reste requise.

## Éléments restant à implémenter

- génération et persistance transactionnelle de l’identité lors du premier démarrage ;
- repositories JDBC d’activation, révocation, état et preuve temporelle ;
- stockage indépendant de preuve et intégration TPM/HSM ou ancre distante ;
- import atomique du manifeste et mise à jour de la séquence acceptée ;
- endpoints `status`, `preflight` et import administrateur ;
- raccordement de l’état d’activation au registre de capacités du Server ;
- notifications avant expiration et pendant la grâce ;
- workflows de renouvellement, révocation, upgrade et rollback ;
- générateurs externes Python et PHP, maintenus hors du produit client.
