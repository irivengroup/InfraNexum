# InfraNexum 2.0.0-alpha.0.18 — état d’implémentation

## alpha.0.18 — Core Workers Foundation / PGM-02-E07

**Statut de l’incrément : implémenté localement, certification cible partielle. Statut de l’epic PGM-02-E07 : NON TERMINÉ.**

Le nouveau module `components/core/workers` fournit un ordonnanceur idempotent, un `TaskStore` de référence thread-safe et borné, un worker unitaire et un pool à concurrence fixe. Les contrats de sûreté couvrent les leases versionnés, le fencing des workers obsolètes, le heartbeat, les checkpoints, l’annulation coopérative, les retries uniquement pour les tâches déclarées `RETRY_SAFE` et le fail-closed `AT_MOST_ONCE`.

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
src/components/adapters/persistence-jdbc/src/main/java/io/infranexum/adapters/persistence/jdbc/JdbcTransactionalEventStore.java
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
