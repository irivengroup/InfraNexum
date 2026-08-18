# InfraNexum 2.0.0-alpha.0.122 — prepared connector governance and execution admission

## Scope

`alpha.0.122` advances PGM-10-E06 at the governance boundary only. Existing Jira Assets and ServiceNow connector keys may receive a provider-neutral authority mapping (direction, authority, conflict/deletion policy, rollback strategy and field-level authorities). The provider name is resolved from the real configured provider and is not configurable in the mapping.

`executionEnabled=false` defines a prepared policy. The planner rejects mutating execution, the Web Execute selector excludes it and no provider write occurs. `executionEnabled=true` is admitted only when the provider connector is enabled and a matching approved synchronization handler is registered. Startup validation is bidirectional and fail-closed: handler without an active policy or active policy without a handler aborts composition. No Jira Assets or ServiceNow mutating handler is registered by this release.

The API adds `executionEnabled` to the existing governance response and audit metadata records `execution_enabled`. No endpoint, migration, capability or RBAC permission is added. Migrations remain through `0039`.

## Validation locale exécutée

- Web : 224/224 ; couverture 99,73 % lignes, 98,56 % branches, 100 % fonctions ; process smoke PASS.
- API : 48/48 ; 15 fragments / 200 opérations ; dette idempotence/pagination/capability/permission = 0/0/0/0 ; projection OpenAPI 2/2.
- Architecture : 206/206 tests fonctionnels + 29/29 tests du checker ; Architecture-as-Code sans violation.
- Toolchains : 25/25 ; migrations : 117/117 ; Compose : 69/69.
- Eventing 10/10, Persistence 12/12, Capabilities 10/10, Entitlements 10/10, Audit 8/8 ; checkers sans violation.
- Java Integrations auxiliaire sur JDK21 : compilation stricte `-Xlint:all -Werror` et smokes runtime/governance/sync/handler registry réussis. Ce préflight ne remplace pas le gate JDK25/JaCoCo.

## Roadmap status

- **PGM-10-E05: NON TERMINÉ** — exact JDK25/JaCoCo/PostgreSQL 17/18 proof remains mandatory on this snapshot.
- **PGM-10-E06: EN COURS** — prepared authority mapping and execution admission are implemented; durable alpha.0.116 synchronization/compensation is preserved; no provider mutator is active.
- **OpenService:** not implemented because draft.21 does not define an authoritative provider contract sufficient for a production adapter.

---

# InfraNexum 2.0.0-alpha.0.121 — deterministic DataTable geometry corrective

## Scope

- Corrects the transverse table-layout regression introduced while removing nested DataTable scrolling.
- Replaces automatic intrinsic sizing plus `1%` compact/action columns with fixed-layout, normalized semantic column weights.
- Keeps every enhanced table at exactly `100%` of its available workspace with no nested horizontal or vertical scroll region.
- Preserves readable headers, controlled long-value wrapping, dense-table spacing, intact action-button labels, localized empty states and DCIM Location context.
- Preserves the `alpha.0.119` isolated ReDoc/CSP design and the `alpha.0.120` PostgreSQL/JDK25 connector-sync qualification hardening.
- Adds no API operation, migration, permission, capability, provider authority mapping or provider mutation.

## Roadmap status

PGM-10-E05 remains **NON TERMINÉ** pending exact JDK25/JaCoCo/PostgreSQL 17/18 evidence on this snapshot. PGM-10-E06 remains **EN COURS**; no mutating provider is activated.

## Validation evidence

- **EXÉCUTÉ — Web:** 223/223 tests; 99.73% lines, 98.56% branches, 100% functions; process smoke passed.
- **EXÉCUTÉ — browser geometry:** Chromium headless at 1440/1024/800 px; table width equals container width, right-edge delta 0 px, horizontal overflow 0 px, wrapper vertical overflow visible, localized empty row present.
- **EXÉCUTÉ — API contracts:** 48/48 tests; 15 fragments, 200 operations, zero contract debt.
- **EXÉCUTÉ — toolchain policy:** 25/25 tests; zero violations.
- **EXÉCUTÉ — migrations:** 117/117 tests; zero violations; no new migration.
- **EXÉCUTÉ — targeted architecture:** 17/17 relevant tests plus Architecture-as-Code check.
- **EXÉCUTÉ — Compose contract:** 69/69 tests.
- **NON EXÉCUTÉ — exact JDK25/JaCoCo:** runner provides JDK 21.0.11; target JDK25 gate remains mandatory.
- **NON EXÉCUTÉ — live PostgreSQL 17/18:** no `psql`/target database is available in this runner; the CI matrix remains the required proof.

---

# InfraNexum 2.0.0-alpha.0.120 — PostgreSQL connector-sync qualification gate hardening

**Nature : corrective de qualification CI/JDBC, aucune avancée de roadmap.** La revue de `alpha.0.119` a identifié un défaut de preuve : le repository durable `JdbcConnectorSyncRepository` de `alpha.0.116` possédait une couverture JUnit déterministe PostgreSQL/Oracle, mais n'était pas explicitement exécuté dans la matrice live PostgreSQL 17/18.

**Correction :** ajout de `PostgreSqlJdbcConnectorSyncRepositoryTest` sur la vraie datasource PostgreSQL. Le contrat couvre l'admission idempotente et le rejet d'une réutilisation sémantiquement différente, l'écriture de checkpoints monotones, pause/reprise avec conservation du curseur, rejet d'une révision stale, succès puis compensation append-only vers un nouveau checkpoint, lecture durable des runs/checkpoints, ainsi qu'une course concurrente de deux admissions démontrant qu'un seul run acquiert le fence actif du connecteur.

**CI fail-closed :** le job `postgresql-integration` 17/18 exécute explicitement ce nouveau contrat. `CHECK-TOOLCHAIN-042` exige désormais dans ce job les contrats live Entitlements, Connector Inbox et Connector Sync ; leur suppression ou déplacement hors de la matrice déclenche une violation. Aucun seuil JaCoCo, aucun test existant et aucune contrainte de persistance n'est affaibli.

**Contrats préservés :** 15 fragments OpenAPI / 200 opérations, migrations jusqu'à `0039`, Jira Assets et ServiceNow `FEDERATED_READ`, runtime checkpoints/synchronisation/compensation `alpha.0.116`. Aucun provider mutateur n'est enregistré ou activé.

**État roadmap :** PGM-10-E05 reste formellement **NON TERMINÉ** tant que les gates exacts Temurin 25/Maven/JUnit/JaCoCo et PostgreSQL 17/18 de ce snapshot ne sont pas exécutés avec succès. PGM-10-E06 reste **EN COURS**.

---

# InfraNexum 2.0.0-alpha.0.119 — DataTable and DCIM context Web/UX corrective

**Nature : corrective Web/UX transverse, aucune avancée de roadmap.** Cette tranche corrige la géométrie commune des DataTables et le repérage contextuel DCIM sans modifier API, migrations, RBAC, capacités, synchronisation ni providers.

**DataTables :** les wrappers de tables ne sont plus des régions de scroll. `overflow-x:auto` est supprimé de la couche commune car il peut calculer `overflow-y:auto` et créer un scroll vertical imbriqué. Les cellules, entêtes et actions peuvent se replier dans la largeur disponible ; les frames historiques `overflow-hidden` sont neutralisées uniquement lorsqu'elles hébergent une DataTable enrichie. La table reste à `width/max-width: 100%` et aucune extrémité droite ne doit être masquée.

**État vide :** tout `tbody` vide enrichi reçoit une ligne structurelle localisée via `common.emptyList`; la valeur anglaise canonique est `No records are available.`. Le renderer commun marque également cet état pour conserver un comportement uniforme et correctement retraduit lors d'un changement de langue.

**DCIM :** les panneaux `Location` affichent désormais le contexte `Location` puis la rubrique active `Sites / Buildings / Floors / Rooms / Zones`, dans la liste comme dans l'éditeur, sur le même principe que les panneaux `Infrastructure`.

**ReDoc :** le renderer est désormais isolé de la shell Bootstrap authentifiée dans une iframe sandboxée same-origin. Cela supprime les collisions de portée CSS et confine l'injection de styles inline de ReDoc/styled-components à une CSP dédiée autorisant `style-src 'unsafe-inline'` uniquement dans le document ReDoc. La shell InfraNexum conserve une CSP stricte sans `unsafe-inline`, autorise uniquement la frame documentaire same-origin et conserve le contrat OpenAPI 3.1 certifié en local. Un handshake `postMessage` borné remonte explicitement ready/error/resize afin d'éviter un panneau blanc silencieux.

**État roadmap :** PGM-10-E05 reste formellement **NON TERMINÉ** jusqu'à réussite des gates exacts JDK25/JaCoCo/PostgreSQL 17/18. PGM-10-E06 reste **EN COURS** ; le runtime durable checkpoints/synchronisation/compensation de `alpha.0.116` est conservé et aucun provider mutateur n'est activé.

---

# InfraNexum 2.0.0-alpha.0.118 — hosted JDK25 quality-gate corrective

**Nature : corrective CI/JDK25, aucune avancée de roadmap.** Le run hébergé `alpha.0.117` confirme que la compilation Server JDK25 est réparée, mais laisse quatre gates rouges : DDI JaCoCo lignes à 96 %, fixture `ConnectorSyncEngineTest` incohérente avec la gouvernance de rollback, adaptateur JDBC à 96 % lignes / 89 % branches, et testCompile Server invalide autour de `SimpleMeterRegistry`.

**Corrections :** aucun seuil JaCoCo n'est abaissé et aucune exclusion n'est ajoutée. DDI couvre explicitement la surface générée/accessors restante. Integrations utilise `DUAL_COMPENSATION` lorsque des mutations locale+distant doivent être compensées et garde un test distinct qui prouve que `INBOUND + REMOTE_COMPENSATION` reste rejeté. Le moteur corrige en plus la transition réelle de compensation MANUAL : après `beginCompensation`, l'issue est `COMPENSATION_FAILED / MANUAL_COMPENSATION_REQUIRED` via `compensationFailed(...)`. JDBC renforce les branches PostgreSQL/Oracle/fencing/transactions et supprime des branches internes inatteignables. Le test Server ferme explicitement `SimpleMeterRegistry` en `finally`.

**Préflight local avant gel :** DDI JUnit-compatible 18/18 et traçage 49/49 lignes exécutables des classes DDI chargées ; Integrations JUnit-compatible 32/32 ; JDBC Connector Sync JUnit-compatible 13/13 ; smokes officiels DDI, Integrations/Sync, JDBC/JDBC-audit, Policy et API Capability 200 opérations PASS. Ces preuves sont auxiliaires et ne remplacent pas le gate Maven/JUnit/JaCoCo JDK25.

**État roadmap :** PGM-10-E05 reste formellement **NON TERMINÉ** jusqu'au prochain run exact JDK25/JaCoCo/PostgreSQL 17/18 réussi. PGM-10-E06 reste **EN COURS** et conserve intégralement le runtime de checkpoints/synchronisation/compensation de `alpha.0.116`; aucun provider mutateur n'est activé.

---

# InfraNexum 2.0.0-alpha.0.117 — JDK25 Server compilation corrective

**Statut : CORRECTIVE / AUCUNE AVANCÉE ROADMAP.** Le build Docker de `alpha.0.116` sous JDK25 échoue pendant la compilation du Server dans `ImmutableConnectorSyncHandlerRegistry`. La cause est l'inférence générique de `Objects.requireNonNullElse(handlers, List.of())` dans un `for-each`, dont le fallback vide devient `? extends Object` au lieu de `ConnectorSyncHandler`.

**Correction :** fallback explicitement typé `List.<ConnectorSyncHandler>of()` dans le registre, plus typage explicite des autres fallbacks de collections vides `requireNonNullElse` rencontrés dans les surfaces Java affectées. Aucun seuil, test, invariant de sécurité ou contrat métier n'est affaibli.

**Non-régression :** `java-integrations-smoke` compile désormais le vrai `ImmutableConnectorSyncHandlerRegistry` avec `-Xlint:all -Werror` et exécute les cas registre vide, doublon, handler nul et clé absente. L'architecture interdit également les fallbacks `List.of()/Set.of()/Map.of()` non typés dans `Objects.requireNonNullElse`.

**Contrats préservés :** 15 fragments OpenAPI / 200 opérations, migrations jusqu'à 0039, gouvernance/checkpoints/compensation `alpha.0.116`, Jira Assets et ServiceNow non mutateurs. PGM-10-E05 reste FORMELLEMENT NON TERMINÉ jusqu'aux preuves exactes JDK25/JaCoCo/PostgreSQL 17/18.

**Qualification locale alpha.0.117 :** API Contracts 48/48 en split déterministe et checker 15 fragments / 200 opérations / dette 0/0/0/0; Web 220/220 avec 99,73 % lignes / 98,54 % branches / 100 % fonctions et smoke process réussi; Architecture 205/205 fonctionnels + 29/29 méta + Architecture-as-Code PASS; Source Integrity 45/45 à 100 %; migrations 117/117; Eventing 10/10; Persistence 12/12; Capabilities 10/10; Entitlements 10/10; Audit 8/8; Toolchains 25/25; SDK 19/19 à 99 %; Compose 69/69; smokes Java Integrations/Sync/registre Server/JDBC/Capabilities/Policy/API Capability PASS. Maven/JUnit/JaCoCo JDK25 et PostgreSQL 17/18 live restent NON EXÉCUTÉS dans ce runner.

---

# InfraNexum 2.0.0-alpha.0.116 — durable connector sync checkpoints and compensation runtime

**Statut : PGM-10-E06 EN COURS / PGM-10-E05 FORMELLEMENT NON TERMINÉ. Nature : incrément fonctionnel provider-agnostique.** `alpha.0.116` complète le modèle d'autorité/direction/rollback de `alpha.0.114` par un vrai chemin d'exécution durable de synchronisation, sans activer de mutation Jira Assets ou ServiceNow.

**Runtime :** un cycle de synchronisation possède un run durable, une direction gouvernée, une révision de checkpoint monotone, un curseur interne et son SHA-256 public. L'exécution progresse en batches bornés, peut être mise en pause puis reprise, refuse les runs concurrents sur un même connecteur et ne suppose jamais une livraison exactly-once. Les handlers doivent être idempotents lorsqu'un curseur durable est rejoué.

**Compensation :** les stratégies `LOCAL_CHECKPOINT`, `REMOTE_COMPENSATION` et `DUAL_COMPENSATION` peuvent déclencher une compensation réelle via le handler approuvé. Une compensation réussie ajoute un nouveau checkpoint `COMPENSATION` restaurant le curseur initial; elle ne modifie jamais un checkpoint historique. Une compensation est refusée si le connecteur a déjà progressé au-delà de la révision du run concerné. `MANUAL` reste explicitement opérateur et `NONE_REQUIRED` n'autorise aucune fausse restauration.

**Persistance/RBAC :** migrations PostgreSQL/Oracle `0038` pour `connector_sync_state`, `connector_sync_run`, `connector_sync_checkpoint`; migration `0039` pour `integrations.sync.read`, `integrations.sync.execute`, `integrations.sync.compensate`, attribuées au rôle système protégé `system.platform_admin`. En mode MEMORY, les beans de sync durable ne sont pas composés.

**API/Web :** le contrat produit passe à 200 opérations. Les cinq routes Sync fournissent liste des runs, liste des checkpoints, execute, resume et compensate. Les mutations exigent `Idempotency-Key`; les lectures sont paginées. Les réponses publiques n'exposent jamais le curseur brut. Le Web n'affiche `Execute` que pour une future policy mutante réellement admise; Jira Assets et ServiceNow restent read-only et n'ont aucun handler mutateur enregistré.

**Validation de développement avant gel :** Web 220/220, couverture 99,73 % lignes / 98,54 % branches / 100 % fonctions; API Contracts 48/48, 15 fragments / 200 opérations, dette 0/0/0/0; architecture fonctionnelle 204/204, méta-checker 29/29, Architecture-as-Code PASS; migrations 117/117; Eventing 10/10; Persistence 12/12; Capabilities 10/10; Entitlements 10/10; Audit 8/8; Toolchains 25/25; SDK 19/19; Compose 69/69; smokes Java dependency-free Integrations/Sync/JDBC/Policy/API Capability PASS. Ces chiffres seront rejoués après le versionnage final avant packaging.

