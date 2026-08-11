
### 2.0.0-alpha.0.25 — Entitlements coverage hardening

Le déficit JaCoCo observé sous JDK 25 sur `infranexum-core-entitlements` (66 % lignes / 64 % branches) est traité par des tests comportementaux supplémentaires couvrant l'import d'activation, la compensation transactionnelle, les preuves temporelles duales, le runtime authority Lite et les invariants des value objects. Aucun seuil JaCoCo n'est abaissé et aucune exclusion n'est ajoutée. Le verify Maven complet utilise désormais `--fail-at-end` afin qu'un seul run CI expose tous les modules Java restant sous 98 % au lieu de s'arrêter au premier échec.

# InfraNexum 2.0.0-alpha.0.25 — état d’implémentation

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
