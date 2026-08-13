# InfraNexum 2.0.0-alpha.0.68 — état d’implémentation

## PGM-03-E03 — RBAC foundation

`alpha.0.68` implémente le bounded context `identity-access` avec utilisateurs IAM, memberships Organisation/Subdivision temporels, groupes, groupes imbriqués lorsque le profil l’autorise, rôles protégés, permissions atomiques approuvées et affectations USER/GROUP scopées. Les suppressions sont logiques, les rôles/permissions système sont protégés et les mutations sensibles produisent audit append-only et événements outbox dans l’unité de travail JDBC.

Le PEP HTTP Server est deny-by-default : toute route `/api/v1/**` authentifiée doit être enregistrée explicitement. API, CLI Server et Web utilisent les mêmes cas d’usage RBAC. Le bootstrap conserve le UUID du `local_account` comme `iam_user.id` et garantit `system.platform_admin` pour éviter tout lockout lors d’une installation neuve ou d’un upgrade. L’ABAC/PDP/SoD complet reste hors périmètre et relève de PGM-03-E04.

Le catalogue `draft.21` ne définit pas `organization.read`. Aucun code de permission n’est inventé : les lectures racine d’organisation sont autorisées par membership IAM effectif ou rôle plateforme, tandis que les subdivisions utilisent les permissions approuvées `organization.subdivision.read/search`.

### Validation de clôture locale

**EXÉCUTÉ** — Source Integrity 45/45 (100 %, 0 violation), Archive Compatibility 12/12, Architecture fonctionnelle 67/67 avec contrôle CLI à 0 violation, Toolchains 25/25, migrations 50/50, Eventing 10/10, Persistence 12/12, Capabilities 10/10, Entitlements 10/10, Audit 8/8 et contrats Compose 63/63. Le Web passe 79/79 avec 99,68 % de couverture lignes, 98,39 % branches et 100 % fonctions ; le process smoke retourne `status=passed`. Les 11 smokes Java dependency-free du Makefile passent sous le JDK 21 local avec `javac -Xlint:all -Werror`. Les scénarios comportementaux RBAC passent 22/22 et le registre HTTP deny-by-default 6/6 dans le harness Java local.

La passe de clôture corrige également une régression d’industrialisation héritée : les targets JDBC du Makefile compilaient l’ensemble des adapters sans les domaines/ports qu’ils implémentent. Les targets partagent désormais `JDBC_DOMAIN_SOURCES` pour `identity-local`, `identity-access` et `organization`; un test Architecture dédié verrouille cet ordre de dépendances.

**ÉCHOUÉ puis corrigé dans la révision source** — le build Docker Desktop utilisateur a exécuté Temurin 25.0.4+7 et Maven 3.9.16 jusqu’au module Server, puis a échoué à la compilation de `IdentityAccessController` parce que deux références au catalogue `PermissionCodes` n’étaient pas importées. L’import explicite `io.infranexum.identity.access.domain.PermissionCodes` est désormais présent et un test de non-régression RBAC dédié verrouille les références `GROUP_ADD_GROUP` et `GROUP_REMOVE_GROUP`. Le même build signalait aussi l’alias Spring 7 déprécié `HttpStatus.UNPROCESSABLE_ENTITY`; il est remplacé par `HttpStatus.UNPROCESSABLE_CONTENT` (HTTP 422 inchangé) et couvert par une seconde assertion de non-régression. Le rebuild Docker suivant a ensuite compilé les 63 sources Server sous Java 25 mais a échoué pendant `spring-boot:repackage`, car `InfraNexumServerApplication` et `IdentityAccessCliApplication` exposaient tous deux une méthode `main` sans classe canonique configurée. Le `spring-boot-maven-plugin` déclare désormais explicitement `io.infranexum.server.InfraNexumServerApplication` comme `mainClass`; la CLI reste une entrée Server distincte appelée explicitement. Un test de non-régression vérifie ce contrat de packaging. Le même build Java 25 signalait enfin `ActivationManifestJsonCodec` pour l’usage déprécié de `JsonNode.asText()` sous Jackson 3; le codec utilise désormais `asString()` après son contrôle strict `isString()`, sans élargir les coercitions acceptées, et un test verrouille l’absence de l’API dépréciée. Le candidat corrigé doit encore être reconstruit sur Docker Desktop pour transformer ce gate en succès. **NON EXÉCUTÉ** — JUnit/JaCoCo complet sous JDK 25 après cette correction ; validation Web sous Node 24.18.1, le runtime local disponible étant Node 22.16.0 ; Agent sous Go 1.26.5 ; migrations réelles PostgreSQL/Oracle. Le runner Architecture agrégé avec couverture a dépassé 180 s ; les 67 tests fonctionnels ont été exécutés séparément, mais la couverture agrégée reste non mesurée. Ces contrôles restent obligatoires avant promotion de la release. L’archive source finale est générée de manière reproductible depuis le snapshot Git, contient 726 fichiers, passe le validateur Archive Compatibility à 0 violation et repasse après extraction Source Integrity, Architecture-as-Code, migrations, les 63 contrats Compose et les 79 tests Web. Un manifeste externe `release-files.sha256` couvre les octets réellement archivés ; `source-files.sha256` conserve volontairement sa sémantique de checksums des blobs Git avant filtres LF/CRLF.

## alpha.0.67 — authentification navigateur déterministe et HA Patroni sans tracebacks de transport

La validation Docker Desktop réelle d’`alpha.0.66` confirme désormais le nominal complet (`compose-smoke: PASS`) et le scénario HA PostgreSQL/Server/Web (`compose-ha-smoke: PASS`), mais le formulaire navigateur restait sans réaction. Les tests Web précédents invoquaient directement les callbacks d’un faux DOM et ne prouvaient donc pas l’attachement effectif des événements dans le chemin de bootstrap réel. `alpha.0.67` transforme l’authentification en chemin critique autonome : les listeners `click` et `submit` sont câblés synchroniquement avant le probe de session, les tentatives sont sérialisées, les boutons statiques sont désactivés jusqu’au câblage (`data-auth-wired=true`), et préférences/notifications/admin-shell ne sont initialisés qu’après authentification réussie.

Le smoke Docker ne se contente plus de vérifier qu’un appel anonyme est rejeté : si le compte `admin` est encore en `must_change=true`, il lit explicitement le secret du volume développeur, réalise un `POST /api/v1/iam/local-auth/session` via l’ingress Web, exige le payload de session, les cookies `INX_SESSION` et `INX_XSRF`, puis effectue un logout CSRF protégé sans modifier le credential. Le résultat expose `CredentialLogin=PASS`; si le mot de passe bootstrap a déjà été remplacé, le contrôle est explicitement `SKIPPED_CHANGED`.

Les tracebacks Python Patroni observés pendant le HA smoke provenaient des probes REST qui n’avaient besoin que du code HTTP mais utilisaient des `GET`. Les health checks Patroni Docker, les checks HAProxy `/primary`/`/replica` et la détection de primaire des wrappers utilisent désormais `HEAD`. Les boucles de réintégration deviennent silencieuses pendant les états transitoires et n’impriment les logs qu’en cas d’échec final. En complément, `ha-smoke` collecte les logs Patroni uniquement depuis le début du scénario et échoue si `Traceback`, `ConnectionResetError` ou `BrokenPipeError` apparaît ; le PASS final exige `PatroniPythonErrors=0`.

Validations locales : Web **73/73**, couverture runtime **99,67 % lignes / 98,37 % branches / 100 % fonctions**, process smoke PASS ; Compose **63/63** ; migrations **42/42** ; Architecture **57/57** ; Source Integrity **45/45** ; Toolchains **25/25** ; Archive Compatibility **12/12** ; Eventing **10/10** ; Persistence **12/12** ; Capabilities **10/10** ; Entitlements **10/10** ; Audit **8/8**. L’exécution Chromium headless réelle est **NON EXÉCUTÉE** dans le conteneur de travail car Chromium y bloque sur l’environnement système/DBus même pour une page minimale. La toolchain Agent cible Go 1.26.5 est également **NON EXÉCUTÉE localement** : le Go installé tente de télécharger la toolchain, mais le réseau est désactivé. La validation Docker Desktop/PowerShell d’`alpha.0.67` reste requise.

## alpha.0.66 — normalisation du body HTTP PowerShell

**Statut :** correction ciblée du tooling de smoke Windows, sans changement IAM, migration ou topologie.

La validation Docker Desktop d’`alpha.0.65` a prouvé que le backend renvoie désormais un `401` avec un body JSON UTF-8 complet contenant le `correlation_id` attendu. PowerShell 7 exposait toutefois `Invoke-WebRequest.Content` sous forme binaire et le cast `[string]` produisait une suite décimale (`123 34 115 ...`) au lieu du JSON. `alpha.0.66` introduit une normalisation explicite des corps HTTP (`string`, `byte[]`, `HttpContent`, `Stream` et enumerable de bytes) avant `ConvertFrom-Json`. Le smoke conserve les assertions strictes sur le status HTTP, le header `X-Correlation-ID` et le `correlation_id` du Problem JSON.

## alpha.0.65 — contrat terminal Local Auth et UX Secure Area

`alpha.0.64` a confirmé sur Docker Desktop que le header `X-Correlation-ID` traverse correctement l’ingress Web, mais le body du `401` ne contenait pas le champ `correlation_id` attendu. `alpha.0.65` rend donc la réponse terminale déterministe au niveau de `LocalAuthenticationFilter` : buffer réinitialisé avant écriture, `application/problem+json`, `Content-Length` explicite, corps contenant `correlation_id` et `trace_id`, puis `flushBuffer()` avant retour de la chaîne de filtres. Le smoke PowerShell conserve l’exigence stricte `status + header + body` et affiche désormais le body brut dans le diagnostic en cas de divergence.

L’écran de connexion est simplifié conformément au contrat UI : le formulaire central affiche **Secure Area**, sans eyebrow ni texte introductif. Le panneau de marque conserve son contexte via une clé i18n distincte. Le badge d’état du service d’authentification est conditionnel : il reste masqué quand le service est sain et devient visible uniquement lorsqu’une indisponibilité est détectée. Les cinq langues DE/EN/ES/FR/IT restent couvertes.

Aucune migration, aucun privilège IAM et aucun invariant HA n’est modifié.

## alpha.0.64 — durcissement de la corrélation à la frontière Local Auth

`alpha.0.63` a atteint correctement la frontière d’authentification mais le smoke PowerShell a produit un faux négatif en lisant les en-têtes HTTP d’une réponse `401` via `Exception.Response.Headers`. Ce chemin d’erreur n’expose pas le même contrat de réponse que le résultat normal d’`Invoke-WebRequest`.