**Gates exacts encore requis :** JDK25/Maven/JUnit/JaCoCo >=98 % lignes+branches pour les nouveaux modules/tests, PostgreSQL 17/18 live, Docker PRO/PowerShell et provider réel autorisé. Aucun de ces gates n'est prétendu exécuté localement si la toolchain correspondante n'est pas disponible.

---

# InfraNexum 2.0.0-alpha.0.115 — hosted Java verification corrective

Status: **CORRECTIVE / NO ROADMAP ADVANCE**.

Hosted `alpha.0.114` evidence under the target Java toolchain identified four defects that must be closed before PGM-10-E05 can be promoted: DDI module JaCoCo line coverage was 96 % against the mandatory 98 % minimum; `OutboundNotificationRuntimeTest` expected a disabled-endpoint rejection while its publisher registry still contained the enabled fixture; the independent JDBC adapter verify reached only 93 % lines / 83 % branches; and the independent Server verify reported five Spring context errors because durable RSOT boundaries were composed in MEMORY mode and one bootstrap test used low-precedence default properties.

`alpha.0.115` corrects those causes without reducing coverage policy, disabling tests, excluding production classes or weakening runtime validation. DDI adds domain boundary coverage. Integrations registers the disabled endpoint in the test under assertion. JDBC adds a dedicated deterministic suite for `JdbcOutboundNotificationRepository`, including PostgreSQL/Oracle paths, lease fencing, retry/dead-letter state, Oracle unique-race recovery, replay/resume and transaction branches. Server conditions durable RSOT controllers/CLI on PostgreSQL/Oracle persistence and uses command-line overrides in executable bootstrap tests; a Spring context-runner regression protects the MEMORY composition boundary.

Local status for the corrected snapshot: dependency-free DDI/Integrations/JDBC/JDBC-audit/RSOT/Policy/API-Capability smokes **PASS**; dedicated functional JUnit-compatible probes for the newly added DDI and JDBC coverage tests **PASS**. Exact Temurin 25 Maven/JUnit/JaCoCo verification for `alpha.0.115` is **NON EXÉCUTÉ locally** and must be established by the next hosted GitHub Actions run. PGM-10-E05 therefore remains formally open and PGM-10-E06 remains in progress.

---

# InfraNexum 2.0.0-alpha.0.114 — PGM-10-E06 phase 4: Connector Governance

**Statut : EN COURS / NON TERMINÉ. Nature : évolution fonctionnelle bornée.** Cette version préserve Jira Assets, ServiceNow, Notifications et la corrective Web/IAM `alpha.0.113`, puis rend exécutable la gouvernance commune des connecteurs exigée par la roadmap : autorité, direction de synchronisation, conflits, suppressions, autorité par champ et stratégie de rollback.

**Politique runtime :** Jira Assets et ServiceNow restent `FEDERATED_READ / EXTERNAL / REJECT / IGNORE / NONE_REQUIRED`. Le nouveau planner dry-run est fail-closed : toute direction différente de celle configurée, mutation sans champs gouvernés, propagation de suppression avec politique `IGNORE`, champ non gouverné ou mutation sans rollback admissible est refusée. Aucun objet InfraNexum ou fournisseur n'est modifié par ce dry-run.

**API/Web :** trois opérations sont ajoutées sous `/api/v1/integrations/governance` : catalogue paginé, détail et `sync-plan` dry-run. Elles utilisent `integrations.connectors` + `integrations.connector.read` au scope PLATFORM ; les verbes/chemins non enregistrés restent deny-by-default. Le workspace Integrations expose direction, autorité, conflits, suppression, rollback et dry-run dans DE/EN/ES/FR/IT sans bearer token, secret ou Authorization fournisseur dans le navigateur. Le contrat passe à **15 fragments / 195 opérations, dette 0/0/0/0**.

**Frontière de livraison :** `ConnectorRollbackStrategy` est ici une déclaration de gouvernance et une condition d'admission, **pas encore un moteur de rollback exécutable**. Les synchronisations mutantes, checkpoints durables, compensation/rollback vérifié, propagation contrôlée des suppressions et mappings provider spécifiques restent à livrer. OpenService demeure non implémenté : `draft.21` le nomme mais ne fournit toujours pas de contrat produit/API/authentification/schéma métier faisant autorité. PGM-10-E06 reste donc **EN COURS**. PGM-10-E05 reste formellement **NON TERMINÉ** jusqu'aux preuves exactes Temurin 25/JaCoCo/PostgreSQL 17/18.

**Qualification de développement avant gel :** invariants Connector Governance **6/6** ; API Contracts **48/48**, couverture **98 %**, **15 fragments / 195 opérations**, dette **0/0/0/0** ; Architecture fonctionnelle **197/197**, méta-checker **29/29**, Architecture-as-Code `PASS` ; Web **217/217**, couverture **99,73 % lignes / 98,54 % branches / 100 % fonctions** ; Migrations **117/117**, Eventing **10/10**, Persistence **12/12**, Capabilities **10/10**, Entitlements **10/10**, Audit **8/8**, Toolchains **25/25**, SDK **19/19**, Compose **69/69** ; smokes Java dependency-free Integrations/Governance/Notifications, Policy, JDBC/JDBC-audit et API Capability **195 opérations** passent sous Java 21. Les preuves exactes Maven/JUnit/JaCoCo sous JDK25, PostgreSQL 17/18 live, Docker PRO, PowerShell 7 et navigateur réel restent des gates externes/non exécutés dans ce runner.

Voir `docs/integrations-connector-governance.md`.

---

# InfraNexum 2.0.0-alpha.0.113 — corrective Web/UX transverse

**Nature : corrective Web/UX, aucune avancée de roadmap.** La passe traite le comportement commun plutôt que des écrans isolés : géométrie DataTable bornée, colonnes dimensionnées selon leur contenu, suppression des identifiants techniques dans les listes, fiche détaillée avec identifiant read-only, délégation des actions `+ New`, consolidation des actions IAM et intégration visuelle du menu avatar.

**IAM :** `Utilisateur → Modifier` regroupe Paramètres, Appartenances, Rôles et statut `ACTIVE/SUSPENDED`. `Groupe → Modifier` regroupe Paramètres et Rôles ; l'action distincte `Membres` regroupe la liste paginée des appartenances directes, l'ajout, le retrait ligne par ligne et une vue séparée des membres effectifs issus des groupes imbriqués. `Rôle → Modifier` regroupe Paramètres, Affectations et Révocation. Les actions secondaires ne polluent plus la DataTable. Les facettes de fiche sont navigables au clavier (`←/→/Home/End`) avec déplacement effectif du focus.

**DataTables/CRUD :** les wrappers sont limités à 100 % du workspace, `table-layout:auto` remplace l'ancien layout fixe, les colonnes compactes restent au contenu, les colonnes longues absorbent l'espace disponible avec bornes de lisibilité, et le scroll horizontal reste interne au wrapper uniquement lorsque nécessaire. Les boutons CRUD injectés après le premier rendu sont capturés par délégation d'événements. Les colonnes primaires `ID/UUID` sont masquées dans les listes et l'identifiant sélectionné est réinjecté en lecture seule dans la fiche.

**État roadmap :** PGM-10-E05 reste formellement **NON TERMINÉ** tant que ses gates JDK25/JaCoCo/PostgreSQL 17/18 ne sont pas closes. PGM-10-E06 reste **EN COURS**. `alpha.0.113` ajoute uniquement `GET /api/v1/organizations/{orgId}/groups/{groupId}/members` pour distinguer les liens d'appartenance directs des membres effectifs ; l'opération réutilise `iam.group.read` et `iam.access`. Aucune migration, nouvelle permission ou nouvelle capability n'est ajoutée.

**Validation de développement :** suite Web complète **214/214**, couverture **99,73 % lignes / 98,54 % branches / 100 % fonctions**, smoke process `passed` ; API Contracts **48/48**, **15 fragments / 192 opérations**, dette **0/0/0/0** ; Architecture fonctionnelle **191/191**, méta-checker **29/29**, Architecture-as-Code `PASS`. Les validateurs transverses et les preuves de packaging finales sont consignés dans le manifeste de livraison.

---

# InfraNexum 2.0.0-alpha.0.112 — explicit Spring constructor binding corrective

**Nature : corrective de démarrage Server, aucune avancée de roadmap.** Les logs Docker PRO de `alpha.0.111` montrent que la suppression des maps YAML vides a fermé le premier défaut de conversion, mais a révélé un second défaut : Spring Boot 4.1 tente d'instancier `IntegrationRuntimeProperties` comme JavaBean et échoue avec `No default constructor found` sur les quatre nœuds Server. Le record possède en effet son constructeur canonique et un constructeur de compatibilité, ce qui rend le choix du constructeur de binding ambigu sans qualification explicite.

**Correction :** le constructeur canonique compact du record `IntegrationRuntimeProperties` est désormais annoté `@ConstructorBinding` (`org.springframework.boot.context.properties.bind.ConstructorBinding`). Le constructeur de compatibilité à 14 arguments est conservé pour ne pas casser les appels existants. Aucun constructeur sans argument mutable n'est ajouté. Les invariants, validations et normalisations `Map.of()` introduits en `alpha.0.111` restent inchangés.

**Non-régression :** le test Spring Boot existant `applicationYamlBindsWithNoIntegrationEndpointsConfigured` continue à charger le vrai `application.yaml` via `ConfigDataApplicationContextInitializer` et doit désormais prouver le démarrage avec zéro webhook/Jira Assets/ServiceNow/notification configuré. Un garde-fou d'architecture supplémentaire exige l'import `ConstructorBinding`, l'annotation du constructeur canonique, la conservation du constructeur de compatibilité et l'absence de constructeur vide.

**État :** PGM-10-E05 reste formellement **NON TERMINÉ** tant que les gates hébergées JDK25/JaCoCo/PostgreSQL 17/18 ne sont pas closes. PGM-10-E06 reste **EN COURS**. `alpha.0.112` est strictement corrective et ne modifie ni API, ni migrations, ni RBAC, ni capabilities.

**Validation locale :** garde-fou de binding **2/2** ; Compose **69/69** ; invariants Jira Assets/ServiceNow/Notifications **18/18** ; API Contracts **48/48**, **15 fragments / 191 opérations**, dette **0/0/0/0** ; Architecture fonctionnelle **191/191**, méta-checker **29/29**, Architecture-as-Code `PASS` ; Web **205/205**, couverture **99,73 % lignes / 98,54 % branches / 100 % fonctions**, smoke process `passed` ; Migrations **117/117**, Eventing **10/10**, Persistence **12/12**, Capabilities **10/10**, Entitlements **10/10**, Audit **8/8**, Toolchains **25/25**, SDK **19/19** à 99 % ; Source Integrity **45/45** à 100 %. Les smokes Java dependency-free JDBC/JDBC-audit/Integrations/Notifications/Policy/API-Capability passent sous Java 21. Le JUnit Spring ConfigData exact et Maven/JaCoCo restent **NON EXÉCUTÉS** dans ce runner car il ne fournit que Java 21 alors que le build exige JDK25. Le démarrage Docker PRO réel `alpha.0.112` reste à confirmer sur l'environnement opérateur.

---

# InfraNexum 2.0.0-alpha.0.110 — PGM-10-E06 phase 3: durable signed outbound notifications

**Nature : évolution fonctionnelle PGM-10-E06, tranche notifications.** Jira Assets (`alpha.0.108`) et ServiceNow CMDB (`alpha.0.109`) restent en lecture fédérée gouvernée. Cette candidate ajoute une pile de notifications sortantes réellement durable : domaine d'admission/idempotence, outbox PostgreSQL/Oracle, leasing borné multi-Server, retries/DLQ/suspension par endpoint, transport HTTPS signé HMAC-SHA256, récupération explicite par replay/resume, métriques à faible cardinalité, audit et UI Web sans secret.

**Sécurité et autorisation :** les destinations sont de la configuration opérateur et ne peuvent pas être fournies par l'API de publication. Les secrets sont uniquement `env:`/`file:`, exigent au moins 32 octets et sont effacés du buffer après usage. Le transport refuse redirections, userinfo et ports explicites autres que 443. Les six routes Notification sont enregistrées dans le résolveur RBAC Server avec `integrations.notification.read|publish|replay|resume`; tout verbe/chemin non enregistré reste fail-closed. Les erreurs publiques ne reflètent ni payload distant ni message interne.

**Durabilité/récupération :** `(endpoint,event-id)` est la clé métier d'idempotence ; une rediffusion identique est reconnue comme duplicate et une réutilisation sémantiquement différente est rejetée. Les réponses 408/425/429/5xx et erreurs réseau sont transitoires ; les autres non-2xx sont permanentes. Replay ne lève jamais implicitement une suspension : `resume` reste une action séparée et auditée. Les migrations `0036` et `0037` ont parité PostgreSQL/Oracle ; le rollback `0036` détruit l'outbox/l'état et nécessite donc drainage/sauvegarde préalable.

**OpenService :** le connecteur n'est volontairement pas inventé. Les sources `draft.21` disponibles le nomment mais ne définissent pas un produit/API faisant autorité, ses endpoints, son authentification ni son schéma métier. PGM-10-E06 reste donc ouvert jusqu'à décision produit/contrat fournisseur.

**Validation de développement avant gel :** Web **205/205** à **99,73 % lignes / 98,54 % branches / 100 % fonctions** ; API Contracts **48/48**, **15 fragments / 191 opérations**, dette **0/0/0/0** ; Architecture fonctionnelle **184/184** en split déterministe plus méta-checker **29/29**, Architecture-as-Code `PASS` ; Migrations **117/117**, Eventing **10/10**, Persistence **12/12**, Capabilities **10/10**, Entitlements **10/10**, Audit **8/8**, Toolchains **25/25**, SDK **19/19**, Compose **68/68** ; smokes Java dependency-free JDBC/JDBC-audit/Integrations/Policy/API-Capability exécutés sous Java 21. Le gel final revalide ces surfaces après versionnage. Exact Temurin **25.0.4+7**, Maven/JUnit/JaCoCo **98 % lignes+branches**, PostgreSQL **17/18**, Docker PRO/PowerShell, Node **24.18.1 + pnpm 11.17.0** et un récepteur webhook réel restent des gates externes/non disponibles dans le runner local.

---

# InfraNexum 2.0.0-alpha.0.109 — PGM-10-E06 phase 2: ServiceNow CMDB federated read

**Statut : EN COURS / NON TERMINÉ. Nature : évolution fonctionnelle bornée.** Cette version préserve la tranche Jira Assets de `alpha.0.108` et ajoute ServiceNow CMDB en **lecture fédérée sans copie**. PGM-10-E05 n'est toujours pas clôturé formellement : les gates hébergés Temurin 25/Maven/JaCoCo et PostgreSQL 17/18 restent obligatoires. PGM-10-E06 complet reste NON TERMINÉ tant qu'OpenService, notifications et les tranches de synchronisation/rollback prévues par la roadmap ne sont pas livrés.

**ServiceNow / autorité :** `components.adapters.service-now` est un adapter Java autonome, sans dépendance RSOT/ITAM. Le flux est `FEDERATED_READ`, autorité `EXTERNAL`. Il interroge uniquement la Table API d'une instance `*.service-now.com` configurée et seulement une table `cmdb_ci` ou `cmdb_ci_*`. La recherche publique est limitée à un terme de nom gouverné ; InfraNexum construit lui-même `nameLIKE<term>^ORDERBYsys_id` et n'expose pas un proxy de `sysparm_query`. La projection est limitée à `sys_id`, `name`, `sys_class_name`, `sys_updated_on`.

**Sécurité / résilience :** HTTPS obligatoire, redirections et ports explicites refusés, timeout <= 60 s, réponses bornées à 2 MiB par défaut et 8 MiB maximum configurable, pagination `offset <= 1 000 000` / `limit <= 200`, host strictement suffixé `.service-now.com`, erreurs fournisseur traduites sans recopier leur payload. Le bearer token est résolu uniquement depuis `env:` ou `file:` puis son buffer est effacé. La configuration par défaut contient zéro instance, token, client secret ou connecteur ServiceNow.

