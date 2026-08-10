# InfraNexum 2.0.0-alpha.0.15 — état d’implémentation

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