`alpha.0.64` rend la frontière auto-descriptive : `LocalAuthenticationFilter` réaffirme explicitement le `X-Correlation-ID` canonique sur ses réponses terminales `401/403`, tandis que le smoke PowerShell 7 utilise `-SkipHttpErrorCheck` pour traiter le `401` attendu comme une réponse normale. Il vérifie simultanément le code HTTP, l’en-tête `X-Correlation-ID` et le champ `correlation_id` du corps Problem JSON à travers le chemin same-origin Web → Server. Le wrapper refuse désormais PowerShell < 7, déjà incompatible avec ses primitives natives modernes.

Aucune migration, aucun privilège IAM et aucun invariant HA n’est modifié.

## alpha.0.63 — correction du contexte PostgreSQL des diagnostics Local Auth

`alpha.0.62` a produit sur Docker Desktop un faux négatif : le smoke interrogeait `infranexum_core.schema_history` et `infranexum_iam.*` via la connexion superutilisateur dédiée aux diagnostics de réplication, laquelle utilise volontairement `dbname=postgres`. Ces objets vivent dans la base applicative `infranexum`; PostgreSQL ne partage pas les schémas entre bases. La relation était donc introuvable dans `postgres` indépendamment de l’état réel des migrations.

`alpha.0.63` sépare explicitement `cluster_admin_db_scalar`/`Invoke-ClusterDatabaseAdminScalar` (base `postgres`, uniquement diagnostics cluster) de `application_admin_db_scalar`/`Invoke-ApplicationDatabaseAdminScalar` (base `infranexum`, diagnostics schéma/historique IAM). Les requêtes `pg_stat_replication` restent sur la connexion cluster et les vérifications `schema_history`, `local_account`, `local_session` et compte bootstrap sont exécutées dans la base applicative. Une non-régression interdit toute requête d’objet applicatif via la connexion cluster.

Aucune migration `0013` n’est ajoutée : `0011` et `0012` restent les migrations IAM autoritatives. La correction porte sur le diagnostic et non sur le modèle de données.


## alpha.0.62 — réparation migration IAM et login

`alpha.0.62` corrigeait un défaut source réel du catalogue découvert après la validation `alpha.0.61`: le dossier `0011-local-identity-foundation` existait sans être déclaré dans `catalogue.yaml`. Le message runtime « relation local_account does not exist » n’est plus interprété comme preuve de l’absence de la table, car `alpha.0.63` a établi que le smoke interrogeait alors la mauvaise base PostgreSQL. La migration `0011-local-identity-foundation` est désormais déclarée dans le catalogue et son contrat `verify.sql.yaml` est machine-validable. Le checker refuse tout futur dossier de migration à quatre chiffres absent de `catalogue.yaml`.

La migration additive `0012-local-identity-repair` est idempotente et non destructive. Elle recrée les objets IAM locaux manquants sur une base persistante et vérifie leur présence. Le wrapper de développement rejoue explicitement `secret-init`, `db-bootstrap` et `migrate` à chaque `up`, sans suppression des volumes. Le smoke vérifie d’abord `schema_history` pour `0011`/`0012`, puis les tables et enfin le compte bootstrap.

Le formulaire Web de connexion expose désormais l’indisponibilité backend dès le probe initial et utilise un état de soumission visible (`aria-busy`, bouton désactivé, indicateur de progression) avec possibilité de retry.






## 2.0.0-alpha.0.61 — PGM-03-E02 Local Identity & Authentication foundation

**Statut : implémentation source complète et validations locales hors reactor JDK25/Docker cible ; RBAC/MFA/fédérations restent hors périmètre de cet incrément.**

Le bounded context `identity-local` introduit les comptes humains locaux, la politique credentials autoritative, les états de verrouillage et les sessions opaques. La politique mot de passe impose 12–128 caractères, majuscule/minuscule/chiffre/spécial, refuse les caractères de contrôle et ne tronque jamais. L’adaptateur Security utilise Argon2id avec sel aléatoire par secret, paramètres de coût versionnés, comparaison constante via la bibliothèque et rehash transparent lors d’une authentification réussie lorsque les paramètres deviennent obsolètes. Les identités inexistantes, suspendues ou temporairement verrouillées consomment un travail cryptographique équivalent et toutes les erreurs de login restent génériques afin d’éviter l’énumération des comptes.

La migration paire `0011-local-identity-foundation` crée les comptes et sessions PostgreSQL/Oracle avec UUIDv7, version optimiste, compteur d’échecs, lockout borné, security epoch, expirations idle/absolute et révocation. Les tokens de session et CSRF ne sont jamais persistés en clair : seuls leurs fingerprints SHA-256 sont stockés. Le changement de mot de passe incrémente le security epoch, révoque toutes les sessions existantes et crée une nouvelle session.

Le Server expose `/api/v1/iam/local-auth/session`, `/password` et `/password-policy/validate`. Le filtre d’authentification protège les autres APIs v1 ; le bootstrap `mustChange` interdit leur accès tant que le secret initial n’a pas été remplacé. Toute mutation protégée exige un header `X-CSRF-Token`. Les cookies `INX_SESSION` (HttpOnly) et `INX_XSRF` utilisent `SameSite=Strict`; `Secure=false` n’est autorisé que pour `environment=local`. `/api/v1/system/build` reste public car il est utilisé comme contrat secret-free de routage/smoke.

Le Web publie `localAuthEnabled` comme configuration publique, affiche un auth gate same-origin avant le dashboard, force le remplacement du secret bootstrap et offre un logout avec révocation côté serveur. Le shell Bootstrap 5.3.6, le thème InfraNexum, le sélecteur de langue stable DE/EN/ES/FR/IT, la command palette, les préférences et les notifications sont conservés. Aucun mot de passe/token n’est stocké dans `localStorage`.

Le banc Docker PRO génère le secret bootstrap dans le volume runtime-secrets et ne le révèle que via la commande développeur explicite `credentials`. Le smoke exige désormais au moins un compte local, la migration `local_session` et un HTTP 401 corrélé sur l’API Organisation anonyme.

Validations locales avant packaging : Web **68/68**, couverture runtime lignes **99,67 %**, branches **98,37 %**, fonctions **100 %**, Web process smoke **PASS** ; smoke Java autonome bootstrap/login/CSRF/password-rotation/session-revocation **PASS** ; compilation stricte Domain/Security sous JDK local compatible **PASS** ; contrats Compose **57/57** ; migrations/Architecture/Source Integrity/Toolchains/Archive Compatibility et gates Core transverses **PASS**. Le reactor Maven complet sous JDK25 et le runtime Docker Desktop de `alpha.0.61` restent à exécuter sur la cible.


## 2.0.0-alpha.0.60 — Operational preferences, notifications & live platform insights

**Statut : expérience Web implémentée et validée localement ; runtime Docker Desktop et contrôle visuel cible à valider.**

Le dashboard internationalisé gagne une couche d’exploitation sans introduire de données simulées. `platform-insights.mjs` lit en same-origin les contrats existants `/api/v1/platform/capabilities` et `/api/v1/platform/quotas`, valide strictement leurs formes puis expose le profil effectif, le palier d’allocation, le nombre de capabilities disponibles/évaluées, les décisions `deployment.high_availability` / `deployment.split_web` et les limites effectives `organization.organizations.max`, `deployment.server.nodes_total.max` et `deployment.web.nodes_total.max`. Toute indisponibilité de l’un des deux contrats remet l’ensemble du widget dans un état explicite `Unavailable` au lieu de conserver des valeurs périmées ou fictives.

Les préférences opérateur sont regroupées dans un document JSON versionné `infranexum.web-preferences/v1`, persisté localement sous `infranexum.preferences.v1`. Les paramètres couvrent la densité (`comfortable|compact`), le comportement de sidebar (`auto|expanded|compact`) et la cadence de rafraîchissement des données opérationnelles (`0|30|60|300` secondes). Une valeur ou un JSON corrompu retombe sur des défauts sûrs. Langue et thème conservent leurs clés historiques séparées pour compatibilité ascendante ; aucune persistance serveur n’est revendiquée avant IAM.

Le contrôle de langue a été durci après observation d'une fermeture prématurée du `<select>` natif pendant l'interaction. Il est remplacé par un listbox accessible persistant dont l'état ouvert n'est jamais modifié par les rafraîchissements runtime/Capabilities/Quotas ou le rerender des notifications. Seules une sélection explicite, `Escape` ou une interaction pointeur hors du composant le ferment ; la navigation clavier `↑/↓`, `Home/End`, `Enter/Espace` est couverte par non-régression.

Le centre de notifications est volontairement **observational** : il ne contient que des faits vus par le navigateur pendant la session (runtime validé/indisponible, lecture Capabilities/Quotas réussie/échouée). Les événements sont identifiés et dédupliqués, disposent d’un compteur non lu et sont retraduits lors d’un changement de locale. Aucun incident, alerte d’infrastructure ou événement métier non fourni par le backend n’est inventé. La command palette ajoute seulement deux actions locales réellement exécutables : ouvrir les préférences et les notifications.

Validations locales de l’incrément avant packaging : Web **61/61**, couverture runtime lignes **99,67 %**, branches **98,33 %**, fonctions **100 %** ; préférences structurées/corruption storage **PASS** ; notifications observées/déduplication/non-lu **PASS** ; Capabilities/Quotas validation/rendu/fail-closed/auto-refresh **PASS**. Les gates transverses et la validation post-extraction sont consignés dans le release manifest.

## 2.0.0-alpha.0.59 — Internationalized administration shell & command palette

**Statut : expérience Web implémentée et validée localement ; rendu final et runtime Docker Desktop à valider sur la cible.**

Le dashboard professionnel introduit en `alpha.0.58` devient un véritable shell d’administration navigable. Les workspaces `Overview` et `Organizations` sont routés par hash sans dépendance frontend externe, la navigation active et le breadcrumb suivent la route courante, et le workspace Organisations reste fail-closed tant que la capability locale pré-IAM n’est pas explicitement disponible. Les modules non livrés restent visuellement signalés comme indisponibles et ne sont pas exposés comme commandes exécutables.

L’internationalisation Web couvre désormais **DE, EN, ES, FR et IT**. Au premier chargement, la langue est résolue depuis `navigator.languages`/`navigator.language` avec repli anglais ; une préférence explicite est persistée sous `infranexum.locale`. Le changement de langue met à jour les libellés statiques, les états runtime dynamiques, les compteurs Organisations/Subdivisions, les actions, les breadcrumbs, le titre de page et les attributs d’accessibilité. Le thème clair/sombre reste indépendant et persistant sous `infranexum.theme`.