**API/Web :** trois opérations Server sont ajoutées : catalogue paginé ServiceNow, health et recherche CMDB paginée. Elles utilisent `integrations.connectors` + `integrations.connector.read`; les verbes non enregistrés restent deny-by-default. Le workspace Web Integrations conserve Jira Assets et ajoute ServiceNow avec catalogue, health, recherche et résultats minimaux, en session same-origin/CSRF sans provider Authorization dans le navigateur. Les textes sont couverts dans DE/EN/ES/FR/IT. Le contrat OpenAPI passe à **15 fragments / 185 opérations, dette 0/0/0/0**, sans nouvelle migration de données.

**Qualification locale : EXÉCUTÉ** — API Contracts **48/48**, couverture **98 %**, **15 fragments / 185 opérations**, dette **0/0/0/0** ; Architecture fonctionnelle **183/183** en exécution split, méta-checker **29/29** indépendant, Architecture-as-Code `PASS`, invariants ServiceNow **6/6** ; Web **204/204** sous Node **22.16.0**, couverture **99,73 % lignes / 98,54 % branches / 100 % fonctions**, process smoke `passed` ; Migrations **117/117**, Eventing **10/10**, Persistence **12/12**, Capabilities **10/10**, Entitlements **10/10**, Audit **8/8**, Toolchains **25/25**, SDK connecteurs **19/19**, Compose **68/68** ; smokes Java dependency-free Integrations, Policy et API Capability exécutés sous Java **21.0.11**, ce dernier vérifiant **185 opérations** ; Source Integrity **45/45 à 100 %** sur **1 430 chemins canoniques** et Archive Compatibility unitaire **12/12 à 100 %**. Le snapshot de packaging contient **1 432 fichiers suivis / 1 431 empreintes Git** ; le contrôle Source Integrity strict sur l'index staged est à zéro violation, l'archive Git est à zéro violation Archive Compatibility, son extraction repasse Source Integrity à zéro violation et deux constructions successives sont byte-for-byte identiques.

**NON EXÉCUTÉ / gates externes :** Temurin **25.0.4+7** avec Maven/JUnit/JaCoCo et seuil **98 % lignes + branches**, incluant les **10 JUnit ServiceNow définis** ; PostgreSQL **17/18 live** ; Docker Desktop/Compose PRO et wrapper PowerShell 7 ; Node **24.18.1/pnpm 11.17.0** exact ; Go **1.26.5** exact ; navigateur réel ; appel Jira Assets réel ; appel ServiceNow réel avec une instance/token autorisés. Le wrapper Maven refuse correctement le Java **21.0.11** local, donc aucun résultat JUnit ServiceNow n'est revendiqué ici.

Voir `docs/integrations-jira-assets.md` et `docs/integrations-service-now.md`.

---

# InfraNexum 2.0.0-alpha.0.107 — corrective Web/IAM/developer data and CI diagnosis

**Statut : NON TERMINÉ. Nature : corrective, aucun nouvel epic.** Cette version ne ferme pas encore PGM-10-E05 : le gate exact Temurin 25/Maven/JaCoCo et la matrice PostgreSQL 17/18 doivent toujours réussir sur GitHub Actions. Le message GitHub « Cannot retrieve latest commit at this time » n'est produit par aucun workflow, script ou source InfraNexum de la baseline ; aucun contournement du checkout ou affaiblissement de CI n'est introduit sans preuve d'un échec du job lui-même.

**Web / session :** le menu avatar synchronise désormais l'état natif `hidden`, `aria-expanded` et l'état visuel Bootstrap `show`. Le défaut provenait d'un menu `.dropdown-menu` dont `hidden` était retiré sans ajouter `.show`, laissant Bootstrap conserver `display:none`. Le test de non-régression vérifie ouverture, visibilité Bootstrap, focus et fermeture.

**IAM / administration plateforme :** `system.platform_admin` reste l'unique rôle système d'administration globale. Une attribution PLATFORM active de ce rôle protégé peut désormais évaluer les permissions et rôles sur un scope Organization/Subdivision même sans membership local ; tous les autres rôles restent soumis à la frontière de membership. Cette exception est explicite et fail-closed, ce qui restaure la capacité de l'administrateur de développement à tester les composants sans introduire un second super-rôle ni une élévation implicite.

**Outillage développeur :** `admin-reactivate` réactive/déverrouille uniquement le compte local canonique `admin`, incrémente son `security_epoch`, réactive sa projection IAM et vérifie son attribution `system.platform_admin` sans jamais la créer silencieusement. `docker/dev-seed-postgresql.sql` fournit des fixtures fictives idempotentes Organisation/IAM/RSOT/Schema Registry/ITAM/DCIM/DDI/Integrations. Ce seed reste hors du catalogue de migrations, sans identifiant de connexion, monté en lecture seule et exécuté avec le rôle applicatif après le `--wait web`; `seed` permet un rejeu explicite.

**Qualification locale corrective :** **EXÉCUTÉ** — Source Integrity strict **45/45, couverture 100 %, zéro violation**, snapshot **1 386 chemins canoniques / 1 388 fichiers suivis / 1 387 empreintes Git** ; Archive Compatibility **12/12, couverture 100 %, zéro violation** ; API Contracts **48/48, couverture 98 %, 15 fragments / 179 opérations, dette 0/0/0/0** ; Migrations **117/117, couverture 99 %, zéro violation** ; Capabilities **10/10 (99 %)**, Eventing **10/10 (100 %)**, Persistence **12/12 (98 %)**, Entitlements **10/10 (100 %)**, Audit **8/8 (100 %)** et Toolchains **25/25 (99 %)**, tous à zéro violation ; Compose contracts **68/68** ; Web **195/195**, couverture **99,73 % lignes / 98,53 % branches / 100 % fonctions**, smoke process réussi ; Architecture fonctionnelle split **171/171** et Architecture-as-Code `PASS` ; SDK connecteurs **19/19, couverture 99 %** ; smokes Java dependency-free concernés (contrats, events, audit, JDBC/workers, capabilities, entitlements, activation, workers/observabilité, RSOT, Schema Registry, ITAM, DCIM, DDI, Integrations et Policy) sortent à zéro dans les exécutions split sous Java 21. Le méta-checker Architecture instrumenté n'est pas revendiqué comme exécuté à terme : son lancement groupé puis son découpage atteignent la limite du runner.

**NON EXÉCUTÉ** — Temurin **25.0.4+7** avec Maven/JUnit/JaCoCo et seuil **98 % lignes + branches** ; PostgreSQL **17/18 live** ; Docker Desktop/Compose PRO (`up` + seed, `smoke`, `ha-smoke`) ; wrapper PowerShell 7 ; rendu navigateur réel du dropdown ; Go **1.26.5** et Node **24.18.1/pnpm 11.17.0** exacts ; run GitHub Actions spécifique au dépôt. Ces gates restent requis avant fermeture formelle de PGM-10-E05.

---

## 2.0.0-alpha.0.106 — corrective qualification / IAM / DCIM / Web

**Statut : NON TERMINÉ.** La corrective traite les seuils JaCoCo encore rouges de `alpha.0.105`, le bootstrap Organization Server en mode sans runtime Organization, l’administration des utilisateurs suspendus, l’auto-verrouillage IAM, le menu compte topbar, l’organisation verticale DCIM et les régressions IAM/ReDoc/DataTable. Aucun seuil qualité n’est abaissé et PGM-10-E06 n’est pas engagé. La fermeture formelle reste conditionnée au `mvn verify` Temurin 25 et à PostgreSQL 17/18 hébergés.

# InfraNexum 2.0.0-alpha.0.105 — fermeture de couverture hébergée et corrective Web

**Nature : corrective, aucun nouvel epic.** Cette version traite les déficits JaCoCo encore exposés par `alpha.0.104` sans réduire le seuil **98 % lignes + branches**, sans exclusion de classes de production et sans désactivation de tests. Les batteries couvrent les branches métier/résilience réelles ; les anciennes classes `*Smoke` JDBC/Organization sont intégrées au cycle Surefire via wrappers JUnit afin que leurs assertions existantes contribuent enfin à la couverture Maven.

**JDBC :** le préflight déterministe Java 21 exécute **129 tests** sans échec. L'instrumentation auxiliaire de conditions couvre les deux côtés de **407/411 conditions** et environ **99,4 % des côtés de condition** ; les quatre reliquats sont des garde-fous de dépassement de graphe IAM >512 et de retour batch JDBC atypique. Ce diagnostic n'est pas JaCoCo et ne remplace pas le gate Temurin 25.

**Web/Documentation :** les DataTables n'utilisent plus de scroll interne horizontal/vertical, restent dans la largeur disponible et proposent les tailles **20/50/100/200** avec pagination thémée. ReDoc charge la projection OpenAPI JSON same-origin certifiée, valide l'objet avant `Redoc.init()` et détecte l'écran fatal du renderer. Web est **EXÉCUTÉ 194/194**, couverture **99,73 % lignes / 98,53 % branches / 100 % fonctions**, smoke process réussi.

**Contrats/Architecture :** API Contracts est **EXÉCUTÉ 48/48**, **15 fragments / 179 opérations**, dette **0/0/0/0**. Architecture est **EXÉCUTÉ 200/200 en mode split** (171 fonctionnels + 29 méta-checker) et Architecture-as-Code ne remonte aucune violation. La tentative de suite Architecture instrumentée monolithique dépasse encore la fenêtre locale ; elle n'est donc pas revendiquée comme exécutée à terme.

**Qualification cible : NON EXÉCUTÉ localement** — Temurin **25.0.4+7** avec `./mvnw --batch-mode --no-transfer-progress --fail-at-end verify`, et PostgreSQL **17/18 live**. Ces gates restent obligatoires avant de déclarer PGM-10-E05 livré.

---

# InfraNexum 2.0.0-alpha.0.104 — qualification JDK25 corrective

**Nature : corrective, aucun nouvel epic.** Cette version traite l'ensemble des échecs révélés par `--fail-at-end`, `java-module-verify` et la matrice PostgreSQL sur `alpha.0.103`, sans réduction du seuil JaCoCo 98 %, exclusion de classes ni désactivation de tests.

**Persistance webhook :** `connector_inbox.payload_json` ne porte plus seul l'autorité du corps authentifié, car JSONB réécrit légitimement sa représentation lexicale. La migration forward-only `0035-integrations-connector-raw-payload` ajoute `payload_raw` (`TEXT` PostgreSQL / `CLOB` Oracle), backfille les lignes historiques et impose `NOT NULL`. Toute nouvelle admission écrit simultanément le corps brut et sa représentation JSON structurée ; les lectures/replays utilisent le corps brut.

**Architecture Server :** les contrôleurs/handlers IAM sont conditionnés avec le runtime IAM ; `AuthenticatedActorContext` appartient à la frontière HTTP ; `CorrelationIdFilter` et `ApiProblemSupport` sont composés par `HttpBoundaryConfiguration`. Les cycles Modulith `http↔identity` et `http↔observability` signalés par JDK 25 sont ainsi supprimés sans exclusion de package. Le bootstrap local-auth rejette également un secret décodé vide.

**Contrats et sécurité :** les validations de texte rejettent les caractères de contrôle sur l'entrée brute avant normalisation ; le fingerprint d'idempotence des mises à jour DCIM inclut le payload métier complet ; et la hiérarchie DDI autorise un enfant contenu dans son parent sans désactiver l'anti-chevauchement vis-à-vis des autres préfixes.

**Qualification locale auxiliaire :** les surfaces dependency-free touchées compilent sous Java 21 avec `-Xlint:all -Werror`; un harness JUnit-compatible fonctionnel exécute **386 tests Core/domaines**, tous passants ; **56 tests JDBC déterministes non-PostgreSQL** passent également. Architecture est exécutée en mode split **199/199** et Architecture-as-Code reste `PASS`. Web reste **192/192**, couverture **99,73 % lignes / 98,53 % branches / 100 % fonctions**. Ce harness ne remplace pas Maven/JUnit/JaCoCo. **NON EXÉCUTÉ localement :** Temurin 25 exact, calcul JaCoCo cible et PostgreSQL 17/18 live ; le rerun GitHub Actions reste obligatoire avant promotion formelle de PGM-10-E05.

---

# InfraNexum 2.0.0-alpha.0.102 — corrective CI/Web/UX after PGM-10-E05 phase 2

**Nature : corrective, aucun nouvel epic.** Les échecs du CI exact `alpha.0.101` sont traités sans réduire de seuil ni modifier les contrats métier : compilation Java 25 du test `SchemaRegistryServiceTest`, frontière Jackson 3 de `IntegrationController`, qualification explicite des `Clock`, séparation des tags métriques et attributs de spans, et couverture dédiée du contrat `IdempotencyLedger.Entry`. Le seuil JaCoCo reste **98 % lignes + branches**.

**Web/UX :** les bandeaux d'onglets sont désormais unis, sans dégradé, dans la teinte bleue déjà utilisée par le thème. Les entêtes de tables portent un seul dégradé continu Midnight → Blue → Blue-500 sur le `thead` complet ; les cellules restent transparentes afin que le dégradé ne redémarre jamais par colonne, avec texte blanc et indicateurs de tri turquoise. La hiérarchie IAM distingue visuellement les contextes `Identity`, `Access control`, `Authorization policy` des entités administrées. Les workspaces partagent des entêtes, espacements, filtres et conteneurs de données cohérents avec la lisibilité d'Overview.

**Login :** le panneau de marque passe à une répartition desktop 7/5 et conserve la typographie acceptée ; `Operate infrastructure` est une ligne non sécable à partir du breakpoint desktop. Le texte interne sur la frontière de sécurité est supprimé et remplacé par `copyright 2026 Iriven Group. All Right Reserved`, puis automatiquement `copyright 2026 - <année> ...` à partir de 2027. L'échec du probe initial `GET /local-auth/session` ne marque plus le service indisponible : ce probe est non bloquant, tandis qu'un échec réel de connexion/changement de mot de passe continue de produire l'état d'indisponibilité et reste réessayable.

**ReDoc/API :** le `$ref` invalide du fragment Platform Entitlements est corrigé, la projection OpenAPI Web est régénérée, et `CHECK-API-036` bloque désormais tout `$ref` interne mal formé ou externe non gouverné dans les fragments canoniques. Les tests de non-régression reproduisent les deux formes fautives avant correction.

**Validation locale corrective :** **EXÉCUTÉ** — API checker unitaire **48/48** et checker catalogue **15 fragments / 179 opérations, dette 0/0/0/0** ; Architecture **199/199** (170 tests hors méta-checker + 29 scénarios du méta-checker exécutés isolément en parallèle) et Architecture-as-Code `PASS`; Web **192/192**, couverture **99,73 % lignes / 98,53 % branches / 100 % fonctions**, smoke `passed`, dont initialisation ReDoc sur le contrat local certifié ; `java-schema-registry-smoke`, `java-integrations-smoke`, `java-contract-smoke`, `java-jdbc-smoke` et `java-api-capability-smoke` `PASS`; sources et tests Core Contracts/Compatibility compilent sous Java 21 avec une API JUnit minimale de contrôle syntaxique. **NON EXÉCUTÉ** — `alpha.0.102` sous Temurin 25/Maven/JaCoCo et PostgreSQL 17/18 réels dans cet environnement local ; revue navigateur réelle également non exécutée car la politique Chromium du runner bloque HTTP loopback et `file://`. Ces gates restent requis avant promotion formelle de PGM-10-E05.

---

# InfraNexum 2.0.0-alpha.0.101 — PGM-10-E05 phase 2: durable Connector runtime

**Nature : évolution fonctionnelle, PGM-10-E05 implémenté jusqu'au runtime phase 2 mais NON PROMU/LIVRÉ tant que les gates cible exacts restent non exécutés.** Cette tranche étend le SDK `alpha.0.100` par une admission webhook HMAC authentifiée et durable, une inbox générique PostgreSQL/Oracle, une DLQ opérable, le rejeu/reprise RBAC + audit, des retries/suspensions bornés et des métriques par connecteur. Aucun connecteur fournisseur n'est créé artificiellement.

**Sécurité et idempotence :** un endpoint actif référence uniquement un secret externe `env:` ou `file:` absolu ; aucun secret webhook n'est persisté. Le démarrage échoue si le handler ou le secret d'un endpoint actif n'est pas résolvable. Le corps JSON est borné à 1 Mio, signé HMAC-SHA256 avec fenêtre temporelle bornée et comparaison constante. `(connector, X-InfraNexum-Delivery-ID)` est l'identité durable : un doublon identique est idempotent, une réutilisation avec payload différent échoue en conflit. Le payload authentifié est conservé sans `trim`/normalisation et n'est jamais exposé dans la DLQ, l'audit ou les métriques.

