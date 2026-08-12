# InfraNexum 2.0.0-alpha.0.45 — état d’implémentation


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