Une command palette globale du **dashboard** est accessible par `Ctrl+K` ou `Cmd+K`, avec filtrage localisé insensible à la casse et aux accents, navigation clavier `↑/↓`, sélection `Enter` et fermeture `Esc`. Elle ne simule pas la future recherche métier globale : seules les routes et actions d’interface réellement disponibles sont indexées. Les commandes actuelles couvrent la vue d’ensemble, Organisations lorsque la capability est active, le focus runtime et le basculement de thème.

Le socle visuel reste Bootstrap 5.3.6 vendored localement + thème InfraNexum adapté du prédécesseur, sans CDN ni Bootstrap JavaScript. La nouvelle couche ajoute une barre de commande compacte, un sélecteur de langue, des breadcrumbs, un dialogue de commandes responsive et des états mobile/tablette cohérents, tout en conservant `prefers-reduced-motion`, focus visible, contenu API rendu par `textContent` et accès same-origin `/api`.

Validations locales de l’incrément : Web **49/49**, couverture runtime lignes **99,67 %**, branches **98,33 %**, fonctions **100 %** ; tests i18n dédiés (détection, persistance, fallback, traductions dynamiques) **PASS** ; tests routing/command palette (fail-closed capability, recherche localisée, actions locales idempotentes) **PASS**. Les gates transverses et le packaging final sont consignés dans le release manifest de l’archive.


## 2.0.0-alpha.0.58 — PGM-03-E01 Organization & Subdivision foundation

**Statut : tranche verticale métier implémentée et validée hors runtime Docker/JDK25 cible ; validation Docker Desktop de la migration/API requise.**

Le premier bounded context métier autoritatif est désormais matérialisé sous `src/components/domains/organization`. L’agrégat Organisation utilise un UUIDv7, un code normalisé globalement unique, une version optimiste et le cycle de vie strict `PROVISIONING → ACTIVE ↔ SUSPENDED → ARCHIVING → ARCHIVED → DELETION_PENDING → DELETED`. Les quotas proviennent exclusivement du catalogue de capacités (`organization.organizations.max`, `organization.subdivisions.max`, `organization.hierarchy_depth.max`) ; le code métier ne dérive pas de limites par profil. La hiérarchie d’organisations est réservée au profil qui l’autorise et les Subdivisions sont refusées fail-closed lorsque le profil actif ne les expose pas.

Les Subdivisions utilisent un code unique dans leur Organisation, une relation parent composite confinée à la même Organisation, une profondeur de hiérarchie bornée et une suppression logique. Les scopes temporels couvrent les dimensions `LEGAL`, `GEOGRAPHIC`, `OPERATIONAL`, `ADMINISTRATIVE` et `DATA` sur des intervalles semi-ouverts. Les commandes de création et les transitions Organisation sont idempotentes ; l’idempotency key est liée à une empreinte canonique du payload et à l’identité de ressource. Chaque mutation autoritative émet un événement transactionnel/outbox dont le type respecte le contrat Core versionné (`organization.lifecycle.*.v1`, `organization.subdivision.created.v1`, `organization.scope.created.v1`).

La migration paire `0010-organization-foundation` fournit PostgreSQL et Oracle avec parité logique : contraintes UUIDv7, états, unicité, clés étrangères composites de confinement Organisation, période temporelle, idempotence et rollback. Les adaptateurs JDBC traduisent les collisions uniques en conflits métier stables et excluent les objets supprimés logiquement des quotas.

L’API `/api/v1/iam/organizations` expose recherche, création, lecture, suspension/reprise, Subdivisions et scopes effectifs avec `X-Correlation-ID`, `Idempotency-Key`, ETag et enveloppes d’erreur stables. IAM n’étant pas encore livré, cette frontière est désactivée par défaut et sa composition refuse de démarrer hors `environment=local`. Le Docker PRO de développement l’active explicitement. Le Web appelle l’API en same-origin via HAProxy Web `/api`, affiche Organisations puis Subdivisions de l’Organisation sélectionnée, et conserve Bootstrap 5.3.6 + le thème visuel adapté du prédécesseur uniquement.

Validations locales exécutées : smoke Java autonome Organisation/Subdivision/Scope/outbox **PASS** ; compilation stricte Java Domain/Application et JDBC `-Xlint:all -Werror` sous JDK local **PASS** ; contrats Compose **52/52** ; migrations **38/38** et CLI 0 violation ; Architecture **57/57** et CLI **PASS** ; Web **38/38**, couverture lignes **99,67 %**, branches **98,33 %**, fonctions **100 %**, process smoke **PASS** ; Eventing **10/10**, Persistence **12/12**, Capabilities **10/10**, Entitlements **10/10**, Audit **8/8**, Toolchains **25/25**, Archive Compatibility **12/12**. Le reactor Maven cible JDK25 et le runtime Docker réel de la migration/API restent à exécuter sur l’environnement cible.

La baseline `alpha.0.56` est désormais certifiée par exécution Docker Desktop réelle : smoke nominal, failover/rejoin PostgreSQL, failover Server, failover Web et readiness finale `UP` sont tous PASS.


## 2.0.0-alpha.0.55 — bounded HAProxy Server/Web reconvergence

Le runtime Docker Desktop de `alpha.0.54` confirme le nominal PRO : `compose-smoke: PASS (streaming=2 synchronous=1 Server=4 Web=2)`. Le `ha-smoke` franchit également l’arrêt du primaire Patroni, l’élection du primaire de remplacement et la reconvergence du writer PostgreSQL. Il échoue ensuite sur une fenêtre transitoire HAProxy Server : `GET /actuator/health/readiness` retourne `503 No server is available to handle this request` avant que les nœuds Server, eux-mêmes dépendants de la base, soient à nouveau admis dans le backend.

`alpha.0.55` introduit une attente HTTP générique bornée à 60 secondes, avec polling toutes les 2 secondes et conservation du dernier diagnostic. Elle ne rejoue que des requêtes GET idempotentes de readiness/configuration. Le scénario HA l’emploie après le basculement PostgreSQL, après le retrait contrôlé d’un nœud Server et après le retrait contrôlé d’un nœud Web. Une non-régression exécutable reproduit deux réponses HTTP 503 après le failover DB avant convergence, puis exige que le scénario poursuive les bascules Server et Web. Aucun seuil HA, privilège, quota ou invariant de réplication n’est abaissé.

## 2.0.0-alpha.0.54 — PRO HA writer convergence and predecessor Web theme adaptation

**Statut : correction HA et baseline Web implémentées ; revalidation Docker Desktop/PowerShell cible requise.**

La validation réelle de `alpha.0.53` sous Docker Desktop/PowerShell a confirmé le smoke PRO complet : `streaming=2`, `synchronous=1`, `Server=4`, `Web=2`. Le scénario `ha-smoke` a ensuite arrêté `postgres-1` et obtenu une élection Patroni, mais la première requête SQL via le routeur writer HAProxy a échoué transitoirement avec `server closed the connection unexpectedly`. Le bloc `finally` a correctement redémarré l’ancien primaire, mais le test traitait à tort ce délai de convergence HAProxy comme un échec définitif.

`alpha.0.54` distingue désormais explicitement l’élection Patroni de la disponibilité du writer HAProxy. Après l’élection d’un primaire différent, le smoke HA effectue uniquement un `SELECT 1` idempotent, toutes les deux secondes, pendant au plus 60 secondes. Il conserve le dernier diagnostic et échoue explicitement si le writer ne converge pas. Aucune mutation SQL n’est rejouée, aucun seuil de réplication n’est abaissé et les contrôles ultérieurs Server/Web restent obligatoires. Une non-régression exécutable simule exactement deux fermetures de connexion HAProxy avant une troisième tentative réussie et exige la poursuite des bascules Server et Web.

Le Web reprend uniquement le thème visuel retrouvé dans l’archive source précédente : le fichier historique `src/applications/web/public/assets/bootstrap.css` (1 804 octets, SHA-256 `07b9b698d639a8bd9b2ce758e51754be4d33ca03cb5a692cc566319f3cc9f1a9`). Malgré son nom, ce fichier n’était pas le framework Bootstrap mais la couche de thème historique à palette IONOS. `alpha.0.54` en conserve exclusivement la palette, la typographie, les surfaces, le focus, le mode sombre et le breakpoint mobile dans `infranexum-theme.css`, chargé après Bootstrap 5.x vendored localement. Aucun template HTML, runtime JavaScript ni composant métier du produit prédécesseur n’est importé. Le shell reste sans CDN, responsive et accessible.

## 2.0.0-alpha.0.53 — PRO HA replication-observability privilege repair

**Statut : correction implémentée et testée hors moteur Docker ; runtime Docker Desktop à revalider.**

Le runtime Docker Desktop de `alpha.0.52` atteint les quatre bindings PRO attendus puis `smoke`/`ha-smoke` échouent avec `Expected two streaming standbys; observed 0`. Le cluster avait pourtant déjà franchi `db-bootstrap`, qui exige deux standbys `streaming` et un standby `sync`/`quorum` avec la connexion superutilisateur. La divergence provenait du smoke : ses requêtes `pg_stat_replication` étaient exécutées avec le rôle applicatif `infranexum`. PostgreSQL restreint les détails des vues statistiques dynamiques pour les sessions appartenant à d’autres rôles ; les colonnes protégées peuvent être nulles pour un utilisateur ordinaire, ce qui rend un filtre `state='streaming'` impropre à un diagnostic HA effectué avec le compte applicatif.

Les wrappers POSIX et PowerShell séparent désormais explicitement les requêtes applicatives des diagnostics administratifs. `db_scalar`/`Invoke-DatabaseScalar` conservent le rôle `infranexum`; `admin_db_scalar`/`Invoke-DatabaseAdminScalar` utilisent le compte bootstrap `postgres` uniquement pour lire l’état de réplication via le writer privé. Aucun `GRANT pg_monitor` ni `GRANT pg_read_all_stats` n’est ajouté au rôle applicatif : le principe de moindre privilège reste inchangé. Le test de reprise après failover utilise le même chemin administrateur pour attendre le retour à deux standbys.

La non-régression comprend un contrat statique sur les deux wrappers et un smoke POSIX exécutable dont le faux PostgreSQL reproduit exactement la visibilité observée : le rôle applicatif retourne `0` pour `pg_stat_replication`, tandis que le chemin administrateur retourne `2` standbys et `1` synchrone ; le smoke doit alors réussir sans élargir les privilèges applicatifs.

Validations locales de l’incrément : contrat Compose **48/48** ; Source Integrity **45/45** et 0 violation sur snapshot Git staged avec checksums ; Architecture fonctionnelle **52/52** et Architecture-as-Code **PASS** ; Toolchains **25/25** et 0 violation ; migrations **34/34** et 0 violation ; runtime Web local **27/27**, couverture lignes **99,65 %**, branches **98,28 %**, fonctions **100 %**, process smoke **PASS** ; Agent avec Go local 1.23.2 : `vet` et tests race **PASS**, couverture **98,4 %**. Le toolchain cible Go 1.26.5 n’a pas pu être téléchargé dans l’environnement isolé ; PowerShell et Docker Desktop ne sont pas disponibles localement et restent à revalider sur Windows.