**Inbox/DLQ :** leasing `SKIP LOCKED`, lots bornés et traitement asynchrone fournissent une sémantique at-least-once explicite. Les retries sont bornés avec backoff/jitter ; l'épuisement mène à `DEAD_LETTER`. Des dead letters répétées suspendent automatiquement le connecteur ; une admission peut continuer à être durable mais le dispatcher ne réclame plus de travail pour le connecteur suspendu. Le replay n'est autorisé que depuis `DEAD_LETTER`, incrémente son compteur et ne réactive pas implicitement le connecteur. La reprise est une opération distincte, autorisée et auditée.

**API/IAM/capabilities :** le catalogue compte **15 fragments / 179 opérations**, dette PGM-05-E01 **0/0/0/0**. Le webhook public possède les modes de gouvernance dédiés `connector-signature` et `connector-delivery`; les routes opérateur utilisent les permissions `integrations.dlq.read`, `integrations.dlq.replay`, `integrations.connector.read`, `integrations.connector.resume`. La capability `integrations.connectors` est disponible en Pro/Enterprise. Migrations `0033`/`0034` sont symétriques PostgreSQL/Oracle.

**Validation locale disponible :** **EXÉCUTÉ** — API Contracts **46/46 à 99 %**, **15 fragments / 179 opérations**, dette **0/0/0/0** ; Architecture directement concernée **35/35** et Architecture-as-Code `PASS`; Migrations **114/114** + checker zéro violation ; Capabilities **10/10**, catalogue **32 capabilities / 119 quotas** ; Compose contracts **64/64** ; Web **189/189**, couverture **99,73 % lignes / 98,53 % branches / 100 % fonctions**, smoke `passed` ; SDK **19/19** et package gate ; smokes Java offline integrations/eventing/JDBC/audit/entitlements/ITAM/DCIM/DDI/policy/capabilities `PASS`; compilation stricte Java 21 des nouveaux contrats/domaine/JDBC réussie. **NON EXÉCUTÉ** — Maven/JUnit/JaCoCo sous Temurin **25.0.4+7** et tests JDBC PostgreSQL **17/18** réels (runner local Java 21, pas de PostgreSQL cible), Agent Go **1.26.5**, Node **24.18.1/pnpm 11.17.0** exacts, Docker Desktop/PowerShell et revue navigateur. La suite Architecture instrumentée globale conserve le timeout du runner et n'est pas revendiquée comme réussie.

**Roadmap :** la fermeture formelle de PGM-10-E05 dépend du passage des gates cible ci-dessus. Aucun epic aval n'est déclaré commencé dans cette livraison.

---

# InfraNexum 2.0.0-alpha.0.100 — PGM-10-E05 phase 1: Connector SDK v1

**Nature : évolution fonctionnelle, PGM-10-E05 EN COURS.** Cette première tranche crée la frontière stable d'auteur/certification avant tout runtime de paquet connecteur : SDK Python 3.13 `1.0.0` sans dépendance runtime, contrat objet `Connector`, modèles immuables, manifeste `infranexum.connector-manifest/v1`, certification offline déterministe et primitives webhook HMAC-SHA256. Elle n'ajoute aucun connecteur fournisseur fictif, aucune route Server, migration, persistance de secret ou écriture directe en base.

**Gouvernance/sécurité :** le manifeste certifie versions fournisseur explicites, capabilities/permissions, noms de secrets sans valeur, egress HTTPS exact, matrice d'autorité, conflits/suppressions, idempotence/checkpoint/replay contrôlé, classifications de données, contrats versionnés, limites et support. Les wildcards, IP/localhost d'egress, compatibilités fournisseur `*`, champs inconnus, manifeste >1 Mio et `minimumVersion` SDK future échouent fermés. Les données certifiées sont profondément immuables et fingerprintées SHA-256.

**Webhook :** signature canonique HMAC-SHA256 avec secret >=32 octets, payload <=1 Mio, timestamp timezone-aware, comparaison constante et fenêtre de dérive bornée. `InMemoryReplayGuard` est uniquement un adaptateur local/test ; la protection de rejeu de production doit être durable et sera branchée sur l'inbox Core en phase 2.

**CI/package :** `make sdk-test sdk-check` est intégré à `verify-foundation` et à un job GitHub Actions dédié. Il impose couverture branche >=98 %, cohérence version/schema, double build wheel byte-identique (`SOURCE_DATE_EPOCH`) et import depuis le wheel construit.

**Roadmap :** PGM-10-E05 reste **EN COURS**. La phase suivante doit livrer admission webhook authentifiée, inbox durable connecteur, exposition DLQ, rejeu contrôlé/audité, retry/suspension bornés et métriques health/latence/backlog/erreurs. PGM-10-E06 et les epics DNS/DHCP dépendants restent bloqués jusqu'à fermeture de ce gate runtime.

---

# InfraNexum 2.0.0-alpha.0.99 — enterprise CRUD navigation and API documentation

**Nature : corrective Web/UX, aucun nouvel epic métier.** Les onglets d’entités adoptent un contrat list-first : DataTable triable, filtres compacts, bouton `+ Nouveau` uniquement lorsque la création est réellement supportée, colonne Actions contextuelle, éditeur create/edit/lifecycle exclusif et retour automatique à la liste après mutation réussie. Les suppressions initiées par l’utilisateur demandent confirmation, y compris une transition DCIM vers `deleted`. Les ressources dérivées/read-only n’exposent aucune mutation fictive.

**Présentation :** Identity & Access reprend le même fond d’entête de tabs que les autres workspaces. Les tables utilisent un fond d’entête continu et atténué plutôt que des couleurs différentes par colonne. Le topbar ne répète plus l’environnement. Le login retrouve les proportions du slogan `Operate infrastructure with clarity.` de la présentation précédemment acceptée. Les dumps JSON bruts sous les listes RSOT/DCIM/DDI sont retirés.

**Documentation :** une rubrique `DOCUMENTATION` sous `PLATFORM` expose Swagger et ReDoc dans le shell authentifié. Les deux vues utilisent `assets/generated/infranexum-openapi.yaml`, projection déterministe des 14 fragments / 174 opérations du catalogue Server. Swagger UI `5.32.13` et ReDoc CE `2.5.3` sont épinglés et chargés paresseusement depuis l’origine CSP explicitement autorisée `cdn.jsdelivr.net`; le YAML local reste disponible en cas d’indisponibilité du renderer. La projection est contrôlée octet pour octet par un test d’architecture.

**Validation alpha.0.99 :** **EXÉCUTÉ** — Web **189/189**, couverture **99,73 % lignes / 98,53 % branches / 100 % fonctions**, smoke `passed`; architecture directement concernée **91/91**; API Contracts **45/45 à 99 %**, **14 fragments / 174 opérations**, dette **0/0/0/0**; Source Integrity **45/45 à 100 %**, checker **0 violation**; Architecture-as-Code `PASS`; Toolchains **25/25**, Migrations **114/114**, Eventing **10/10**, Persistence **12/12**, Capabilities **10/10**, Entitlements **10/10**, Audit **8/8**, Compose **64/64**; `java-contract-smoke` et `java-policy-smoke` `PASS`; Archive Compatibility **12/12 à 100 %** et checker `git archive` **0 violation**; snapshot de gel **1272 fichiers suivis / 1271 checksums Git / 1269 chemins canoniques**. **NON EXÉCUTÉ globalement** — suite Architecture instrumentée : timeout du runner après **49 tests démarrés / 48 terminés OK**, pendant `test_legacy_brand_and_artifact_are_blocked`, sans assertion observée en échec avant l’arrêt; les **91/91** tests directement concernés et le checker passent séparément. Les gates cible exacts restent externes au runner disponible : Temurin **25.0.4+7** (runner Java **21.0.11**), Node **24.18.1 / pnpm 11.17.0** (runner Node **22.16.0**, pnpm absent), Docker Desktop PRO et PowerShell absents; revue visuelle Windows/Chromium et chargement réseau réel des renderers Swagger/ReDoc **NON EXÉCUTÉS**.

---

# InfraNexum 2.0.0-alpha.0.98 — PGM-05-E01 delivered

**Nature : évolution fonctionnelle de gouvernance API/runtime, PGM-05-E01 LIVRÉ.** La phase 5 ferme les deux dernières dettes contractuelles capability/permission et étend l’inventaire OpenAPI à toutes les routes publiques Server connues. La surface canonique compte désormais **14 fragments / 174 opérations**, avec un ratchet **0/0/0/0** pour idempotence, pagination, capability et autorisation.

**Capability/runtime :** chaque opération référence un code du catalogue Core. `ApiCapabilityFilter`, ordonné après la corrélation et avant l’authentification locale, retire fail-closed les surfaces métier non disponibles. La capability minimale `platform.bootstrap` protège le contrat de démarrage sans créer de dépendance circulaire : les routes de build/capabilities/quotas restent publiables pour le diagnostic et l’introspection de l’installation. Un smoke Java généré depuis les fragments OpenAPI confronte chaque route enregistrée au résolveur runtime.

**Autorisation :** `x-infranexum-permission` est un objet structuré. Les modes `permission`, `conditional`, `platform-admin`, `organization-visibility`, `authenticated-self` et `anonymous` distinguent les permissions IAM provisionnées des frontières spéciales. Le checker vérifie les codes `permission`/`conditional` contre `PermissionCodes` et les tests d’architecture vérifient qu’ils sont réellement référencés par les PEP Server.

**Publication :** le contrat produit consolidé reste généré depuis les fragments canoniques. Un second mode généré `installation-effective` filtre les routes selon les capabilities installées, sans devenir une source de vérité manuelle. L’audit de fermeture a ajouté quatre routes runtime auparavant absentes du catalogue : `/api/v1/system/build`, `/api/v1/platform/capabilities`, `/api/v1/platform/capabilities/{code}` et `/api/v1/platform/quotas`.

**Validation alpha.0.98 :** **EXÉCUTÉ** — API Contracts **45/45 à 99 %**, **14 fragments / 174 opérations**, ratchet **0/0/0/0**; génération `installation-effective` vérifiée sur **12 capabilities → 113 paths / 159 opérations**, avec `platform.bootstrap` conservé et DDI absent lorsqu’il n’est pas installé; architecture directement concernée **108/108**, smoke Java route→capability **174/174**, `java-contract-smoke` et `java-policy-smoke` `PASS`, Architecture-as-Code `PASS`; Capabilities **10/10 à 99 %**, catalogue **31 capabilities / 119 quotas**, smoke Java `PASS`; Web **181/181**, couverture **99,73 % lignes / 98,53 % branches / 100 % fonctions**, smoke `passed`; Source Integrity **45/45 à 100 %** et checker **0 violation** sur **1261 chemins canoniques**; Toolchains **25/25**, Migrations **114/114**, Eventing **10/10**, Persistence **12/12**, Entitlements **10/10**, Audit **8/8**, Compose **64/64**; Archive Compatibility **12/12 à 100 %** et checker `git archive` **0 violation**; snapshot de gel **1264 fichiers suivis / 1263 checksums Git / 1261 chemins canoniques**. **NON EXÉCUTÉ globalement** — suite Architecture instrumentée : timeout du runner pendant `test_repository_passes` après 56 tests démarrés, sans assertion observée en échec avant l’arrêt; les 108 tests directement concernés et le checker passent séparément. Maven/JUnit/JaCoCo cible sous Temurin **25.0.4+7**, Node **24.18.1 / pnpm 11.17.0**, Docker Desktop PRO et revue navigateur opérateur restent externes au runner disponible (Java **21.0.11**, Node **22.16.0**, sans pnpm/Docker/pwsh).

---

# InfraNexum 2.0.0-alpha.0.97 — PGM-05-E01 phase 4

Canonical API idempotency closes the historical idempotency debt from 39 to 0. Thirty-two IAM/RSOT mutations require a canonical `Idempotency-Key`; repeatable evaluation endpoints and security-sensitive Local Auth operations are explicitly classified. Migration `0032-core-api-idempotency` adds the PostgreSQL/Oracle durable ledger and the Server idempotency filter provides deterministic successful replay, payload-conflict detection and fail-closed handling of indeterminate executions. PGM-05-E01 remains in progress for capability/permission metadata closure.

**Semantics:** the generic IAM/RSOT ledger acquires a durable reservation before controller execution and never automatically expires `IN_PROGRESS`/`INDETERMINATE` records. It deliberately does **not** claim exactly-once execution or a single physical database transaction with the IAM/RSOT business mutation. Completed 2xx/3xx responses are replayable; a reused key with different request semantics returns 409; an indeterminate original execution blocks automatic retry. Existing Organization/ITAM/DCIM/DDI domain-native idempotency remains unchanged.

**Validation alpha.0.97 :** **EXÉCUTÉ** — API Contracts **32/32 à 98 %**, **13 fragments / 170 opérations**, ratchet **0/0/56/85**; architecture IAM/RSOT/idempotence directement concernée **56/56**, Architecture-as-Code `PASS`; sous-ensemble Java Core+Events+JDBC idempotence compilé sous Java 21.0.11 avec `-Xlint:all -Werror`; Web **181/181**, couverture **99,73 % lignes / 98,53 % branches / 100 % fonctions**, smoke `passed`; Source Integrity **45/45 à 100 %** sur **1255 chemins canoniques**, snapshot Git **1257 fichiers suivis / 1256 checksums**; Archive Compatibility unitaire **12/12 à 100 %**; Toolchains **25/25**, Migrations **114/114**, Eventing **10/10**, Persistence **12/12**, Capabilities **10/10**, Entitlements **10/10**, Audit **8/8**, Compose **64/64**. **NON EXÉCUTÉ globalement** — suite Architecture instrumentée : timeout du runner après 36 tests; les 56 tests directement concernés et le checker passent séparément. Maven cible exact sous Temurin 25.0.4+7 reste non exécuté car le wrapper refuse le Java 21.0.11 local; Node cible 24.18.1/pnpm 11.17.0, Docker Desktop PRO et revue navigateur opérateur restent externes.

---

# InfraNexum 2.0.0-alpha.0.96 — PGM-05-E01 phase 3: bounded pagination

**Nature : évolution fonctionnelle API/runtime, PGM-05-E01 EN COURS.** La phase 3 ferme la dette historique de pagination **15 → 0** en alignant domaine, ports de persistance, JDBC, contrôleurs HTTP, OpenAPI et adaptateurs Web. Les collections mutables/volumineuses utilisent un curseur keyset stable; les référentiels et sous-collections administratives utilisent un offset explicitement borné.

**Contrat :** 8 opérations utilisent `cursor + limit`; 7 utilisent `offset + limit`. Les corps JSON historiques restent des tableaux pour préserver la compatibilité. Les réponses ajoutent `X-Page-Limit` et, lorsqu'une continuation existe, `X-Next-Cursor` ou `X-Next-Offset`. Les adaptateurs Web conservent `payload` et ajoutent `pagination = {limit,nextCursor,nextOffset,hasNext}`. L'offset est borné à **1 000 000** dans le contrat Core, le runtime Server, OpenAPI et le Web.

**OpenAPI/CI :** chaque opération paginée déclare `x-infranexum-pagination: cursor|offset`; le checker vérifie mode, types, minimum/maximum, limites et headers. Le ratchet courant devient **39/0/56/85** (idempotence/pagination/capability/permission). PGM-05-E01 reste ouvert : l'idempotence puis les métadonnées capability/permission doivent encore être résorbées.

**Validation alpha.0.96 :** **EXÉCUTÉ** — API Contracts **32/32 à 99 %**, **13 fragments / 170 opérations**, ratchet **39/0/56/85**; tests ciblés contrats/architecture **72/72** dont **40 tests d’architecture directement affectés**; smokes Java Core/DCIM/DDI/ITAM/IAM `PASS`; Web **181/181**, couverture **99,73 % lignes / 98,53 % branches / 100 % fonctions**, smoke `passed`; Source Integrity **45/45 à 100 %**, Architecture-as-Code `PASS`; Toolchains **25/25**, Migrations **114/114**, Eventing **10/10**, Persistence **12/12**, Capabilities **10/10**, Entitlements **10/10**, Audit **8/8**, Compose **64/64**. **NON EXÉCUTÉ globalement** — suite Architecture instrumentée : timeout du runner après 33 tests sans assertion observée; les tests directement affectés et le checker passent séparément. Toolchains cible exactes : Temurin JDK 25.0.4+7 et Node 24.18.1/pnpm 11.17.0; runner disponible Java 21.0.11 / Node 22.16.0 sans pnpm. Archive Compatibility **12/12 à 100 % / 0 violation** sur le snapshot Git; snapshot de packaging **1243 fichiers suivis / 1242 checksums Git / 1241 chemins canoniques**. Docker Desktop PRO et revue navigateur opérateur restent externes.

---

# InfraNexum 2.0.0-alpha.0.95 — PGM-05-E01 phase 2: canonical API problem runtime

**Nature : évolution fonctionnelle de gouvernance/runtime API, PGM-05-E01 EN COURS.** La phase 2 aligne le comportement réel du Server sur le contrat OpenAPI certifié en phase 1. Un modèle unique `ApiProblem` et un service unique `ApiProblemSupport` couvrent désormais les exception handlers MVC et les rejets terminaux des filtres de corrélation, authentification locale, RBAC et ABAC. Les réponses utilisent `application/problem+json`, conservent le `X-Correlation-ID` validé et exposent un jeu commun de champs RFC 9457/InfraNexum.

**Compatibilité :** les extensions historiques `message`, `details` et `timestamp` sont conservées comme alias compatibles de `detail`, des détails structurés et `occurred_at`. Les erreurs inattendues restent fail-closed, leur détail public est générique, les textes publiables sont redacted/bornés et aucune exception interne n'est exposée. Aucun endpoint, status métier, migration, permission, capability ou règle RBAC/ABAC n'est supprimé.

**OpenAPI/CI :** les 13 fragments/170 opérations partagent exactement le même schéma `Problem`, toutes les réponses 4xx/5xx documentées résolvent vers `application/problem+json` et déclarent `X-Correlation-ID`. Le checker résout maintenant les `$ref` de réponses réutilisables et ajoute les contrôles fail-closed du schéma canonique et du header de corrélation. La dette ratchet demeure **39/15/56/85** (idempotence/pagination/capability/permission) : elle n'augmente pas.

**Validation alpha.0.95 :** **EXÉCUTÉ** — API Contracts **30/30 à 99 %**, **13 fragments / 170 opérations**, dette ratchet inchangée **39/15/56/85**; architecture runtime directement concernée **81/81** et Architecture-as-Code `PASS`; Source Integrity **45/45 à 100 %**, checker Git tracking/staged/checksums **0 violation**; Web **178/178**, couverture **99,73 % lignes / 98,53 % branches / 100 % fonctions**, smoke `passed`; Toolchains **25/25**; Migrations **114/114**; Eventing **10/10**; Persistence **12/12**; Capabilities **10/10**; Entitlements **10/10 à 100 %**; Audit **8/8**; Compose **64/64**. Snapshot Git temporaire : **1235 chemins suivis / 1234 checksums / inventaire canonique 1233 chemins**. **NON EXÉCUTÉ globalement** — suite Architecture instrumentée : timeout du runner après 27 tests sans assertion observée; les 81 tests directement affectés et le checker passent séparément. Toolchains cibles exactes : JDK 25.0.4+7 et Node 24.18.1/pnpm 11.17.0; runner disponible Java 21.0.11 / Node 22.16.0 sans pnpm. Docker Desktop PRO et revue navigateur opérateur restent externes.

---

# InfraNexum 2.0.0-alpha.0.94 — dense enterprise filters and data-navigation refinement

**Nature : corrective Web/UX, sans nouvel epic métier.** Les formulaires de recherche/filtrage et les sélecteurs de contexte utilisent désormais une barre `.inx-filter-bar` compacte : sur écran desktop, champs, selects et actions restent alignés sur une seule ligne lorsque l'espace le permet ; sous 768 px, la barre se replie proprement en largeur complète. Les libellés et hauteurs de contrôles sont réduits uniquement dans ce contexte afin de diminuer l'espace vertical sans modifier les formulaires de création/édition.

**Tables et tabs :** les en-têtes de tables utilisent un fond Midnight/Blue à contraste élevé, souligné par Turquoise et ponctué d'accents Green/Orange aux extrémités. Les `nav-underline` utilisés comme tablists deviennent de véritables surfaces de navigation : fond spectral très léger Blue/Turquoise/Green/Orange, état actif Midnight/Blue avec texte blanc et indicateur Turquoise. Les couleurs restent concentrées sur les zones de repérage et n'altèrent ni les contenus de données ni les états Bootstrap existants.

**Compatibilité :** aucun endpoint, contrat OpenAPI, modèle métier, migration, permission, capability ou règle RBAC/ABAC n'est modifié. La phase 1 de PGM-05-E01 livrée en `alpha.0.93` reste inchangée et son ratchet contractuel demeure 39/15/56/85.

**Validation alpha.0.94 :** **EXÉCUTÉ** — Web **178/178**, couverture runtime **99,73 % lignes / 98,53 % branches / 100 % fonctions**, smoke `passed`; architecture UI directement concernée **8/8** et Architecture-as-Code `PASS`; Source Integrity **45/45 à 100 %**, checker **0 violation**; API Contracts **28/28 à 99 %**, **13 fragments / 170 opérations**, dette ratchet inchangée **39/15/56/85**; Toolchains **25/25**; Migrations **114/114**; Eventing **10/10**; Persistence **12/12**; Capabilities **10/10**; Entitlements **10/10**; Audit **8/8**; Compose **64/64**; snapshot Git temporaire **1230 chemins suivis / 1229 checksums / inventaire canonique 1227 chemins**, zéro violation; Archive Compatibility **12/12 à 100 %**, checker **0 violation**. **NON EXÉCUTÉ globalement** — suite Architecture complète : timeout du runner après 33 tests sans assertion observée; les 8 tests directement affectés et le checker passent séparément. Les toolchains cibles exactes JDK 25 / Node 24.18.1-pnpm 11.17.0, Docker Desktop PRO et la revue navigateur opérateur restent des gates externes.

---

# InfraNexum 2.0.0-alpha.0.93 — PGM-05-E01 phase 1: API contract governance

**Nature : évolution fonctionnelle de gouvernance API, PGM-05-E01 EN COURS.** Cette tranche introduit le catalogue canonique de tous les fragments OpenAPI Server, un validateur global fail-closed et la génération déterministe du contrat OpenAPI produit complet. Elle ne modifie ni les routes publiques existantes, ni les migrations, ni les règles RBAC/ABAC.

**Contrat certifié :** les 13 fragments OpenAPI 3.1 et leurs 170 opérations sont enregistrés dans `openapi/catalogue.yaml`. Le gate impose version produit cohérente, `operationId` globalement uniques, absence de collision méthode/route, organisation fonctionnelle composant/contexte, sécurité déclarée, résumés explicites et erreurs 4xx/5xx documentées en `application/problem+json`. `artifacts/validation/openapi-product.yaml` est généré depuis les fragments certifiés ; il n'est jamais une seconde source de vérité manuelle.

**Ratchet de dette :** la dette historique est figée par `operationId` et ne peut plus augmenter : idempotence **39**, pagination **15**, capability metadata **56**, permission metadata **85**. Toute nouvelle opération qui crée une dette supplémentaire fait échouer la certification. Les phases suivantes de PGM-05-E01 doivent réduire ces quatre compteurs jusqu'à zéro et normaliser le comportement runtime correspondant.

**Dépendance roadmap :** PGM-05-E01 n'est pas encore déclaré livré. `PGM-10-E05` reste bloqué jusqu'à fermeture des phases runtime/contractuelles restantes ; DNS/DHCP restent donc en aval. Voir `docs/api-platform-contract-governance.md`.

**Validation alpha.0.93 :** **EXÉCUTÉ** — API Contracts **28/28**, couverture **99 %**, **13 fragments / 170 opérations**, zéro violation et dette ratchet stable **39/15/56/85**; Source Integrity **45/45 à 100 %**, checker zéro violation; cinq tests d'architecture directement concernés **5/5** et Architecture-as-Code `PASS`; Toolchains **25/25**, Migrations **114/114**, Eventing **10/10**, Persistence **12/12**, Capabilities **10/10**, Entitlements **10/10**, Audit **8/8**, Compose **64/64**; Web **176/176**, couverture **99,73 % lignes / 98,53 % branches / 100 % fonctions**, smoke `passed`; Java contract/policy smokes `PASS` sous JDK 21 local. **NON EXÉCUTÉ** — couverture Architecture globale instrumentée (timeout du runner), Maven/JUnit exact sous Temurin JDK 25.0.4+7, Node 24.18.1/pnpm 11.17.0 exacts, Docker Desktop PRO et revue navigateur opérateur.

---

# InfraNexum 2.0.0-alpha.0.92 — deterministic Settings and notification drawer corrective

**Nature : corrective Web/UX, sans nouvel epic métier.** Cette révision ferme le défaut opérateur où le bouton Settings « Enregistrer » pouvait être visible mais ne produire aucun effet observable sur le chemin navigateur réel. Les contrôles shell globaux sont désormais câblés avant toute initialisation asynchrone RSOT/ITAM/DCIM/DDI, le bouton Save possède un handler `click` explicite et indépendant du submit implicite du navigateur, et chaque application de préférences resynchronise les `.inx-select` visibles avec leur `<select>` natif autoritatif.

**Settings :** Save lit les valeurs natives, persiste le document versionné, applique immédiatement `density`, `navigation`, `layout` et `refresh`, demande le thème Clair/Sombre partagé avec la topbar, émet `infranexum:preferences-change`, marque le drawer `saved` puis le ferme. Le test de non-régression reproduit maintenant un clic réel sur `#preferences-save` au lieu d'appeler indirectement la soumission de formulaire.

**Notifications :** l'icône topbar devient une cloche SVG monochrome héritant de `currentColor`. Le centre de notifications est un drawer modal vertical aligné à droite, avec largeur bornée, backdrop, animation, Escape, clic backdrop, retour du focus et liste scrollable. Il ne dépend plus du grand `.inx-dialog` central.

**Login :** “Operate infrastructure with clarity.” reste le message produit principal mais sa taille est rééquilibrée à `clamp(2.15rem, 3.45vw, 3.45rem)`, avec largeur de lecture réduite et interligne adapté au split-screen. L'identité produit reste en haut et la carte d'authentification conserve sa hiérarchie indépendante.

**Validation alpha.0.92 :** **EXÉCUTÉ** — Web **176/176**, couverture runtime **99,73 % lignes / 98,53 % branches / 100 % fonctions**, smoke `passed`; tests d'architecture Web directement concernés **24/24**; Architecture-as-Code `PASS`; Compose contract **64/64**; Toolchains **25/25**; Migrations **114/114**; Source Integrity **45/45** et checker **0 violation** avant gel Git. Les gates exacts JDK 25, Node 24.18.1/pnpm 11.17.0, navigateur Windows réel et Docker Desktop PRO restent des gates de promotion externes.

---

# InfraNexum 2.0.0-alpha.0.90 — premium login/settings/display corrective

**Nature : corrective Web/UX et préférences d’affichage, sans nouvel epic métier.** Cette révision améliore la vitrine d’authentification, simplifie l’affichage des pays et langues, restaure le panneau Settings vertical à droite et ajoute le choix de largeur Page/Fluide ainsi que le thème Clair/Sombre dans ce même panneau.

**Login :** l’identité `IN / InfraNexum / Infrastructure Control & Governance Platform` reste positionnée en haut de la vitrine, le récit produit et les capacités principales structurent la zone de marque, et `Sign in to InfraNexum` possède une couleur explicite et lisible sur la carte d’authentification en thème clair comme sombre. Les contrastes et surfaces restent dérivés de la palette IONOS Midnight/Blue/Turquoise/Orange.

**Settings et affichage :** `preferences-dialog` redevient un drawer modal étroit, aligné à droite, avec backdrop et animation latérale. Le schéma de préférences passe de `web-preferences/v1` à `v2` uniquement pour ajouter `layout=page|fluid`; la migration conserve densité, navigation et intervalle de rafraîchissement existants. Le thème n’est pas dupliqué dans ce JSON : Settings et le bouton topbar pilotent le même stockage historique `infranexum.theme` avec les valeurs `light|dark`.

**Pays et internationalisation :** les options pays affichent uniquement le nom localisé ; l’alpha-2 reste la valeur soumise au Server. Le sélecteur de langue affiche uniquement drapeau + code `DE/EN/ES/FR/IT`, les noms complets restant disponibles aux technologies d’assistance via `aria-label`.

**Sidebar :** les états normal, hover/focus, actif et indisponible utilisent des tons distincts de la palette produit. Le texte n’utilise jamais le bleu IONOS sur le fond bleu de la sidebar ; l’état actif inverse sur une surface turquoise avec texte Midnight, le hover combine Blue/Turquoise et un accent Orange sur l’icône.

**Validation alpha.0.90 :** **EXÉCUTÉ** — Web **175/175**, couverture runtime **99,73 % lignes / 98,53 % branches / 100 % fonctions**, smoke `passed`; tests d’architecture Web ciblés **24/24**; Source Integrity **45/45 à 100 %**, checker **0 violation**; Architecture-as-Code `PASS`; Toolchains **25/25** (99 %), checker 0 violation; Migrations **114/114** (99 %), checker 0 violation; Compose contract **64/64**; Archive Compatibility **12/12 à 100 %**, checker 0 violation. Le ZIP final est extrait avec `unzip` puis rejoué sur Source Integrity, Architecture-as-Code, Compose et Web avec les mêmes résultats; le mode `-rwxr-xr-x` de `docker/dev-compose.sh` est conservé.

**NON EXÉCUTÉ** — revue visuelle pixel-perfect dans le navigateur Windows cible; compilation Maven/JUnit/JaCoCo complète sous Temurin JDK 25.0.4+7; toolchain Web exacte Node 24.18.1/pnpm 11.17.0; Docker Desktop PRO `up`, `smoke` et `ha-smoke`; couverture Architecture instrumentée globale. Ces contrôles restent des gates de promotion et ne sont pas présentés comme validés.

**Dépendance roadmap :** aucun epic n’est avancé. La chaîne métier reste `PGM-05-E01 → PGM-10-E05 → PGM-08-E02/PGM-08-E03`.

---

# InfraNexum 2.0.0-alpha.0.90 — enterprise IAM/forms/calendar/Partner corrective

**Nature : corrective fonctionnelle Web/UX et invariants de période, sans nouvel epic métier.** Cette révision corrige les tabs Identity & Access, rétablit un calendrier InfraNexum déterministe, remplace la saisie JSON des contacts Partner par des champs structurés, introduit le catalogue ISO 3166-1 alpha-2 groupé par continent et renforce les surfaces Partner/IAM pour un rendu logiciel d’entreprise.

**Identity & Access :** les navigations principales et secondaires pilotent désormais explicitement `hidden`, `aria-hidden`, `aria-selected` et le focus clavier. Chaque facet s’initialise indépendamment ; un panneau inactif ne peut plus rester visible derrière le panneau actif.

**Calendriers et périodes :** les champs `date`/`datetime-local` restent les valeurs FormData autoritatives mais sont rendus via `.inx-temporal-*`, sans dépendre du popup natif Windows/Chromium. Le calendrier est Monday-first, propose une grille rapide de 16 années et une navigation par pas de 12 ans. Toute paire début/fin reconnue synchronise `min/max` et interdit une fin strictement antérieure au début. L’égalité est autorisée. Les invariants correspondants sont également conservés côté domaines concernés.

**Partners et pays :** `contacts` n’est plus saisi comme JSON. L’éditeur répétable expose type, nom, e-mail, téléphone et URL avec validation HTML puis sérialisation dans le contrat API existant. `countryCode` est un `select` alimenté par les 249 entrées ISO 3166-1 alpha-2 ; les libellés sont localisés et regroupés dans sept continents, tandis que la valeur soumise reste le code alpha-2. Le même catalogue est réutilisable par DCIM.

**Ergonomie entreprise :** les panneaux IAM, formulaires Partner, détails gouvernés, contacts, tabs, tables et calendriers utilisent la couche produit `.inx-*` / `--inx-*` au-dessus de Bootstrap 5.3.6, avec palette IONOS Midnight/Blue/Turquoise/Orange, contrastes élevés et surfaces cohérentes. Le détail Partner n’est plus un dump JSON : il présente une fiche gouvernée lisible et les contacts structurés.