## 2.0.0-alpha.0.52 — PRO Web HA Compose schema repair

**Statut : schéma Compose corrigé ; runtime Docker Desktop exécuté jusqu’au smoke, qui a exposé le défaut de visibilité statistique corrigé en alpha.0.53.**

Le runtime Docker Desktop de `alpha.0.51` rejetait le modèle Compose avant toute opération car les clés `interval`, `timeout`, `retries` et `start_period` du routeur `web` étaient placées au niveau du service au lieu d’être imbriquées sous `healthcheck`. `alpha.0.52` a corrigé cette structure sans modifier la sémantique de readiness ni la politique de redémarrage. Un contrat dédié vérifie que ces quatre propriétés restent sous `services.web.healthcheck`.

Sur Windows/Docker Desktop, `alpha.0.52` a ensuite atteint une topologie entièrement déclarée saine et les quatre bindings loopback attendus, mais `smoke`/`ha-smoke` ont échoué sur la lecture de `pg_stat_replication` avec le rôle applicatif. Cette preuve runtime est conservée comme diagnostic historique et mène directement à la correction `alpha.0.53`.


## 2.0.0-alpha.0.51 — PRO Web HA developer topology

**Statut : implémentation et contrats statiques/local Web réalisés ; runtime Docker Desktop PRO à valider.**

Le banc Docker/Compose PRO complète désormais la séparation Server/Web : deux nœuds Web privés (`web-1`, `web-2`) exécutent le runtime Web sous un utilisateur non privilégié et sont servis par un HAProxy dédié publié uniquement sur `127.0.0.1:8081`. Le routeur Server reste sur `127.0.0.1:8080` et les nœuds Web reçoivent une configuration runtime explicite qui cible l’API Server sans publier leurs ports individuels. Le démarrage `up` attend désormais le routeur Web, ce qui entraîne et vérifie toute la chaîne etcd → Patroni/PostgreSQL → migrations → Server → Web.

L’image Web utilise Node.js 24.18.1, conformément au catalogue de toolchains du dépôt, avec archives amd64/arm64 et SHA-256 épinglés. Le Dockerfile vérifie le checksum avant extraction, vérifie la version Node installée, n’embarque ni gestionnaire de paquets ni dépendance npm de production et exécute le runtime en UID/GID dédiés. Les probes utilisent `/health/ready`; le routeur Web n’accepte qu’un backend prêt.

`smoke` vérifie désormais les 3 nœuds PostgreSQL/Patroni, les 4 nœuds Server, les 2 nœuds Web, les trois routeurs loopback, la readiness Web et le contrat `/runtime-config.json`. `ha-smoke` conserve le failover/rejoin PostgreSQL et ajoute une panne bornée d’un nœud Server puis d’un nœud Web, en exigeant que leurs routeurs respectifs restent disponibles et que les nœuds arrêtés rejoignent ensuite le cluster. Restore et rollback arrêtent également la couche Web avant toute opération incompatible sur les données.

Validations locales de l’incrément : contrat Compose **45/45** ; runtime Web exécuté avec le Node local disponible : **27/27**, couverture lignes **99,65 %**, branches **98,28 %**, fonctions **100 %**, smoke process **PASS** ; Source Integrity **45/45**, couverture **100 %**, 0 violation ; Toolchains **25/25**, couverture **99 %**, 0 violation ; Archive Compatibility **12/12**, couverture **100 %**, 0 violation ; Architecture-as-Code CLI **PASS**, 0 violation ; tests fonctionnels Architecture fractionnés **52/52 PASS**. Le runner de couverture Architecture agrégé a dépassé 180 s : sa couverture n’est donc pas déclarée validée. L’exécution avec Node.js 24.18.1 exact et le runtime Docker Desktop restent **NON EXÉCUTÉS** dans l’environnement de génération ; le build Docker cible constitue la preuve d’installation/checksum du runtime Node épinglé sur la plateforme utilisateur.


## 2.0.0-alpha.0.50 — PowerShell Compose stdout/stderr capture isolation

**Statut : correction implémentée et testée statiquement ; runtime PowerShell/Docker Desktop à revalider.**

Après la correction du forwarding natif, `docker compose run` pouvait encore écrire les messages de cycle de vie de son conteneur éphémère (`Creating`, `Created`) sur stderr. `Invoke-ComposeCapture` fusionnait alors stderr avec stdout ; une requête SQL scalaire retournant par exemple `0` devenait une chaîne multi-lignes impossible à convertir en `[int]`.

Le wrapper lance désormais Docker via `System.Diagnostics.ProcessStartInfo`, redirige stdout et stderr indépendamment, retourne uniquement stdout au consommateur et conserve stderr comme diagnostic d’erreur. Le code de sortie natif reste contrôlé explicitement. Un test de non-régression injecte les messages `Creating/Created` et exige qu’ils ne puissent plus contaminer la valeur SQL capturée. Aucun invariant HA, secret, volume ou contrat de service n’est modifié.


## 2.0.0-alpha.0.49 — PowerShell Compose native argument forwarding repair

**Statut : correction implémentée et testée statiquement ; runtime PowerShell/Docker Desktop à revalider.**

Le runtime Docker Desktop `alpha.0.48` atteint les bindings PRO attendus (`127.0.0.1:5432`, `127.0.0.1:5433`, `127.0.0.1:8080`) et le wrapper vérifie tous les services du cluster avant d’échouer au premier appel SQL. La cause est l’advanced-function parameter binding de `Invoke-ComposeCapture`: l’option native Docker `-e` est résolue par PowerShell comme abréviation ambiguë des paramètres communs `-ErrorAction` et `-ErrorVariable`, donc Docker ne reçoit jamais l’argument. `ha-smoke` échoue au même endroit parce qu’il commence par exécuter `smoke`.

`Invoke-Compose` et `Invoke-ComposeCapture` utilisent désormais exclusivement le vecteur automatique `$args`, sans bloc `param` ni `ValueFromRemainingArguments`. Les options natives restent ainsi opaques au binder PowerShell et sont transmises telles quelles au CLI Docker. Cette correction couvre également les options courtes déjà utilisées ailleurs (`-T`, `-q`) et les futurs switches natifs, sans modifier la topologie, les secrets, les volumes ou les invariants HA.

Un test de non-régression interdit la réintroduction d’un paramètre de forwarding sur ces wrappers et exige l’utilisation de `@args`, tout en conservant explicitement les chemins `-e` de requête SQL et de rollback. La suite de contrat Compose passe désormais **40/40** tests hors moteur Docker. L’exécution cible PowerShell/Docker Desktop reste obligatoire avant de considérer `smoke` et `ha-smoke` validés.

Validations locales de l’incrément : Compose **40/40**, Source Integrity **45/45** avec couverture **100 %** et 0 violation, Archive Compatibility **12/12** avec couverture **100 %**, Toolchains **25/25** avec couverture **99 %**, Architecture-as-Code CLI **PASS** avec 0 violation. La suite de tests Architecture a été interrompue par timeout avant son résultat final sans assertion en échec observée ; elle n’est donc pas déclarée validée. PowerShell et Docker ne sont pas disponibles dans l’environnement de génération.


## 2.0.0-alpha.0.48 — PRO HA database bootstrap repair

**Statut : correction implémentée et testée hors moteur Docker ; runtime Docker Desktop PRO à revalider.**

Le démarrage réel de `alpha.0.47` a confirmé etcd et les trois nœuds Patroni/PostgreSQL sains, puis `db-bootstrap` a terminé avec le code 1. La cause est le passage de `PASSWORD :'db_password'` à `psql --command`. L’interpolation `:'variable'` appartient au client `psql` ; un argument `-c/--command` doit être directement analysable par le serveur. Le bootstrap lit désormais les commandes de création/modification du rôle sur stdin, importe le secret via `\getenv` depuis l’environnement du processus et ne place plus le mot de passe dans les arguments de processus.

Le bootstrap ne refuse plus immédiatement un cluster dont HAProxy est prêt quelques instants avant la convergence de la réplication. Il attend de façon bornée jusqu’à 60 tentatives les deux standbys `streaming` puis au moins un standby `sync`/`quorum`, avec diagnostic de la dernière valeur observée et code 69 en cas de non-convergence. Le comportement reste fail-closed et aucune exigence PRO HA n’est abaissée.

Le test de non-régression exécutable reproduit le défaut `alpha.0.47` avec un faux `psql` strict : l’ancien script sort en code 1 lorsque la syntaxe psql-only est envoyée via `--command`; le script corrigé termine en code 0, transmet le SQL par stdin et vérifie que le secret n’apparaît pas dans les arguments `psql`. La suite Compose passe désormais 39/39 tests hors moteur Docker.


## 2.0.0-alpha.0.47 — hosted JDK25 and PRO HA stabilization

**Statut : corrections implémentées ; hosted JDK25/JaCoCo et runtime Docker Desktop PRO à revalider.**

Le premier run JDK25 complet a isolé trois défauts de qualité Java : `JdbcTaskStore` déclarait dix-neuf colonnes dans l’INSERT `worker_task` mais seulement dix-huit expressions après l’ajout de `correlation_id`; le module Core Workers exécutait 50/50 tests mais ne couvrait que 96 % des branches JaCoCo pour un seuil contractuel de 98 %; et le contexte minimal de `ClockBeanQualificationTest` ne fournissait plus le `SensitiveDataRedactor` devenu dépendance obligatoire de `EntitlementExceptionHandler`. La correction aligne l’arité SQL avec un contrat de forme directement exécuté par le smoke JDBC, étend les scénarios de branches Workers sans exclusion ni réduction du seuil, et remet la fixture Spring en cohérence avec le constructeur réel.

Le même run a montré qu’un `OtlpMeterRegistry` était créé par défaut et tentait d’exporter vers localhost malgré la politique d’export OTLP opt-in. `management.otlp.metrics.export.enabled` est désormais explicitement désactivé par défaut, indépendamment du tracing, avec un test Spring exigeant l’absence d’`OtlpMeterRegistry` dans le runtime nominal.

Côté PRO HA, Patroni pouvait cloner correctement un standby puis PostgreSQL refusait de démarrer parce que le volume Docker fournissait `PGDATA` avec un mode trop permissif. L’entrypoint fixe désormais propriétaire et mode `0700` sur le répertoire de données avant bootstrap/rejoin, sans supprimer ni réécrire les fichiers du cluster. Un contrat Compose garantit que cette réparation précède l’exécution de Patroni.


## 2.0.0-alpha.0.46 — etcd distroless healthcheck repair

**Statut : correction implémentée ; runtime Docker Desktop PRO à revalider.**

Le premier démarrage réel de la topologie PRO `alpha.0.45` a montré les trois membres etcd capables de rejoindre le cluster, d’élire un leader et de passer le service gRPC en `SERVING`, tandis que Compose déclarait néanmoins `etcd-3` `unhealthy`. La cause est le healthcheck `CMD-SHELL` : l’image officielle etcd 3.6.14 est distroless et ne fournit pas `/bin/sh`. Les trois probes etcd utilisent désormais directement `/usr/local/bin/etcdctl` en exec-form `CMD`, sans shell, redirection ni dépendance au `PATH`. La commande `endpoint health` conserve une vérification active nécessitant une proposition etcd réussie et protège donc le quorum, pas seulement l’ouverture du port TCP. Un contrat Compose interdit toute réintroduction de `CMD-SHELL` sur les membres etcd.


## 2.0.0-alpha.0.45 — PRO Docker HA topology

**Statut : implémentation statique/local hors Docker réalisée ; runtime Docker Desktop PRO NON EXÉCUTÉ dans l’environnement de génération.**

Le banc Docker/Compose de développement modélise désormais la topologie PRO `single_cluster` : trois PostgreSQL 17.10 gérés par Patroni 4.1.4 et un DCS etcd à trois membres, avec au moins un standby synchrone strict et un second réplica, ainsi que quatre nœuds Server `REGIONAL` derrière HAProxy. Le routeur PostgreSQL distingue writer primaire et replicas, et seuls les routeurs sont publiés en loopback sur la station de développement.

Le bootstrap BDD attend deux replicas streaming et au moins un standby `sync`/`quorum` avant de créer le rôle et la base applicatifs. `smoke` vérifie toute la topologie ; `ha-smoke` arrête volontairement le primaire Patroni, exige une nouvelle élection bornée, vérifie la reprise du writer et la readiness Server, redémarre l’ancien primaire puis exige son retour et deux replicas streaming. Backup, restore et rollback utilisent le writer stable et arrêtent les quatre nœuds Server pour les opérations incompatibles.

Le runtime Entitlements est désactivé uniquement dans ce banc de topologie afin de ne pas embarquer de manifeste d’activation client ; cette dérogation n’est jamais un mode de production. Le cluster Web PRO reste volontairement différé.

## 2.0.0-alpha.0.44 — Sensitive observability redaction

**Statut : politique de masquage systématique implémentée ; epic PGM-12-E01 NON TERMINÉ.**

Le Server applique désormais une politique centrale `SensitiveDataRedactor` au dernier point précédant la sérialisation des logs structurés ECS. Le customizer Spring Boot traite chaque valeur `String`, y compris message, MDC et stack trace, masque complètement les champs dont le chemin est credential-bearing et neutralise les encodages courants de mots de passe, secrets, tokens, Authorization/Cookie, user-info URI, JWT et blocs de clé privée. Le format ECS est désormais imposé afin qu’une variable d’environnement ne puisse pas contourner le customizer, et la taille/profondeur des stack traces structurées est bornée.

Les réponses RFC Problem Entitlements passent par la même politique avant émission HTTP. Les spans manuels restent limités à deux attributs allowlistés (`infranexum.worker.task.type`, `infranexum.correlation.id`) ; les paramètres de tâches, headers et secrets ne sont pas admis comme attributs. Un smoke Java pur-JDK injecte volontairement plusieurs formes de secrets et doit prouver leur disparition tout en conservant les identifiants opérationnels sûrs. Les dashboards/runbooks et la certification OTLP cible restent à fermer.

## 2.0.0-alpha.0.43 — OpenTelemetry tracing foundation

**Statut : tranche tracing PGM-12-E01 implémentée ; epic NON TERMINÉ.**

Le Server intègre `spring-boot-starter-opentelemetry` sous la BOM Spring Boot 4.1.0. La propagation réseau est limitée à W3C Trace Context, le baggage Micrometer est désactivé et le mapping automatique des variables `OTEL_*` est coupé afin que le contrat `INFRANEXUM_OTEL_*` reste déterministe. L'export OTLP est désactivé par défaut ; lorsqu'il est activé, les files/batches, timeouts et limites de spans restent bornés.

`WorkerCorrelationBridge` ouvre désormais un span `CONSUMER` à nom fixe autour de chaque handler durable et n'ajoute que le type de tâche validé et l'UUIDv7 de corrélation déjà persisté. Les paramètres de tâche, credentials, headers bruts et MDC arbitraire ne deviennent pas des attributs de span. Le contexte W3C parent n'est pas persisté dans `worker_task` dans cet incrément : après redémarrage ou changement de nœud, le lien durable reste l'UUIDv7 de corrélation. La politique globale de masquage, les dashboards/runbooks et la certification OTLP cible restent à fermer avant PGM-12-E01.


## 2.0.0-alpha.0.42 — durable Worker correlation propagation

**Statut : tranche asynchrone PGM-12-E01 implémentée ; epic NON TERMINÉ.**

Le `TaskScheduler` Server capture désormais le UUIDv7 validé présent dans le contexte de corrélation et le transmet au TaskStore comme métadonnée durable. La migration paire `0009-core-worker-correlation` ajoute `correlation_id` nullable à `worker_task` avec contrainte UUIDv7 PostgreSQL/Oracle ; les tâches historiques restent compatibles avec `NULL`. Le replay idempotent conserve la corrélation de la création initiale.

`TaskExecutionContext` expose la corrélation persistée. `WorkerCorrelationBridge` la lie au MDC uniquement pendant l'exécution du handler puis restaure systématiquement l'état précédent du thread Worker. Aucun header brut, SecurityContext, credential ni map MDC arbitraire n'est propagé. Les smokes Core Workers et JDBC couvrent la persistance/restitution ; un test Server JUnit couvre le pont MDC réel et reste à confirmer sous le reactor JDK 25. OpenTelemetry, politique globale de masquage et dashboards restent à implémenter.


## 2.0.0-alpha.0.41 — Docker Desktop host-port publication

Le réseau Compose de développement n’est plus `internal: true`. Les runs Docker Desktop ont démontré que les conteneurs restaient `Healthy` mais que les publications hôte disparaissaient (`5432/tcp` et `8080/tcp` seulement) et que `docker compose port` échouait. Le réseau `backend` est désormais un bridge non interne, tandis que les deux publications restent strictement liées à `127.0.0.1`. Les services techniques `secret-init`, `migrate` et `rollback` ne publient aucun port. Cette décision concerne uniquement l’outillage Docker de développement/test ; le déploiement produit reste standalone bare metal/VM.


## 2.0.0-alpha.0.41 — Docker Desktop port rendering and PowerShell smoke repair

**Statut : correction implémentée ; validation Docker Desktop cible requise.**

Le binding long Compose introduit en alpha.0.39 a exposé sous Docker Desktop/Compose Windows un défaut runtime où `docker compose port` retournait `invalid IP:0`. Les publications développeur utilisent désormais la syntaxe courte explicite `127.0.0.1:HOST:CONTAINER` pour PostgreSQL et le Server. Le smoke conserve une vérification du binding réellement appliqué ; si `docker compose port` échoue, il utilise `docker inspect` sur le conteneur du service puis refuse tout `HostIp` autre que `127.0.0.1`. Le lanceur PowerShell corrige également l’interpolation invalide `$ContainerPort:` en `${ContainerPort}:` et un test statique interdit désormais toute nouvelle interpolation `$name:` ambiguë hors variables de scope `$env:`.

## 2.0.0-alpha.0.39 — Docker loopback port publication and smoke diagnostics

**Statut : supersédé par alpha.0.40 pour compatibilité Docker Desktop/PowerShell.**

Le runtime Compose de développement a introduit la publication explicite de PostgreSQL et du Server sur la loopback hôte et le diagnostic préalable de l’état des services. Le binding long utilisé dans cet incrément s’est toutefois révélé incompatible avec `docker compose port` dans le runtime Docker Desktop observé (`invalid IP:0`) et le script PowerShell contenait une interpolation `$ContainerPort:` non parseable. Ces deux défauts sont fermés en alpha.0.40.

## 2.0.0-alpha.0.38 — PGM-12-E01 HTTP correlation and structured logging foundation

**Statut : première tranche PGM-12-E01 implémentée localement ; epic NON TERMINÉ.**

Le Server établit désormais un contexte de corrélation HTTP avant MVC et Actuator. `X-Correlation-ID` est absent ou un UUIDv7 canonique : en l’absence du header, InfraNexum génère un UUIDv7 ; une valeur malformée, non-v7 ou non canonique est rejetée en HTTP 400 sans réflexion de la valeur reçue. L’identifiant validé est renvoyé sur la réponse et lié au MDC `correlation_id` avec restauration systématique en `finally`, ce qui évite les fuites entre threads servlet réutilisés. `EntitlementExceptionHandler` consomme ce contexte validé au lieu de relire l’entrée brute.

Les logs console utilisent par défaut le format structuré ECS natif de Spring Boot et exposent les métadonnées service/version/environnement/nœud. Deux compteurs Micrometer à cardinalité fixe suivent les corrélations générées et rejetées. Les smokes Compose vérifient la propagation d’un UUIDv7 valide, le rejet 400 d’une valeur invalide, l’absence de réflexion de cette valeur et la génération d’un nouvel UUIDv7 serveur. OpenTelemetry, propagation asynchrone/background, politique de masquage systématique et dashboards restent à implémenter avant fermeture de PGM-12-E01.

## 2.0.0-alpha.0.37 — Workers operational readiness and metrics

**Statut : implémenté localement ; certification Docker/JDK25 du nouvel observability path et Oracle live restent requises.**

PGM-02-E07 reçoit sa couche d’exploitation manquante. `TaskWorkerPool` expose désormais un `WorkerPoolSnapshot` cumulatif et sans donnée sensible : état, concurrence configurée, boucles réellement vivantes, exécutions actives, tâches claimed/succeeded/retried/failed/cancelled/abandoned et erreurs fatales de boucle. Une exception runtime non gérée dans une boucle de worker est comptabilisée avant que l’`ExecutorService` ne la masque dans son `Future`; la perte d’une boucle rend immédiatement `snapshot.ready()` faux. Le Server publie un `WorkerHealthIndicator` toujours présent, y compris lorsque Workers est explicitement désactivé, et le groupe Actuator `readiness` inclut `workers`. Les métriques Micrometer restent à cardinalité fixe (`enabled`, `ready`, `capacity`, `live`, `active`, compteurs de résultats et erreurs fatales de boucle) et l’endpoint `metrics` est exposé pour l’exploitation. Les smokes Docker PowerShell/POSIX exigent maintenant la readiness et la présence de `infranexum.workers.ready`.