**Dépendance roadmap :** aucun epic n’est avancé. La chaîne métier reste `PGM-05-E01 → PGM-10-E05 → PGM-08-E02/PGM-08-E03`.

---

# InfraNexum 2.0.0-alpha.0.85 — Server contract stabilization

**Nature : correction de non-régression, sans nouvel epic métier.** Le build Docker Java 25 de `alpha.0.84` a franchi les défauts de text blocks corrigés précédemment puis a révélé deux incompatibilités de contrat internes au Server. DCIM Physical appelait `Asset.organizationId()` alors que l'agrégat ITAM expose contractuellement `owningOrganizationId()`. DDI/IPAM construisait `JdbcRsotRepository` avec `(DataSource, JdbcTransactionalEventStore, JdbcDatabaseDialect)` alors que ce repository RSOT est read-only et expose `(DataSource, JdbcDatabaseDialect)`. `alpha.0.85` corrige ces deux call sites sans modifier le modèle métier, les migrations, les endpoints, les permissions ni les contrats Web.

**Non-régression :** les tests d'architecture DCIM et DDI vérifient désormais explicitement l'accesseur ITAM et la signature du repository RSOT. Une passe transversale compare également l'arité de tous les constructeurs `Jdbc*Repository` aux instanciations présentes sous `src/applications/server/main`; aucun autre mismatch n'est détecté après correction.

**Dépendance roadmap :** aucun epic n'est avancé dans cette corrective. La chaîne reste `PGM-05-E01 → PGM-10-E05 → PGM-08-E02/PGM-08-E03`.

**Promotion runtime :** le build Maven/JDK 25 exact et `docker/dev-compose.ps1 up`, `smoke`, `ha-smoke` de `alpha.0.85` restent obligatoires sur Docker Desktop PRO. Le runner de livraison fournit Java 21 et ne peut pas résoudre l'archive Temurin 25 exacte ; il ne peut donc pas certifier la compilation Maven cible.

---

# InfraNexum 2.0.0-alpha.0.84 — build stabilization and Bootstrap 5 Web contract

**Nature : correction de non-régression, sans nouvel epic métier.** La baseline fonctionnelle reste PGM-08-E01 livrée en `alpha.0.83`. Cette révision corrige deux text blocks Java invalides dans les CLI DCIM Physical et DDI/IPAM qui faisaient échouer la compilation Server du build Docker Java 25. Un gate statique parcourt désormais toutes les sources Java et refuse un `return """` dont le contenu commence avant le saut de ligne obligatoire. Le diagnostic PowerShell Compose distingue aussi explicitement un service sans conteneur — typiquement après un build avorté — d'un conteneur réellement démarré mais unhealthy.

**Web Bootstrap 5 natif :** Bootstrap 5.3.6 devient l'unique contrat de présentation. La feuille InfraNexum ne définit plus aucune classe `.inx-*` ni variable `--inx-*`; elle surcharge uniquement des variables `--bs-*` et des composants/classes Bootstrap existants. Les surfaces de feedback utilisent les composants `alert alert-*`; les sélecteurs métier restent de vrais `select.form-select`; les dates/datetimes restent de vrais `input.form-control` de type `date`/`datetime-local`. Les clones visuels JavaScript de combobox et calendrier sont retirés, sans modifier les valeurs FormData, les filtres d'entités, l'autorisation, les routes ou les contrats API.

**EXÉCUTÉ** — Bootstrap/IAM/Compose ciblé : 83/83; Source Integrity : 45/45, couverture 100 %, 0 violation; Web : 155/155, couverture runtime 99,73 % lignes / 98,53 % branches / 100 % fonctions, smoke `passed`; Compose contract : 64/64; Architecture-as-Code CLI : 0 violation; Toolchains : 25/25 (99 %); Migrations : 114/114 (99 %); Eventing : 10/10 (100 %); Persistence : 12/12 (98 %); Capabilities : 10/10 (99 %); Entitlements : 10/10 (100 %); Audit : 8/8 (100 %). Les smokes Java autonomes exécutables avec le JDK local passent, notamment DCIM Physical, DDI/IPAM, Policy et JDBC Workers.

**NON EXÉCUTÉ** — build Maven/JUnit/JaCoCo cible sous Temurin 25.0.4+7 : le runner fournit Java 21.0.11 et la tentative de téléchargement du JDK exact échoue sur la résolution DNS de `github.com`. Node cible 24.18.1/pnpm 11.17.0 n'est pas disponible; les tests Web ci-dessus ont été exécutés avec Node 22.16.0. Docker Desktop/Compose et PowerShell ne sont pas disponibles dans le runner, donc `up`, `smoke` et `ha-smoke` PRO réels restent des gates de promotion. La couverture Architecture agrégée reste NON EXÉCUTÉE car le runner dépasse sa limite pendant les copies/scans instrumentés; le checker du dépôt passe séparément à 0 violation.

**Dépendance roadmap suivante :** PGM-08-E02 DNS et PGM-08-E03 DHCP dépendent de PGM-10-E05. PGM-10-E05 dépend de PGM-02-E03 et PGM-05-E01; PGM-02-E03 est déjà livré, PGM-05-E01 reste le prochain prerequisite non livré. Le prochain incrément métier doit donc être PGM-05-E01 (standard REST/OpenAPI, erreurs, pagination et idempotence), avant PGM-10-E05 puis DNS/DHCP.

---

# InfraNexum 2.0.0-alpha.0.83 — état d’implémentation

## alpha.0.83 — PGM-08-E01 DDI/IPAM

Implemented in this snapshot: Organisation-scoped VRF/VLAN/network/pool/address model, atomic pool allocation and reservations, CIDR/pool overlap protection, weak RSOT/DCIM links, 12 RBAC permissions, migrations `0030/0031`, 15-operation OpenAPI surface, CLI and full DE/EN/ES/FR/IT Web administration under `#/ddi`. DNS/DHCP remain pending their own roadmap epics.


## alpha.0.82 — PGM-07-E05 racks, équipements, empreintes, ports et câblage

`alpha.0.82` implémente le gate roadmap **« occupation, connectivité physique et modèles multi-constructeurs »**. DCIM possède désormais les modèles d’équipement et leurs templates de ports, les racks, l’occupation en unités U, les équipements installés, les ports physiques et les liaisons point-à-point. Partner/RSOT/ITAM/Organization restent des autorités externes référencées sans FK inter-bounded-context.

L’installation vérifie modèle/rack actifs, dimensions compatibles, plage U, absence de chevauchement, unicité du numéro de série et quotas. Placement et déplacement verrouillent le Rack transactionnellement ; le câblage verrouille les deux ports dans un ordre déterministe avant de réévaluer leur disponibilité. Les connexions exigent des équipements distincts, des types de ports compatibles ainsi que le même média/connecteur, et un équipement encore câblé ne peut pas être décommissionné/archivé.

La tranche comprend capability `dcim.physical`, 17 permissions organisation-scoped, migrations PostgreSQL/Oracle `0028/0029`, JDBC/outbox/idempotence/version optimiste, API/OpenAPI 3.1 avec 14 opérations explicites, CLI Server et parité Web immédiate. Le workspace DCIM expose modèles/racks/équipements/ports/câbles, activation/archivage/décommission, installation/déplacement et connexion/déconnexion avec des sélecteurs gouvernés ; aucun UUID métier n’est saisi librement. DE/EN/ES/FR/IT restent supportées.

**EXÉCUTÉ sur le snapshot versionné** — Source Integrity **45/45 à 100 %**, 0 violation ; Architecture fonctionnelle **145/145** et Architecture-as-Code `PASS` ; migrations **109/109**, couverture **99 %**, 0 violation ; Web **149/149**, couverture **99,72 % lignes / 98,51 % branches / 100 % fonctions**, process smoke `passed` ; **19** targets Java dependency-free, dont JDBC et E05 ; compilation syntaxique JUnit E05 sous `javac -Xlint:all -Werror` ; Toolchains **25/25** (99 %), Eventing **10/10** (100 %), Persistence **12/12** (98 %), Capabilities **10/10** (99 %), Entitlements **10/10** (100 %), Audit **8/8** (100 %), tous les checkers à 0 violation ; Compose **63/63** ; Agent local Go 1.23.2 `vet + race + tests + build`, couverture **98,4 %**.

**NON EXÉCUTÉ complètement** — couverture Architecture instrumentée : l’exécution finale a dépassé la limite du runner à 120 s après 13 tests, sans assertion en échec ; les 145 tests fonctionnels et le checker restent distinctement exécutés. Maven/JUnit/JaCoCo cible exige JDK25 alors que le runner fournit JDK 21.0.11 et aucun Maven système ; `./mvnw --version` sort 2. Go 1.26.5 exact tente son téléchargement mais le réseau bloque `proxy.golang.org`; Node cible 24.18.1 n’est pas disponible (Node local 22.16.0). Docker Desktop/CLI et `pwsh` sont absents ; les apply/verify/rollback PostgreSQL/Oracle live de `0028/0029`, `smoke` et `ha-smoke` restent des gates de promotion.

Voir `docs/dcim-physical-infrastructure.md`.

---

# InfraNexum 2.0.0-alpha.0.81 — état d’implémentation

## alpha.0.81 — PGM-07-E04 sites, bâtiments, étages, salles et zones

`alpha.0.81` implémente le gate roadmap **« hiérarchie physique, adresses et contrôles de cohérence »**. Le bounded context DCIM possède désormais un registre canonique Site/Building/Floor/Room/Zone. Organisation/Subdivision restent des références faibles ; la seule relation forte inter-objet est le parentage interne DCIM. L’unicité du code est évaluée dans le scope normatif : Subdivision pour Site, parent direct pour Building/Floor/Room, Site racine pour Zone.

Le Site exige une adresse structurée complète (ligne 1, code postal, ville, pays ISO alpha-2, timezone IANA), avec latitude/longitude optionnelles. Building exige `floorCount`; Floor exige `levelNumber`; Room exige `areaM2` et peut être `LOCKED`; Zone exige un type technique parmi cooling/power_distribution/airflow/security. Les champs propres à un type sont rejetés sur les autres types dans le domaine **et** par les contraintes SQL PostgreSQL/Oracle. L’archivage/suppression d’un Site est bloqué uniquement par ses Buildings actifs, conformément au CDC.

La tranche comprend capability `dcim.facilities`, 26 permissions organisation-scoped, transactions JDBC/outbox/idempotence/version optimiste, migrations `0026/0027`, API/OpenAPI 3.1 avec 25 opérations explicites, CLI Server et parité Web immédiate. Le workspace DCIM fournit navigation capability-gated, listes/détail/formulaires/transitions et sélecteurs en cascade Organisation→Subdivision→Site→Building→Floor→Room sans saisie libre d’UUID, ainsi que les cinq locales DE/EN/ES/FR/IT.

**EXÉCUTÉ à ce stade** — Source Integrity **45/45 à 100 %**, 0 violation ; Architecture fonctionnelle **140/140** et Architecture-as-Code `PASS` ; migrations **104/104**, couverture **99 %**, 0 violation ; Web **144/144**, couverture **99,71 % lignes / 98,49 % branches / 100 % fonctions**, process smoke `passed` ; **18** targets Java dependency-free ; Toolchains **25/25** (99 %), Eventing **10/10** (100 %), Persistence **12/12** (98 %), Capabilities **10/10** (99 %), Entitlements **10/10** (100 %), Audit **8/8** (100 %), tous les checkers à 0 violation ; Compose **63/63** ; Agent local Go 1.23.2 `vet + race + tests + build`, couverture **98,4 %**. La compilation syntaxique des JUnit DCIM passe sous `javac -Xlint:all -Werror` avec stubs JUnit temporaires hors produit.

**NON EXÉCUTÉ** — couverture Architecture instrumentée complète : même fragmentée, l’instrumentation des scans de dépôt dépasse la limite du runner ; les 140 tests fonctionnels restent distinctement exécutés. Les gates exacts JDK25/Maven, Go 1.26.5, Node 24.18.1, Docker Desktop PRO/HA et migrations PostgreSQL/Oracle live restent obligatoires avant promotion lorsqu’ils sont indisponibles dans l’environnement de génération.

Voir `docs/dcim-facility-hierarchy.md`.

---

# InfraNexum 2.0.0-alpha.0.80 — état d’implémentation

## alpha.0.80 — parité fonctionnelle Web RSOT / ITAM

`alpha.0.80` est une tranche corrective d’intégration verticale, sans nouvel epic métier. Elle ferme l’écart identifié après `alpha.0.79` : RSOT et ITAM disposaient de domaines, persistence, API/CLI et clients HTTP navigateur, mais leurs fonctions administrables n’étaient pas réellement montées dans le shell Web. Désormais, une fonctionnalité administrable n’est considérée comme supportée par l’IHM que si elle possède une route publiée, une navigation capability-gated, un workspace utilisable, les workflows opérateur nécessaires et des tests d’interaction correspondants.

RSOT et ITAM deviennent des routes de premier niveau `#/rsot` et `#/itam`. RSOT expose les objets canoniques en lecture organisation-scoped, le Schema Registry et les profils composables. ITAM expose Partners, Assets et Compliance avec vues liste/détail, formulaires et transitions déjà supportées par les contrats serveur. Les relations métier sont choisies depuis les catalogues gouvernés plutôt que par saisie d’UUID : Organization filtre Subdivision et, par transitivité, objets RSOT, partenaires, actifs, constructeurs/éditeurs/fournisseurs/supports, autorisations et gardiens disponibles. Les dates/datetimes utilisent les contrôles temporels InfraNexum et les cinq locales DE/EN/ES/FR/IT couvrent les nouveaux workspaces.

Deux surfaces de lecture minimales ont été ajoutées au Server parce qu’elles sont nécessaires à une UI correcte sans créer de nouvelle autorité : consultation des objets RSOT canoniques et consultation/détail des autorisations/contrats Compliance. La lecture RSOT est protégée par la permission normative organisation-scoped `rsot.read`, ajoutée par la migration paire PostgreSQL/Oracle `0025-identity-access-rsot-read-permission`. Les schémas de réponse OpenAPI Compliance ont aussi été alignés exactement sur les DTO Java afin d’éliminer une divergence de contrat héritée.

Le navigateur reste fail-closed : une capability absente retire route et navigation; une navigation directe vers une route indisponible revient vers Overview. Le Server conserve l’autorité RBAC/ABAC; l’IHM n’invente pas de droit client. Une propriété JSON inconnue reste rejetée côté Server et les secrets de licence restent interdits tant que `PGM-13-E02` n’est pas livré. Un octet NUL découvert dans `itam-assets.mjs` a été supprimé et un test parcourt désormais les assets navigateur pour bloquer toute régression similaire.

### Validation alpha.0.80

**EXÉCUTÉ à ce stade** — Web **136/136**, couverture **99,71 % lignes / 98,48 % branches / 100 % fonctions**, process smoke `passed`; migrations **98/98**, couverture **99 %**, checker 0 violation; Architecture fonctionnelle **134/134** et Architecture-as-Code `PASS`; **17** targets Java dependency-free sans échec; Source Integrity **45/45** à **100 %**; Toolchains **25/25** (99 %), Eventing **10/10** (100 %), Persistence **12/12** (98 %), Capabilities **10/10** (99 %), Entitlements **10/10** (100 %) et Audit **8/8** (100 %), tous les checkers à 0 violation; Compose **63/63**. La collecte de couverture Architecture de cette baseline reste à distinguer des 134 tests fonctionnels : l’instrumentation agrégée dépasse la limite du runner et ne sera pas présentée comme exécutée tant qu’une collecte complète n’est pas obtenue.

Les gates exacts JDK25/Maven, Go 1.26.5, Node 24.18.1, Docker Desktop PRO/HA et migrations PostgreSQL/Oracle live restent obligatoires avant promotion lorsqu’ils ne peuvent pas être exécutés dans l’environnement de livraison.

Voir `docs/web-functional-parity.md`.

---

# InfraNexum 2.0.0-alpha.0.79 — état d’implémentation

## alpha.0.79 — PGM-07-E03 garanties, supports et licences ITAM

`alpha.0.79` remplace le verrou transitoire de conformité d’E02 par la gouvernance contractuelle réelle requise par le CDC. Le matériel porte désormais une référence canonique nullable `producerPartnerId` vers son constructeur et le logiciel vers son éditeur. Cette évolution reste rétrocompatible pour les actifs historiques : aucune valeur n’est inventée lors de la migration, les anciens appels Java restent compilables, mais un actif dépourvu de producteur ne peut pas franchir un état opérationnel tant qu’il n’est pas corrigé par la mutation versionnée `setProducer`.