## 2.0.0-alpha.0.36 — explicit managed Spring scheduling runtime

**Statut : correction implémentée ; certification Docker/JDK25 cible requise.**

Le premier démarrage complet d’`alpha.0.35` a confirmé le Server opérationnel jusqu’au `DispatcherServlet`, tout en révélant que `@EnableScheduling` utilisait encore le fallback local de Spring (`No TaskScheduler/ScheduledExecutorService bean found for scheduled processing`). Ce fallback mono-thread fonctionne, mais il n’est ni dimensionné ni explicitement gouverné par InfraNexum. Le Server fournit désormais un véritable `ThreadPoolTaskScheduler` nommé `taskScheduler`, borné et configurable via `infranexum.scheduling.*`, avec horloge plateforme UTC, politique remove-on-cancel et arrêt borné. Le scheduler métier Workers est renommé `workerTaskScheduler` afin que le nom Spring canonique `taskScheduler` appartienne exclusivement au scheduler d’infrastructure. Des tests Spring et Architecture empêchent le retour du fallback implicite ou la réappropriation du nom par un autre bean.

## 2.0.0-alpha.0.35 — canonical platform Clock for framework auto-configuration

**Statut : correction implémentée ; certification Docker/JDK25 cible requise.**

Le démarrage Docker `alpha.0.34` a confirmé que les injections applicatives Entitlements étaient correctement qualifiées, puis a exposé un second niveau de collision : `Spring Modulith MomentsAutoConfiguration` résout un `Clock` unique par type et échoue lorsque seuls `entitlementClock` et `workerClock` sont présents. Le Server fournit désormais un `platformClock` UTC unique marqué `@Primary`. Les bounded contexts conservent leurs horloges explicitement qualifiées ; aucune horloge métier n’est rendue primaire. Une non-régression Spring charge l’auto-configuration Moments en présence des trois beans et vérifie que la résolution par type sélectionne exclusivement `platformClock`. Le gate Architecture interdit également qu’un autre Clock de contexte devienne `@Primary`.

## 2.0.0-alpha.0.34 — Entitlements whole-second temporal repair

**Statut : implémenté localement ; certification Docker/JDK25 cible encore requise.**

Le bootstrap PostgreSQL Compose persiste maintenant `core_installation_identity.created_at` à la seconde entière avec `date_trunc('second', CURRENT_TIMESTAMP)`. La migration appariée `0008-core-entitlement-time-precision` normalise uniquement ce timestamp d’installation non signé lorsqu’il provient d’alpha.0.32 ; elle refuse toute réécriture silencieuse d’un état Entitlements, proof HMAC ou manifeste d’activation contenant des fractions de seconde. PostgreSQL et Oracle reçoivent ensuite des contraintes de précision seconde entière sur les colonnes temporelles participant aux invariants Entitlements. Le mapping JDBC valide ces timestamps dès la frontière persistence et produit une erreur contextualisée sur la colonne fautive.

## 2.0.0-alpha.0.32 — UUIDv7 installation identity repair

**Statut : correction implémentée ; validation Docker réelle à confirmer sur Docker Desktop après reconstruction des images.**

Le premier démarrage Compose réel a démontré que `docker/migrate-postgresql.sh` persistait `core_installation_identity.installation_id` via `/proc/sys/kernel/random/uuid`, donc en UUIDv4, alors que `DomainIdentifier` impose UUIDv7. Le Server échouait fail-closed pendant l’initialisation Entitlements avant le démarrage Tomcat.

Le bootstrap Compose construit maintenant un UUIDv7 RFC 9562 à partir de l’horloge PostgreSQL et de 74 bits aléatoires. La migration appariée `0007-core-installation-uuidv7` répare automatiquement l’identité UUIDv4 introduite par alpha.0.31 uniquement lorsqu’aucun état Entitlements, proof d’intégrité ni manifeste d’activation ne la référence ; sinon elle refuse la migration. Une contrainte base empêche ensuite toute réintroduction d’un identifiant d’installation non UUIDv7. Le mapping JDBC traduit également une valeur persistée invalide en `SQLException` contextualisée.

## 2.0.0-alpha.0.31 — Docker PostgreSQL portability and diagnostics repair

**Statut : correction implémentée ; exécution Docker réelle NON EXÉCUTÉE dans l’environnement de génération faute de moteur Docker.**