Les garanties constructeurs, contrats de licence logiciels, autorisations de supports tiers, couvertures de support et types de garantie sont des agrégats ITAM versionnés et audités. Une garantie opérationnelle doit être active, vérifiée, complète et correspondre au constructeur canonique de l’actif. Une licence doit être active et correspondre à l’éditeur canonique. Une couverture tierce n’est recevable que si l’autorisation active du prestataire couvre explicitement le constructeur, le type d’objet RSOT, la subdivision, la période et le niveau de service. La suspension d’une autorisation place transactionnellement les couvertures actives correspondantes en `REVIEW_REQUIRED`; elle ne réécrit jamais les dates de garantie ou de support constructeur.

Le service de readiness E02 est maintenant alimenté par les contrats E03. Les transitions `IN_STOCK`, `ASSIGNED` et `DEPLOYED` restent fail-closed lorsque la capability `itam.compliance` est indisponible ou lorsque la preuve requise manque. Les échéances sont surveillées sur les seuils configurables `180,120,90,60,30,15,7,1` jours par défaut, avec déduplication persistante et transitions explicites vers `EXPIRED`. Un journal de révisions contractuelles append-only conserve chaque version de preuve indépendamment de l’audit transverse.

Les clés de licence, product keys, serial keys et secrets d’activation restent hors contrat E03 tant que `PGM-13-E02` Secret Service/PKI/KMS n’est pas livré. L’API rejette les propriétés JSON inconnues et la CLI/Web refusent explicitement les noms de champs de secrets connus au lieu de les ignorer ou de les stocker. Les migrations `0023-itam-warranty-support-license` et `0024-identity-access-itam-compliance-permissions` sont symétriques PostgreSQL/Oracle et ne créent aucune FK inter-bounded-context.

La tranche expose `itam.compliance`, les permissions `itam.warranty.read/manage`, `itam.support_coverage.read/manage`, `itam.support_catalog.manage` et `itam.license.read/manage`, tout en réutilisant `itam.audit.read`. Elle fournit API/OpenAPI 3.1, CLI Server, client Web capability-gated et scheduler contractuel multi-nœud dédupliqué.

### Validation alpha.0.79

**EXÉCUTÉ** — Architecture fonctionnelle **130/130**, couverture instrumentée **100 %** et Architecture-as-Code `PASS`; migrations **95/95**, checker à 0 violation; Compose **63/63**; Web **127/127**, couverture **99,71 % lignes / 98,48 % branches / 100 % fonctions**, process smoke `passed`; **17** targets Java dependency-free passent, dont Partner, Asset, Compliance, JDBC, capabilities et Policy/RBAC. Les tests JUnit ITAM existants et E03 ont été compilés strictement contre les contrats réels avec `javac -Xlint:all -Werror` au moyen de stubs JUnit temporaires hors produit; cette preuve de compilation ne remplace pas l’exécution Maven/JUnit.

**EXÉCUTÉ sur toolchain locale** — l’Agent passe `vet`, tests `-race`, couverture et build avec `GOTOOLCHAIN=local` sous Go 1.23.2; couverture **98,4 %**. **NON EXÉCUTÉ sur toolchain cible** — Go 1.26.5 ne peut pas être téléchargé par ce runner isolé. Maven/JUnit/JaCoCo cible requiert JDK 25 et Maven 3.9.16 (runner JDK 21.0.11, Maven absent, wrapper en sortie 2); Node cible 24.18.1 (runner Node 22.16.0); Docker/PowerShell, PostgreSQL live et Oracle live sont indisponibles. Les applications/verify/rollback live de `0023/0024`, `up`, `smoke` et `ha-smoke` PRO restent obligatoires avant promotion.

Voir `docs/itam-compliance.md`.

---

# InfraNexum 2.0.0-alpha.0.78 — état d’implémentation

## alpha.0.78 — PGM-07-E02 cycle de vie des actifs ITAM

`alpha.0.78` introduit l’actif patrimonial ITAM canonique sans dupliquer les autorités RSOT, Organization, Partner, IAM ou DCIM. L’agrégat conserve des weak references vers ces contextes et porte exclusivement les données patrimoniales de l’epic : date et valeur d’acquisition, devise, état de cycle de vie, gardien courant, version optimiste et chaîne de possession append-only. Le cycle couvre acquisition, réception, stock, affectation, déploiement, transfert de garde, maintenance, retour, retrait et disposition certifiée.

Chaque mutation est idempotente, vérifie `expectedVersion`, s’exécute dans la même unité de travail JDBC que l’outbox et ajoute un événement de garde dont la séquence correspond à la version résultante. La disposition est impossible sans référence de preuve. Le lien RSOT est unique et les migrations `0021-itam-asset-lifecycle` / `0022-identity-access-itam-asset-permissions` restent symétriques PostgreSQL/Oracle et sans FK inter-bounded-context.

Le CDC exige une garantie/support complet pour le matériel et un contrat de licence complet pour le logiciel avant exploitation. Ces contrats appartiennent à `PGM-07-E03`, dépendant de cet epic. Pour empêcher une fenêtre de non-conformité, le port `AssetOperationalReadinessPolicy` est obligatoire dès E02 et le runtime `alpha.0.78` fournit une implémentation transitoire fail-closed : `IN_STOCK`, `ASSIGNED` et `DEPLOYED` sont refusés avec `ITAM_ASSET_COMPLIANCE_GATE_UNAVAILABLE` tant que E03 n’est pas présent.

La tranche expose `itam.assets`, le quota `itam.assets.max`, les permissions `itam.asset.read/create/update`, l’API/OpenAPI 3.1, la CLI Server et le client Web capability-gated. L’API HTTP reste désactivée par défaut par `INFRANEXUM_ITAM_ASSET_API_ENABLED=false`.

### Validation alpha.0.78

**EXÉCUTÉ** — Architecture **123/123** et couverture instrumentée **100 %**, Architecture-as-Code `PASS`; migrations **87/87**, couverture **99 %**, checker à 0 violation; Source Integrity **45/45** à **100 %**; Toolchains **25/25** (99 %), Eventing **10/10** (100 %), Persistence **12/12** (98 %), Capabilities **10/10** (99 %), Entitlements **10/10** (100 %) et Audit **8/8** (100 %), tous les checkers à 0 violation; Compose **63/63**; Web **119/119**, couverture **99,70 % lignes / 98,46 % branches / 100 % fonctions**, process smoke `passed`; **16** targets Java dependency-free passent sous Java 21 avec `javac -Xlint:all -Werror`, dont le smoke Asset. Les **14** méthodes JUnit Asset ont en outre été compilées strictement contre les contrats réels au moyen de stubs JUnit temporaires hors produit; elles n'ont pas été exécutées comme tests JUnit.

**EXÉCUTÉ sur toolchain locale** — l'Agent passe `vet`, tests `-race`, couverture et build avec `GOTOOLCHAIN=local` sous Go 1.23.2; couverture **98,4 %**. **NON EXÉCUTÉ sur toolchain cible** — Go 1.26.5 ne peut pas être téléchargé par ce runner isolé. Maven/JUnit/JaCoCo cible requiert JDK 25 et Maven 3.9.16 (runner JDK 21.0.11, Maven absent, wrapper en sortie 2); Node cible 24.18.1 (runner Node 22.16.0); Docker/PowerShell, PostgreSQL live et Oracle live sont indisponibles. Les applications/verify/rollback live de `0021/0022`, `up`, `smoke` et `ha-smoke` PRO restent obligatoires avant promotion.

Voir `docs/itam-asset-lifecycle.md`.

---

# InfraNexum 2.0.0-alpha.0.77 — état d’implémentation

## alpha.0.77 — PGM-07-E01 Partenaires et catalogues ITAM

`alpha.0.77` implémente `PGM-07-E01` sans dupliquer les autorités métier : un seul agrégat `Partner` porte les rôles `manufacturer`, `software_publisher`, `supplier`, `third_party_support_provider`, `integrator` et `recycler`. Les catalogues constructeurs/éditeurs/supports sont des vues filtrées de cet agrégat. Le cycle gouverné est `DRAFT → PENDING_APPROVAL → ACTIVE`, puis `SUSPENDED` ou `RETIRED` selon les transitions autorisées ; `RETIRED` est terminal. Les références Organisation/Subdivision sont des weak references validées par le bounded context Organization, sans FK inter-contexte.

La création et les transitions sont idempotentes, versionnées et transactionnelles avec l’outbox. La détection de doublons couvre le code et les jetons d’identité normalisés ; le quota effectif `itam.partners.max` est relu au moment de la requête. L’autorisation HTTP est en deux temps : authentification/PEP global, puis `ScopedAuthorizationGuard` sur l’organisation réellement liée à la requête afin d’appliquer RBAC et ABAC sans transformer un rôle organisationnel en permission plateforme. La persistance est fournie par `0019-itam-partner-foundation` et les six permissions normatives par `0020-identity-access-itam-partner-permissions`.

L’API `/api/v1/itam/partners` fournit recherche filtrée, création, soumission pour approbation, autorisation et suspension, avec `problem+json`, `Idempotency-Key` et `If-Match`. La CLI Server expose les mêmes use cases avec authentification locale par fichier de secret, JSON/texte et `--dry-run`. Le client Web est capability-gated, CSRF-protected et aligne strictement les bornes du contrat Server/OpenAPI. Les garanties, contrats de support et couvertures restent hors périmètre de cet epic et seront traités par les epics ITAM dédiés.

### Validation alpha.0.77

**EXÉCUTÉ** — tests fonctionnels Architecture **117/117** rejoués par fichiers et Architecture-as-Code `PASS`; migrations **81/81**, checker à 0 violation; Capabilities **10/10**, checker à 0 violation; Web **112/112**, couverture runtime **99,70 % lignes / 98,43 % branches / 100 % fonctions**, process smoke `passed`; **15** targets Java dependency-free sans échec sous Java 21 avec `javac -Xlint:all -Werror`, dont le smoke ITAM de bout en bout. Les validations transverses restantes et le packaging sont rejoués sur le snapshot final avant livraison.

**NON EXÉCUTÉ** — Maven/JUnit/JaCoCo cible JDK 25/Maven 3.9.16, Agent Go 1.26.5, Node 24.18.1 exact, Docker Desktop PRO/HA, migrations live PostgreSQL/Oracle et validation navigateur cible restent indisponibles dans ce runner et obligatoires avant promotion.

Voir `docs/itam-partner-catalogue.md` pour les contrats et le rollback.

---

## alpha.0.76 — PGM-06-E03 Core Schema Registry et profils composables

`alpha.0.76` implémente le registre Core Contracts/Compatibility prévu par `PGM-06-E03`. Les schémas JSON sont identifiés par clé + version sémantique et suivent le cycle `DRAFT → PUBLISHED → DEPRECATED`; une publication est immutable. Une révision optimiste indépendante de la version sémantique protège les modifications concurrentes et alimente `ETag`/`If-Match` sur l’API.

L’analyse de compatibilité est fail-closed : suppressions, réduction de types, optional→required, retrait d’enum et changements de format sont `BREAKING`; une publication breaking exige une référence d’approbation. Les contraintes dont la compatibilité ne peut pas être démontrée automatiquement sont `INDETERMINATE` et bloquent la publication. Les extensions `RSOT_EXTENSION` restent strictement déclaratives : aucun script/expression/I/O et aucun `$ref` externe ne sont admis.

Les profils composables sont versionnés séparément, ordonnés, bornés et ne peuvent contenir que des schémas publiés. La persistance est livrée par `0017-core-schema-registry` (Core, sans FK inter-contexte) et `0018-identity-access-rsot-schema-permissions` (IAM, six permissions normatives et bootstrap `system.platform_admin`). Les mêmes use cases sont exposés via API/OpenAPI, CLI Server et client Web capability-gated `rsot.core`.

### Validation alpha.0.76

**EXÉCUTÉ** — Architecture fonctionnelle **111/111** et Architecture-as-Code `PASS`; migrations **75/75**, couverture **99 %**, 0 violation; Toolchains **25/25** (99 %), Eventing **10/10** (100 %), Persistence **12/12** (98 %), Capabilities **10/10** (99 %), Entitlements **10/10** (100 %), Audit **8/8** (100 %), tous les checkers à 0 violation; contrats Compose **63/63**; Web **107/107**, couverture **99,69 % lignes / 98,43 % branches / 100 % fonctions**, process smoke `passed`; **14** targets Java dependency-free exécutés avec `javac -Xlint:all -Werror` sous Java 21, incluant Core Schema Registry et JDBC. Source Integrity **45/45** à 100 % passe sur le snapshot Git de packaging avec **870 checksums de blobs Git** et 0 violation ; Archive Compatibility **12/12** à 100 % et le contrôle du candidat `git archive` passent avec 0 violation.

**NON EXÉCUTÉ** — la couverture Architecture instrumentée agrégée dépasse la limite d’exécution du runner malgré la suite fonctionnelle 111/111; la cible Maven/JUnit/JaCoCo requiert JDK 25 et Maven 3.9.16, indisponibles localement; la cible Agent requiert Go 1.26.5 mais le runner ne peut pas télécharger l’auto-toolchain; Node cible 24.18.1 n’est pas disponible (tests locaux exécutés sous Node 22.16.0); Docker n’est pas installé et aucune instance Oracle n’est disponible. Les migrations PostgreSQL/Oracle live, les smokes Docker PRO/HA et les gates Maven/JDK25 restent donc obligatoires avant promotion.

Voir `docs/rsot-schema-registry.md` pour le contrat fonctionnel et le rollback.

---

## alpha.0.75 — formulaires IAM pilotés par les entités et calendrier déterministe

`alpha.0.75` remplace les identifiants structurés saisis manuellement dans Identity & Access par des sélecteurs alimentés uniquement par les entités réellement lisibles par l’opérateur. Les dépendances sont synchronisées : Organisation → Subdivisions, type d’acteur/membre → Utilisateurs ou Groupes, Rôle → Affectations révocables, et le catalogue des Policies dispose d’une lecture dédiée protégée. Les listes inaccessibles restent désactivées fail-closed au lieu d’accepter un UUID arbitraire.

Les contrôles temporels utilisent désormais un calendrier InfraNexum déterministe, indépendant des popups natifs Windows/Chromium, tout en conservant le vrai champ `date`/`datetime-local` comme source FormData. Le Server conserve la responsabilité de la conversion et du fuseau par défaut. L’organisation reste inspirée de FreeIPA (navigation fonctionnelle, liste → sélection → actions contextuelles), mais la palette et les composants restent InfraNexum, enrichis d’accents navy/cyan/orange inspirés de la présence Web IONOS actuelle.

### Validation alpha.0.75

Validation locale exécutée : Web **102/102** (99,69 % lignes, 98,42 % branches, 100 % fonctions) et smoke Web réussi ; Architecture **104/104** + Architecture-as-Code PASS ; migrations **69/69** avec 0 violation ; contrats Compose **63/63** ; Toolchains 25/25, Eventing 10/10, Persistence 12/12, Capabilities 10/10, Entitlements 10/10, Audit 8/8 ; **13** smokes Java dependency-free réussis. Le navigateur Chromium du sandbox bloque les URLs localhost par politique d’entreprise, donc la preuve interactive finale calendrier/sélecteurs reste à exécuter sous Docker Desktop/navigateur cible. La promotion reste interdite tant que les gates cible Docker/JDK25/Oracle applicables ne sont pas exécutés.

---

## Historique alpha.0.74 — état d’implémentation

## alpha.0.74 — calendriers natifs et résolution temporelle côté serveur

`alpha.0.74` remplace les saisies libres de date/heure IAM et PAP/PDP par des contrôles navigateur `datetime-local`. Le navigateur transmet la valeur locale sans inventer de fuseau. À la frontière HTTP, `ServerTemporalInputParser` convertit la valeur en `Instant` : un offset ou fuseau explicitement fourni reste prioritaire ; sans fuseau, `ZoneId.systemDefault()` du Server est utilisé. Les heures locales inexistantes ou ambiguës lors des transitions DST sont refusées en 400 afin d’éviter toute correction silencieuse. Le parseur fournit également une conversion `LocalDate` dédiée pour les futurs champs de date pure, sans leur inventer d’heure.