Le défaut observé sous Docker Desktop est corrigé à la cause : les scripts `migrate-postgresql.sh` et `rollback-postgresql.sh` n’utilisent plus `echo` pour produire des méta-commandes `psql`. POSIX laisse le traitement des antislashs par `echo` dépendre de l’implémentation ; le conteneur Alpine/BusyBox pouvait donc produire un contrôle commençant par `\\set`, que `psql` interprétait comme une commande `\`. Les lignes de contrôle sont désormais produites exclusivement avec `printf '%s\n'`, avec un seul antislash attendu.

Un `compose.yaml` de commodité est ajouté à la racine et inclut le modèle canonique `docker/compose.yaml`, ce qui permet les commandes `docker compose ...` directement depuis la racine du dépôt sans dupliquer la topologie. Les lanceurs PowerShell/POSIX acceptent désormais un ou plusieurs noms de services pour `logs`, par exemple `logs migrate`; la documentation distingue explicitement les noms de services Compose des noms de conteneurs générés.

Les tests de non-régression bloquent tout retour d’un rendu des méta-commandes `psql` via `echo`, vérifient le forwarding des services dans le lanceur POSIX et protègent le loader Compose racine.

## 2.0.0-alpha.0.30 — root Docker developer runtime

**Statut : implémenté ; exécution Docker réelle NON EXÉCUTÉE dans l’environnement de génération faute de moteur Docker.**

Le dossier Docker est désormais versionné directement à la racine sous `docker/`, jamais sous `src/`. Il contient `server.Dockerfile`, `postgres-tools.Dockerfile`, `compose.yaml`, les scripts de secrets/migration/rollback/entrypoint, ainsi que les lanceurs PowerShell et POSIX. Le Makefile expose `compose-config`, `compose-build`, `compose-up`, `compose-smoke`, `compose-logs`, `compose-down`, `compose-backup`, `compose-restore`, `compose-rollback` et `compose-reset`. Les opérations destructives restent fail-closed et le rollback laisse le Server arrêté.

Cette présence dans le dépôt ne modifie pas le contrat de déploiement produit : `src/` reste la frontière des sources de la solution déployable et les installations de production ciblent bare metal ou VM sans dépendance à Docker/Compose. Le gate Source Integrity inventorie le nouveau dossier racine et continue de refuser tout Dockerfile/Compose sous `src/`. Le gate Toolchains exige les commandes Make et la topologie racine tout en interdisant de transformer Docker en dépendance du pipeline produit.

## 2.0.0-alpha.0.29 — standalone deployment boundary and Server Workers runtime

Docker/Compose is removed from the canonical InfraNexum product source and product CI. It remains available only as a separate local developer companion extracted into the Git-ignored `.infranexum-dev/` workspace. The product deployment target is standalone bare metal or VM; no container runtime is a production prerequisite. Source Integrity and Toolchains gates reject reintroduction of product Compose wiring.

PGM-02-E07 advances with Server-owned Workers composition: explicit MEMORY/PostgreSQL/Oracle `TaskStore` beans, immutable `TaskHandlerRegistry`, UUIDv7 scheduler identity, bounded retry policy, validated timings/concurrency and Spring-managed `TaskWorkerPool` start/close lifecycle. Target JDK 25/JaCoCo and Oracle live proofs remain required.

### 2.0.0-alpha.0.28 — JDBC coverage, Server bootstrap and Compose runtime

Le run JDK 25/PostgreSQL `alpha.0.27` confirme 41/41 tests JDBC sans skip mais laisse le module à 94 % lignes / 81 % branches. La correction ajoute des scénarios déterministes sur les lectures d’activation et de révocation, les représentations JDBC, documents LOB invalides, états persistés corrompus, transactions interrompues, rollback/commit/autocommit en échec, propagation des causes, fencing, idempotence et invariants de chaîne Audit. Le harnais strict local exécute 33 scénarios JDBC et le diagnostic bytecode ne laisse plus aucune décision source explicite non exercée dans l’adaptateur ; cette mesure reste diagnostique et ne remplace pas JaCoCo. Les seuils JaCoCo restent strictement à 98 % lignes et 98 % branches, sans exclusion. Le workflow publie désormais les lignes/branches manquées via `tools/jacoco_gaps.py` lorsqu’un module reste sous le seuil.

Le même run a révélé trois défauts Server : `application.yaml` matérialisait les maps optionnelles `dependencies`/`quota-overrides` en scalaires vides sous Spring Boot 4 ; le root application enregistrait directement `ActivationRuntimeProperties` appartenant au module interne `platform.entitlements`, ce que Spring Modulith refuse ; et une fixture Pro Advanced utilisait encore `iam.users.max=250` alors que le catalogue impose désormais au moins 500. Les maps vides sont maintenant laissées absentes et normalisées par `PlatformCapabilityProperties`, les propriétés d’activation sont enregistrées par `ActivationRuntimeConfiguration`, et la fixture quota est réalignée.

Docker Compose avait été introduit à ce stade pour la topologie Server + PostgreSQL ; ce choix est **supersédé par alpha.0.29**, qui le retire du périmètre produit après clarification de la cible standalone bare metal/VM. `src/deployment/docker/compose.yaml` fournit `secret-init`, PostgreSQL 17, un migrateur one-shot checksumé avec verrou advisory et bootstrap idempotent de l’identité d’installation, le Server Java 25 non-root avec readiness, ainsi qu’un service maintenance de rollback. Les volumes `postgres-data`, `runtime-secrets` et `integrity-proof`, le réseau backend interne, les targets `compose-up/down/smoke/backup/restore/rollback/reset` et leurs confirmations destructives sont fournis. Le contrat Compose est validé statiquement localement ; l’exécution Docker réelle reste à certifier par le job Ubuntu hébergé, Docker n’étant pas disponible dans l’environnement local.

## alpha.0.23 — deterministic Workers shutdown & Unix archive compatibility

**Statut : corrections implémentées ; confirmation Maven/JDK 25 hébergée encore requise.**

Cette passe corrige les deux défauts de `alpha.0.22` sans abaisser aucun seuil. `TaskWorkerPool.shutdown()` ne réarme plus l’interruption entre ses différentes attentes d’arrêt : il mémorise l’interruption, poursuit les opportunités de nettoyage bornées de tous les executors, calcule l’état réel de terminaison, puis restaure le flag du thread appelant. Le test de régression n’attend plus un résultat dépendant du scheduler/JDK ; il vérifie désormais le contrat réel : arrêt forcé lorsque l’appelant est interrompu, terminaison effective des workers idle et restauration de l’interruption. Le scénario de non-terminaison reste couvert séparément par un handler volontairement non coopératif.

Le workflow Foundation reste exclusivement Unix/Linux. Le job Windows introduit auparavant est supprimé. La compatibilité d’extraction Windows de l’archive publiée est vérifiée sous Ubuntu par un nouveau gate `archive-compatibility` qui contrôle l’intégrité ZIP, la parité exacte avec l’index Git, une racine unique et courte, les budgets de chemins, les caractères et noms réservés Windows, les collisions insensibles à la casse et l’absence de liens symboliques. La protection ne dépend donc pas d’un runner Windows.

`tools/bootstrap-maven.ps1` est néanmoins corrigé pour les usages locaux éventuels sous Windows : `java -version` est lancé via `System.Diagnostics.Process` avec stdout/stderr séparés, code de sortie contrôlé et ressources libérées. La sortie normale de version sur stderr ne peut plus être promue en `NativeCommandError` lorsque `$ErrorActionPreference = 'Stop'`. Un gate Toolchains interdit explicitement la réintroduction du pattern fragile.

## alpha.0.22 — CI Workers Coverage & Cross-platform Path Repair

**Statut : correction implémentée localement ; validation Maven/JDK 25 hébergée encore requise.**

Cette correction ferme les deux défauts révélés par la CI `alpha.0.21` sans réduire les seuils qualité. Le validateur Source Integrity ne dépend plus de la sémantique `pathlib.Path` de l’OS hôte pour décider si un chemin d’inventaire est relatif : il valide simultanément les syntaxes POSIX et Windows et refuse chemins POSIX racinés, lecteurs Windows, UNC, backslashes, traversées `..`, segments non canoniques et retours ligne. Le scénario `/tmp/a` est donc rejeté de manière identique sur Linux et Windows.

Le module Core Workers conserve les seuils JaCoCo **98 % lignes et 98 % branches** sans exclusion. La suite couvre désormais les validations de capacité et de lease, conflits d’idempotence, récupération de leases expirés, fencing, checkpoint, annulation, retries, erreurs de persistence, heartbeat, shutdown, interruptions et branches de value objects. Deux durcissements de concurrence accompagnent ces tests : `TaskWorker.runOnce()` est sérialisé pour empêcher deux claims simultanés par le même worker, et `TaskWorkerPool.start()/shutdown()` partagent le même verrou de lifecycle afin d’éliminer la course NEW/RUNNING/TERMINATED.

Le harnais local strict JDK 21 compile le module et ses tests avec `-Xlint:all -Werror` et exécute **46/46 scénarios Workers**. La preuve exacte JaCoCo sous JDK 25 reste NON EXÉCUTÉE localement et doit être fournie par `./mvnw --batch-mode --no-transfer-progress verify` sur le runner cible.

## alpha.0.21 — Product Source Containment

**Statut : implémenté localement ; certification hébergée cible encore requise.**

Tous les espaces constituant réellement la solution sont désormais contenus sous `src/` : applications, composants, moteurs, provisioning, installateur, déploiement, distribution/migrations et SDK. Les tests Java, Go et Web sont physiquement externes sous `tests/`; `validation/`, `tools/`, `docs/` et `.github/` restent également hors du périmètre produit.

Les tests Java sont raccordés aux modules par des `testSourceDirectory` Maven explicites au niveau dépôt. Les tests Go same-package sont matérialisés uniquement dans un workspace temporaire isolé par `tools/materialize_go_tests.py`, ce qui préserve l'accès aux invariants non exportés sans réintroduire de tests sous `src/`. Les tests Web résident sous `tests/web/`.

Source Integrity bloque désormais toute réapparition d'un espace produit historique à la racine, tout test sous `src/`, toute racine Maven de tests qui ne cible pas `tests/`, ainsi que les références de release qui ne remontent pas correctement de `src/distribution/` vers `BASELINE.json` et `artifacts/validation/`. Le budget de chemins introduit en `alpha.0.20` reste inchangé : 120 caractères par chemin canonique et 80 par composant.

Les packages Java, coordonnées Maven, APIs, schémas de base, identifiants logiques des composants et contrats runtime restent inchangés. Cette passe est une migration physique et de gouvernance du dépôt ; elle ne remplace aucune fonctionnalité métier.

## alpha.0.20 — Repository Layout Hardening

À `alpha.0.20`, la structure physique du dépôt avait été aplatie afin d’éliminer le risque de dépassement de longueur de chemin observé lors de l’extraction Windows. `alpha.0.21` conserve cette réduction de profondeur mais replace tous les espaces produit sous `src/`, avec les tests et outils de validation à l'extérieur.

Les modules Java conservent leurs packages et coordonnées Maven et leurs racines produit courtes `main/` et `resources/`; les racines de tests sont externalisées sous `tests/java/...` depuis `alpha.0.21`. L’adaptateur JDBC est physiquement situé dans `src/components/adapters/jdbc`; son identifiant logique `components.adapters.persistence-jdbc`, son package `io.infranexum.adapters.persistence.jdbc` et l’artifact Maven `infranexum-adapter-persistence-jdbc` restent inchangés.

Le gate Source Integrity impose maintenant **120 caractères maximum par chemin relatif** et **80 caractères maximum par composant de chemin**. Le chemin canonique le plus long de cet incrément mesure **116 caractères**. Le préfixe de l’archive source est limité à `infranexum-<version>` et contrôlé par le même gate.

Le workflow Foundation ajoute un job Windows qui exécute Source Integrity sur le checkout, crée un `git archive` avec le préfixe court, l’extrait sous un préfixe temporaire artificiellement allongé puis compile le reactor Maven depuis cette extraction. Cette preuve hébergée reste requise avant certification complète.

## alpha.0.19 — Durable Workers Persistence / PGM-02-E07

**Statut de l’incrément : implémenté localement, certification cible partielle. Statut de l’epic PGM-02-E07 : NON TERMINÉ.**

`JdbcTaskStore` implémente le port durable du Core Workers pour PostgreSQL et Oracle avec transactions courtes, claim `FOR UPDATE SKIP LOCKED`, fencing `(task_id, lease_owner, lease_version)`, checkpoint atomique, annulation, retry sûr et récupération bornée des leases expirés par compare-and-set optimiste. Une course concurrente qui modifie déjà le lease est bénigne ; toute mise à jour de récupération touchant plusieurs lignes échoue immédiatement. Le retry automatique reste interdit pour `AT_MOST_ONCE`.

La migration appariée `0006-core-workers` ajoute les tables `worker_task` et `worker_task_parameter`, l’unicité `(task_type, idempotency_key)`, les contraintes de statut/lease/checkpoint et les indexes de claim/récupération. PostgreSQL utilise des champs texte bornés à 4096 caractères ; Oracle utilise des `CLOB` et des triggers pour les invariants dépendant du contenu LOB. Le rollback refuse toute suppression si une tâche durable existe.

La CI PostgreSQL 17/18 applique désormais `0006` et exécute `PostgreSqlJdbcTaskStoreTest`, dont un scénario de quatre workers réclamant 40 tâches sans double claim. Ce test live reste à confirmer par le runner hébergé. Oracle 19c/26ai reste également NON EXÉCUTÉ localement.

La fermeture de PGM-02-E07 exige encore la composition du `TaskStore`, du `TaskScheduler` et du `TaskWorkerPool` dans le Server, les propriétés validées, readiness/métriques et shutdown coordonné, puis la preuve Java 25/Spring et Oracle live.

## alpha.0.18 — Core Workers Foundation / PGM-02-E07

**Statut de l’incrément : implémenté localement, certification cible partielle. Statut de l’epic PGM-02-E07 : NON TERMINÉ.**

Le nouveau module `src/components/core/workers` fournit un ordonnanceur idempotent, un `TaskStore` de référence thread-safe et borné, un worker unitaire et un pool à concurrence fixe. Les contrats de sûreté couvrent les leases versionnés, le fencing des workers obsolètes, le heartbeat, les checkpoints, l’annulation coopérative, les retries uniquement pour les tâches déclarées `RETRY_SAFE` et le fail-closed `AT_MOST_ONCE`.

L’arrêt ne surdéclare jamais l’état : lorsqu’un handler ignore l’interruption, le délai d’arrêt reste borné mais le pool demeure `STOPPING` et `ShutdownReport.terminated=false` jusqu’à la terminaison réelle du thread.

Le smoke autonome Java couvre les scénarios critiques et passe **10/10 répétitions locales**. Les **30/30 scénarios JUnit-source** passent également dans un harnais comportemental JUnit-compatible sous OpenJDK 21. Un stress de correction de **2 000 tâches** se termine avec 2 000 succès et **0 double exécution** ; il ne constitue pas un benchmark de performance. Les seuils JaCoCo >=98% lignes/branches sont définis, mais leur exécution Maven/JUnit réelle sous Java 25 reste **NON EXÉCUTÉE** dans l’environnement local Java 21.

La fermeture de PGM-02-E07 exige encore : adaptateur JDBC durable PostgreSQL/Oracle, migration appariée, tests de concurrence live et intégration du lifecycle workers dans le Server.


## alpha.0.17 — validation du snapshot Git candidat

Le second log CI reçu après `alpha.0.16` montre **10 entrées d’inventaire absentes du checkout**, suivies de **5 imports Java non résolus**. Les dix fichiers sont pourtant présents dans l’archive `alpha.0.16` et ne correspondent à aucune règle `.gitignore`. Le défaut est donc à nouveau situé dans la matérialisation/staging du commit, pas dans le code Java ni dans l’inventaire.

La correction devient préventive : `source-integrity` peut maintenant reconstruire dans un répertoire isolé **l’index Git exact qui sera committé** via `git checkout-index`, puis rejouer l’intégralité du graphe de fermeture sur ce snapshot. Toute source inventoriée absente du candidat au commit est bloquée par `CHECK-SOURCE-STAGED-002`, même si le fichier existe encore dans le working tree.

Un hook versionné `.githooks/pre-commit` appelle `make source-integrity-precommit`. Ce target exécute les tests, impose le tracking Git, valide le snapshot staged, vérifie le manifeste SHA-256 des blobs staged et exécute `git diff --cached --check`. L’installation locale est explicite et idempotente avec `make source-integrity-hook-install`; la CI active également la validation staged après `actions/checkout`. Aucun contrôle existant n’est assoupli.
Le target pré-commit n’écrit aucun rapport persistant : ses fichiers de couverture et diagnostics sont temporaires puis supprimés. Le commit ne peut donc pas modifier silencieusement les preuves de validation ou invalider le manifeste SHA-256 de livraison.
Le contrôle d’intégrité est séparé en deux niveaux : `src/distribution/source-files.sha256` couvre le snapshot Git tracké à partir des **blobs immuables de l’index Git**, ce qui neutralise les conversions LF/CRLF de `.gitattributes`; `artifacts/validation/release-files.sha256` couvre les octets réellement présents dans le payload de l’archive, preuves de validation comprises. Un patch Git reste ainsi cohérent entre Windows et Linux sans dépendre de fichiers volontairement ignorés par Git.

Preuves locales `alpha.0.17` : **31/31 tests source-integrity, 100 % lignes/branches, inventaire 411 chemins, 0 violation sur le snapshot staged complet et son manifeste Git-blob SHA-256**. La reproduction exacte des 10 omissions du runner échoue avant commit avec **26 violations** lorsque le manifeste staged reste inchangé : 10 `CHECK-SOURCE-GIT-002`, 1 `CHECK-SOURCE-GIT-004` et 15 `CHECK-SOURCE-STAGED-002`, dont 10 absences d’inventaire et 5 imports Java non résolus. Même après régénération volontaire du manifeste sur l’index incomplet, le candidat reste refusé avec **25 violations** (10 tracking + 15 snapshot staged), ce qui prouve l’indépendance des barrières. Architecture-as-Code passe **29/29** avec **100 % lignes/branches** ; le gate toolchain passe **19/19** avec **99 %**. Les autres gates Python restent ≥98 %, les 8 smokes Java autonomes passent sous OpenJDK 21, le Web passe 27/27 et l’Agent passe localement sous Go 1.23.2 avec race detector et 98,4 % de couverture. Les toolchains cibles Java 25, Go 1.26.5 et Node 24.18.1/pnpm 11.17.0 restent à confirmer par la CI hébergée.

## alpha.0.16 — réparation de fermeture du dépôt

Le log CI fourni pour `alpha.0.15` exécute correctement les 17 tests du validateur puis échoue sur le checkout réel : 17 chemins de l’inventaire canonique sont absents de l’index Git et du disque du runner. Les mêmes 17 fichiers sont présents dans l’archive source `alpha.0.15`; la cause est donc un commit incomplet, pas une exclusion `.gitignore` ni une absence dans le bundle livré.

La livraison `alpha.0.16` conserve intégralement ces sources et ajoute une non-régression de diagnostic : une entrée absente à la fois du checkout et de l’index ne génère plus le doublon `CHECK-SOURCE-GIT-002`; `CHECK-SOURCE-INVENTORY-002` porte seul l’absence physique. `CHECK-SOURCE-GIT-002` reste bloquant pour tout fichier réellement présent mais non tracké. Aucun gate n’est assoupli.

La preuve locale de fermeture du dépôt a été exécutée dans un snapshot Git temporaire committé : **412 fichiers trackés**, arbre propre avant validation, **18/18 tests source-integrity**, **100 % lignes/branches**, puis `--require-git-tracking` avec **0 violation**. La reproduction exacte des 17 suppressions du log échoue volontairement avec **22 violations** (17 absences d’inventaire + 5 imports Java non résolus) et aucun doublon `CHECK-SOURCE-GIT-002`. La preuve hosted reste obligatoire après commit/push de ces fichiers.

## Sources concernées par le checkout incomplet

Les 17 chemins signalés par le runner sont conservés dans la livraison et dans `src/distribution/source-inventory.json` :

- 5 tests Server Entitlements (`ActivationAdministrationServiceTest`, `ActivationRuntimeConfigurationTest`, `EntitlementMutationInterceptorTest`, `EntitlementWebMvcConfigurationTest`, `EntitlementWebServerStartupGuardTest`) ;
- 6 sources JDBC (`FileIntegrityProofStore`, `JdbcActivationOperationalRepository`, `JdbcConnectionAccess`, `JdbcPersistenceException`, `JdbcRevocationRegistry`, `JdbcTransactionalEventStore`) ;
- 5 tests JDBC (`JdbcAuditJournalSmoke`, `JdbcAuditJournalTest`, `JdbcTransactionalEventStoreTest`, `PostgreSqlJdbcAuditJournalTest`, `PostgreSqlJdbcTransactionalEventStoreTest`) ;
- `EntitlementRuntimeUnavailableException`.

Le statut reste **NON TERMINÉ** tant que le workflow Foundation n’a pas confirmé ce commit sous les toolchains cibles.

## alpha.0.15 — intégrité du checkout

La passe transversale ajoute un inventaire canonique des fichiers, une vérification du tracking Git, la résolution des imports Java internes, la détection des collisions de casse et des modules Maven incomplets. Tous les jobs Foundation dépendent désormais de ce préflight afin qu’un fichier source présent dans une archive locale mais absent du commit échoue avant Maven/Python avec un diagnostic déterministe.


## Objet de l’incrément

`alpha.0.15` généralise la correction des checkouts incomplets observés sur les runners `alpha.0.13` et `alpha.0.14`. Le dernier log montre que l’archive locale contenait des sources qui n’étaient pas présentes dans le commit exécuté par GitHub (`CapabilityUnavailableException.java`, `JdbcDatabaseDialect.java`, `JdbcTransactionalEventStore.java`). Le projet dispose maintenant d’un inventaire canonique et d’un preflight Git bloquant avant tout build/test langage.

## Core Capabilities / JaCoCo

Les seuils restent inchangés à **98 % lignes et 98 % branches**. Aucune exclusion JaCoCo n’est ajoutée.

La suite JUnit passe de 17 à **37 scénarios** et couvre désormais :

- tous les états d’activation et de dépendance ;
- les parseurs profil/tier/rôle/topologie/trait, y compris null, blanc et valeur inconnue ;
- les invariants de `CapabilityCode`, `CapabilityDefinition`, `CapabilityDecision` et `CapabilitySnapshot` ;
- la matrice complète `CapabilityEnvironment` profil/tier/topologie/rôle/trait/activation ;
- les catalogues Capabilities et Quotas chargés depuis ressources et fichiers ;
- les documents CSV malformés et erreurs d’I/O ;
- toutes les branches d’allocation Lite, Pro Standard, Pro Advanced, Enterprise Standard et Enterprise Ultimate ;
- les quotas architecturaux non ajustables ;
- les bornes, dépassements, guards et erreurs de quota ;
- le cas d’une capacité protégée Lite ne nécessitant pas d’entitlement commercial.

Le contrôle de ratio Pro Advanced / Enterprise est conservé dans `QuotaDefinition`, sa source d’invariant. Le test redondant de `QuotaCatalog.resolvePro` est supprimé : toute valeur d’override est déjà bornée par le plafond certifié de la définition.

`QuotaPolicy` ne calcule plus `consumption * 100`. Les seuils 80 % et 90 % sont calculés par soustraction/division, sans risque de débordement pour les valeurs `long` maximales.

Les 37 scénarios ont été compilés avec `javac -Xlint:all -Werror` et exécutés localement via un harnais JUnit-compatible sous JDK 21. La mesure JaCoCo exacte reste à produire sous Java 25.

## Persistence / checkout incomplet

Le fichier canonique suivant est présent dans la livraison :

```text
src/components/adapters/jdbc/main/io/infranexum/adapters/persistence/jdbc/JdbcTransactionalEventStore.java
```

`persistence-test` dépend désormais de `persistence-check`. Si cette source ou un autre contrat obligatoire disparaît du checkout, le gate statique échoue avant la création des fixtures de test. Le scénario a été vérifié explicitement : suppression temporaire du fichier → `CHECK-JDBC-STORE-001`, sans `FileNotFoundError`.

Le smoke JDBC compile de nouveau `JdbcTransactionalEventStore` et `JdbcAuditJournal`.

## Non-régression locale

- source integrity : **17 tests**, 100 %, 0 violation ; simulation Git explicite d’un fichier présent mais non tracké → `CHECK-SOURCE-GIT-002` ;
- Architecture-as-Code : 28 tests, 100 %, CLI 0 violation ;
- toolchains : **19 tests**, 99 %, 0 violation ;
- migrations : 20 tests, 99 %, 0 violation ;
- eventing : 10 tests, 100 %, 0 violation ;
- persistence : **11 tests**, 98 %, 0 violation ;
- capabilities : 10 tests Python, 99 %, 0 violation ;
- entitlements : 10 tests, 100 %, 0 violation ;
- audit : 8 tests, 100 %, 0 violation ;
- total des tests Python fonctionnels de gates : **133** (17 source-integrity + 28 architecture + 19 toolchains + 20 migrations + 10 eventing + 11 persistence + 10 capabilities + 10 entitlements + 8 audit) ;
- **37/37** scénarios JUnit Capabilities exécutés dans le harnais local ;
- 8 smokes Java autonomes réussis ;
- Agent : race detector et couverture **98,4 %**, smoke processus et 5/5 répétitions de stress runtime ;
- Web : **27/27**, 99,65 % lignes, 98,28 % branches, 100 % fonctions.

## Preuves fournies par les runners

Le runner `alpha.0.13` a confirmé Core Contracts et Core Events sous Java 25, y compris les gates JaCoCo Events à 98 %. Le runner `alpha.0.14` a ensuite révélé une classe Capabilities et deux classes Persistence absentes du checkout GitHub alors qu’elles existent dans l’archive de livraison. `alpha.0.15` transforme donc cette classe de défaut en invariant de dépôt : inventaire canonique, tracking Git obligatoire, imports Java internes résolus, collisions de casse interdites et modules Maven vérifiés avant le reactor.

## Limites

Le statut reste **NON TERMINÉ** jusqu’à la réexécution de `./mvnw verify` sous Java 25. Le runner doit confirmer Core Capabilities à >=98 % lignes et branches, puis poursuivre Entitlements, Audit, Persistence JDBC et Server. Le job PostgreSQL 17/18 doit également confirmer que les deux tests JDBC ciblés compilent et s’exécutent avec le store restauré.

Docker Compose reste non applicable tant que le JAR Server Java 25 et le bootstrap d’installation neuve ne sont pas prouvés exécutables.

## Web administration dashboard

`alpha.0.58` remplace l’écran de bootstrap par un shell d’administration responsive : navigation latérale, topbar contextuelle, hero opérationnel, KPI véridiques, état du control plane, contexte de déploiement, workspace Organisations/Subdivisions, thème clair/sombre persistant et comportement `prefers-reduced-motion`. Bootstrap 5.3.6 reste embarqué localement et le thème visuel adapté reste la seule couche de personnalisation historique. Aucun indicateur non alimenté par une source réelle n’est présenté comme une mesure.