Le contrat OpenAPI documente cette double forme (`date-time` avec offset ou date-time locale) avec `x-infranexum-timezone-default: server`. La gestion d’erreurs IAM couvre aussi `PolicyController`, de sorte qu’une valeur temporelle invalide reçoit un problème HTTP 400 stable. Aucune migration, permission RBAC/ABAC ou règle SoD n’est modifiée.

### Validation alpha.0.74

- calendrier/date-time : 9 champs `effectiveFrom/effectiveTo` convertis en `datetime-local` ;
- Web local : **95/95**, couverture **99,69 % lignes / 98,42 % branches / 100 % fonctions**, process smoke `passed` ;
- temporal-input smoke : **PASS** sur offset explicite, timezone Server, `LocalDate` et rejets DST ;
- Architecture : **101/101** et Architecture-as-Code **PASS** ;
- migrations : **69/69**, 0 violation ; Compose : **63/63** ;
- Toolchains 25/25, Eventing 10/10, Persistence 12/12, Capabilities 10/10, Entitlements 10/10, Audit 8/8 ;
- 13 targets de smoke Java existants : sortie 0 ;
- packaging/replay post-extraction : à exécuter après le snapshot final.

# InfraNexum 2.0.0-alpha.0.73 — état d’implémentation

## alpha.0.73 — accès IAM partiel, interface fluide et sélecteurs stables

`alpha.0.73` corrige trois défauts Web observés sur `Identity & Access` sans modifier les contrats Server, RBAC, ABAC, SoD ni le catalogue de migrations. Le chargement des listes Users/Groups/Roles/Permissions est désormais indépendant : un refus `*.search` sur une rubrique est rendu dans cette liste uniquement et ne met plus l’ensemble du workspace en erreur. Cela ferme notamment la régression où `INFRANEXUM_AUTHORIZATION_DENIED` sur `iam.role.search` apparaissait dès l’ouverture de la page. Une mutation autorisée reste également considérée comme réussie même si le rechargement de sa liste est ensuite restreint.

Le canvas d’administration utilise maintenant toute la largeur disponible et les listes/data-tables reprennent explicitement le langage visuel InfraNexum (surfaces, en-têtes teintés, lignes alternées, hover/focus, sticky headers, états restreints). Les formulaires IAM ne sont plus artificiellement limités à 58 rem.

Les `select.form-select` sont enrichis après bootstrap par un composant combobox/listbox InfraNexum accessible. Le `<select>` natif reste dans le formulaire comme source de vérité pour `FormData`, validation et événements `change`, mais l’interaction souris/clavier passe par une surface déterministe qui élimine le défaut Chromium/Windows de fermeture au relâchement du clic.

### Validation alpha.0.73

**EXÉCUTÉ** — tests ciblés IAM/Stable Select et interaction Chromium réelle `mousePressed→mouseReleased` : menu maintenu ouvert, sélection de l’option suivante, un seul événement `change`. Suite Web locale 95/95 et process smoke `passed` avant gel de version. Les gates complets sont rejoués après versionnement et packaging avant publication de l’archive.

**NON EXÉCUTÉ** — validation interactive finale sous Docker Desktop/Chrome Windows de l’opérateur, requise pour fermer le défaut d’origine sur la plateforme cible.

## alpha.0.72 — organisation Identity & Access inspirée de FreeIPA

`alpha.0.72` réorganise exclusivement la surface Web `Identity & Access` sans modifier les contrats Server, RBAC, ABAC, SoD ni le catalogue de migrations. L’interface adopte une architecture d’information inspirée de FreeIPA : navigation fonctionnelle `Identity` / `Access control` / `Authorization policy`, facettes orientées liste, recherche locale, actualisation et ajout en barre d’outils, puis vues d’actions contextuelles affichées une à une après sélection de l’objet.

Les 19 formulaires IAM/PAP-PDP existants conservent leurs IDs et leurs handlers afin de préserver le câblage API/CSRF corrigé en `alpha.0.71`. Les sélections d’utilisateurs, groupes, rôles et permissions préremplissent désormais la vue `Settings` correspondante. La navigation principale et les vues d’actions sont accessibles au clavier; l’interface reste Bootstrap 5, responsive et internationalisée DE/EN/ES/FR/IT.

### Validation alpha.0.72

**EXÉCUTÉ** — Web 92/92 avec 99,69 % lignes, 98,42 % branches et 100 % fonctions ; process smoke `passed`. Les 7 gates Architecture spécifiques à la nouvelle organisation IAM passent. Architecture complète 96/96 + Architecture-as-Code `PASS`; migrations 69/69; Toolchains 25/25; Eventing 10/10; Persistence 12/12; Capabilities 10/10; Entitlements 10/10; Audit 8/8; contrats Compose 63/63; Source Integrity 45/45 à 100 % et 0 violation; 13 targets de smoke Java sortent avec code 0.

**NON EXÉCUTÉ** — Docker Desktop PRO et validation interactive dans un navigateur réel de la navigation liste→détail et des soumissions IAM après cette réorganisation. Ces preuves restent requises avant promotion ; aucun contrat Server/RBAC/ABAC/SoD ni aucune migration n'a changé dans `alpha.0.72`.

## alpha.0.71 — stabilisation Web Identity & Access

**Statut : correction fonctionnelle et UX implémentée ; validation Docker Desktop cible requise avant promotion.**

La page `Identity & Access` est restructurée en cinq sous-rubriques exclusives — Utilisateurs, Groupes, Rôles, Permissions et Policies — au lieu d'un accordéon monolithique. Les formulaires sont des cartes d'opération, les identifiants des ressources sont visibles, et l'action `Sélectionner` préremplit les formulaires liés afin d'éviter la saisie manuelle d'UUID opaques. Les suppressions impossibles d'objets système ne sont plus proposées.

Le défaut de soumission est traité à la frontière navigateur : tous les formulaires IAM et PAP/PDP utilisent désormais un contrôleur asynchrone commun qui écoute explicitement les clics des boutons **et** l'événement natif `submit`, conserve la validation HTML, sérialise une seule mutation à la fois et restaure toujours les boutons après succès/échec. Les erreurs API/CSRF/validation sont affichées dans un feedback IAM visible avec code stable plutôt que masquées derrière un simple état générique. Les tests de non-régression reproduisent le chemin où un clic n'est suivi d'aucun second événement `submit` et exigent malgré tout une exécution unique de la mutation.

Aucun contrat RBAC/ABAC, aucune migration ni permission n'est modifié par cette tranche.

### Validation alpha.0.71

**EXÉCUTÉ** — Web 90/90 sous Node local avec 99,69 % lignes, 98,42 % branches et 100 % fonctions ; process smoke `passed`. Les 4 scénarios comportementaux du contrôleur/navigation IAM passent, ainsi que 5 gates Architecture dédiés. Architecture complète 94/94 + Architecture-as-Code `PASS`; migrations 69/69; Toolchains 25/25; Eventing 10/10; Persistence 12/12; Capabilities 10/10; Entitlements 10/10; Audit 8/8; contrats Compose 63/63.

**NON EXÉCUTÉ** — navigateur Chromium réel dans le sandbox (l'environnement headless local retourne `chrome-error://chromewebdata/` même sur le serveur HTTP de test), Node 24.18.1 cible, Docker Desktop PRO et interaction IAM réelle via l'ingress. Ces preuves restent obligatoires avant promotion.

## PGM-03-E04 — PAP/PDP/PEP/PIP/PRP, ABAC et SoD statique

`alpha.0.70` implémente l’autorisation avancée Pro/Enterprise au-dessus du RBAC `alpha.0.68` et des contrats d’autorité RSOT `alpha.0.69`. Le PAP administre des versions immuables de politiques déclaratives ; le PDP produit exclusivement `permit`, `deny`, `not_applicable` ou `indeterminate` avec stratégie deny-overrides ; le PIP reconstruit les attributs de confiance côté Server ; le PRP JDBC stocke les politiques et contraintes SoD ; les PEP HTTP et CLI appliquent toute décision autre que `permit` comme un refus. Lite reste explicitement sans ABAC.

Le langage de policy est fermé et déterministe : aucune expression exécutable, aucun accès réseau/fichier et aucune horloge implicite. Le cycle est `draft → validated → approved → active → deprecated → retired`, avec approbateur distinct du propriétaire, activation atomique et rollback par réactivation d’une version dépréciée. Le policy système `system.rbac-bridge` préserve les autorisations RBAC existantes mais le PDP refuse toujours si le RBAC de base refuse ; ABAC ne peut donc jamais élever un refus RBAC. Les obligations non encore enforceables (`STEP_UP_MFA`, approbation et contrôles de champs) échouent fermé ; `REQUIRE_JUSTIFICATION` est enforceable sur HTTP et CLI.

La migration paire `0016-identity-access-abac-sod` ajoute policies, rules, conditions et contraintes SoD dans le seul bounded context IAM, avec index PostgreSQL/Oracle garantissant l’unicité des versions actives y compris au scope plateforme. La SoD statique est évaluée avant toute persistance d’affectation de rôle. L’API normative expose uniquement création/validation/approbation/activation, décision et explication ; l’explication ne sérialise aucun attribut PIP sensible. L’IHM Web Pro/Enterprise utilise exactement ces endpoints, reste capability-gated et conserve DE/EN/ES/FR/IT.

### Validation courante

**EXÉCUTÉ** — Source Architecture fonctionnelle 89/89 et Architecture-as-Code `PASS`; migrations 69/69 (99 %, 0 violation); Toolchains 25/25; Eventing 10/10; Persistence 12/12; Capabilities 10/10; Entitlements 10/10; Audit 8/8; contrats Compose 63/63. Le Web passe 86/86 sous Node local 22.16.0 avec 99,69 % lignes, 98,42 % branches, 100 % fonctions et process smoke `passed`. `java-policy-smoke` passe, incluant lifecycle quatre-yeux, rollback, deny-overrides, PIP/PRP fail-closed et SoD; son benchmark de 2 000 décisions mises en cache observe un P95 local de 0,173 ms pour une cible draft.21 <50 ms. `JdbcAccessPolicyRepository` passe 10/10 scénarios déterministes PostgreSQL/Oracle à assertions réelles, et les smokes JDBC stricts restent verts.

**NON EXÉCUTÉ** — Maven/JUnit/JaCoCo complet sous Temurin 25.0.4+7 / Maven 3.9.16, faute de JDK25/cache Maven/réseau dans l’environnement local; Docker Desktop PRO avec upgrade réel `0016`; PostgreSQL live de la nouvelle migration; Oracle apply/verify/rollback; Web sous Node 24.18.1 exact; Agent sous Go 1.26.5. Ces preuves restent obligatoires avant promotion de `alpha.0.70`.

## alpha.0.69 — PGM-04-E02 / PGM-06-E01 — isolation des contextes et fondation RSOT

`alpha.0.69` a supprimé les FK IAM→Organization interdites via la migration additive `0014`, puis introduit la fondation RSOT isolée et sa matrice d’autorité via `0015`. Cette baseline est conservée sans réécriture et sert de prérequis direct au PAP/PDP de `alpha.0.70`.

## alpha.0.68 — PGM-03-E03 RBAC foundation

`alpha.0.68` implémente le bounded context `identity-access` avec utilisateurs IAM, memberships Organisation/Subdivision temporels, groupes, groupes imbriqués lorsque le profil l’autorise, rôles protégés, permissions atomiques approuvées et affectations USER/GROUP scopées. Les suppressions sont logiques, les rôles/permissions système sont protégés et les mutations sensibles produisent audit append-only et événements outbox dans l’unité de travail JDBC.

Le PEP HTTP Server est deny-by-default : toute route `/api/v1/**` authentifiée doit être enregistrée explicitement. API, CLI Server et Web utilisent les mêmes cas d’usage RBAC. Le bootstrap conserve le UUID du `local_account` comme `iam_user.id` et garantit `system.platform_admin` pour éviter tout lockout lors d’une installation neuve ou d’un upgrade. L’ABAC/PDP/SoD complet reste hors périmètre et relève de PGM-03-E04.

Le catalogue `draft.21` ne définit pas `organization.read`. Aucun code de permission n’est inventé : les lectures racine d’organisation sont autorisées par membership IAM effectif ou rôle plateforme, tandis que les subdivisions utilisent les permissions approuvées `organization.subdivision.read/search`.

### Validation de clôture locale

**EXÉCUTÉ** — Source Integrity 45/45 (100 %, 0 violation), Archive Compatibility 12/12, Architecture fonctionnelle 67/67 avec contrôle CLI à 0 violation, Toolchains 25/25, migrations 50/50, Eventing 10/10, Persistence 12/12, Capabilities 10/10, Entitlements 10/10, Audit 8/8 et contrats Compose 63/63. Le Web passe 79/79 avec 99,68 % de couverture lignes, 98,39 % branches et 100 % fonctions ; le process smoke retourne `status=passed`. Les 11 smokes Java dependency-free du Makefile passent sous le JDK 21 local avec `javac -Xlint:all -Werror`. Les scénarios comportementaux RBAC passent 22/22 et le registre HTTP deny-by-default 6/6 dans le harness Java local.

La passe de clôture corrige également une régression d’industrialisation héritée : les targets JDBC du Makefile compilaient l’ensemble des adapters sans les domaines/ports qu’ils implémentent. Les targets partagent désormais `JDBC_DOMAIN_SOURCES` pour `identity-local`, `identity-access` et `organization`; un test Architecture dédié verrouille cet ordre de dépendances.

**ÉCHOUÉ puis corrigé dans la révision source** — le build Docker Desktop utilisateur a exécuté Temurin 25.0.4+7 et Maven 3.9.16 jusqu’au module Server, puis a échoué à la compilation de `IdentityAccessController` parce que deux références au catalogue `PermissionCodes` n’étaient pas importées. L’import explicite `io.infranexum.identity.access.domain.PermissionCodes` est désormais présent et un test de non-régression RBAC dédié verrouille les références `GROUP_ADD_GROUP` et `GROUP_REMOVE_GROUP`. Le même build signalait aussi l’alias Spring 7 déprécié `HttpStatus.UNPROCESSABLE_ENTITY`; il est remplacé par `HttpStatus.UNPROCESSABLE_CONTENT` (HTTP 422 inchangé) et couvert par une seconde assertion de non-régression. Le rebuild Docker suivant a ensuite compilé les 63 sources Server sous Java 25 mais a échoué pendant `spring-boot:repackage`, car `InfraNexumServerApplication` et `IdentityAccessCliApplication` exposaient tous deux une méthode `main` sans classe canonique configurée. Le `spring-boot-maven-plugin` déclare désormais explicitement `io.infranexum.server.InfraNexumServerApplication` comme `mainClass`; la CLI reste une entrée Server distincte appelée explicitement. Un test de non-régression vérifie ce contrat de packaging. Le même build Java 25 signalait enfin `ActivationManifestJsonCodec` pour l’usage déprécié de `JsonNode.asText()` sous Jackson 3; le codec utilise désormais `asString()` après son contrôle strict `isString()`, sans élargir les coercitions acceptées, et un test verrouille l’absence de l’API dépréciée. Le candidat corrigé `alpha.0.68-rev2` a ensuite été exécuté par l’opérateur sur Docker Desktop : `up`, `smoke` et `ha-smoke` ont été rapportés tous verts, ce qui clôt le gate runtime PRO de PGM-03-E03. **NON EXÉCUTÉ dans l’environnement local de génération** — JUnit/JaCoCo complet sous JDK 25 ; validation Web sous Node 24.18.1, le runtime local disponible étant Node 22.16.0 ; Agent sous Go 1.26.5 ; exécution Oracle réelle. Le runner Architecture agrégé avec couverture a dépassé 180 s ; les 67 tests fonctionnels ont été exécutés séparément, mais la couverture agrégée reste non mesurée. Ces contrôles restent obligatoires avant promotion de la release. L’archive source finale est générée de manière reproductible depuis le snapshot Git, contient 726 fichiers, passe le validateur Archive Compatibility à 0 violation et repasse après extraction Source Integrity, Architecture-as-Code, migrations, les 63 contrats Compose et les 79 tests Web. Un manifeste externe `release-files.sha256` couvre les octets réellement archivés ; `source-files.sha256` conserve volontairement sa sémantique de checksums des blobs Git avant filtres LF/CRLF.

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
