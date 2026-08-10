# InfraNexum 2.0.0-alpha.0.13 — état d’implémentation

## Objet de l’incrément

Cet incrément corrige les deux régressions observées sur le runner Java 25 après `alpha.0.12` : la couverture JaCoCo insuffisante du Core Events et l’échec des modules Maven amont lors de l’exécution ciblée des tests PostgreSQL.

## Correctif Core Events / JaCoCo

Le seuil du module reste strictement inchangé à **98 % lignes et 98 % branches**. Aucun package, classe ou branche n’est exclu pour faire passer le gate.

La suite JUnit du module passe de 17 à **34 scénarios**. Elle couvre désormais explicitement les invariants et branches de :

- `DispatchReport` ;
- `EventEnvelope`, `EventSource`, `EventType` et `InboxKey` ;
- `InboxReservation`, `InboxReceipt` et `OutboxRecord` ;
- `ExponentialBackoffPolicy`, y compris les bornes, jitter invalide et débordements ;
- `InMemoryEventStore`, notamment rollback, interruptions, hooks post-commit, ownership, leases expirées/non expirées, overflow temporel et machine d’état Inbox ;
- `OutboxDispatcher`, notamment lot vide, succès, retry et dead-letter ;
- `TransactionOutcome` et copies défensives.

Les 34 scénarios ont été compilés avec `javac -Xlint:all -Werror` et exécutés localement via un harnais JUnit-compatible minimal sous JDK 21. Cette preuve vérifie la syntaxe et les comportements sans prétendre remplacer JaCoCo/JUnit sous Java 25.

## Correctif Surefire / reactor ciblé

Le parent conservait :

```xml
<failIfNoTests>true</failIfNoTests>
```

Cette valeur explicite empêchait l’option CLI `-DfailIfNoTests=false` de produire l’effet attendu dans les modules amont.

Le parent utilise désormais une propriété InfraNexum substituable :

```xml
<infranexum.surefire.failIfNoTests>true</infranexum.surefire.failIfNoTests>
...
<failIfNoTests>${infranexum.surefire.failIfNoTests}</failIfNoTests>
```

Le build global reste donc strict, tandis que le job ciblé utilise :

```bash
-Dinfranexum.surefire.failIfNoTests=false \
-Dsurefire.failIfNoSpecifiedTests=false
```

Le gate toolchains vérifie désormais ce binding et la présence des deux options dans le job PostgreSQL.

## Non-régression locale

- architecture : 28 tests, 100 %, 0 violation ;
- toolchains : 18 tests, 99 %, 0 violation ;
- migrations : 20 tests, 99 %, 0 violation ;
- eventing : 10 tests, 100 %, 0 violation ;
- persistence : 10 tests, 98 %, 0 violation ;
- capabilities : 10 tests, 99 %, 0 violation ;
- entitlements : 10 tests, 100 %, 0 violation ;
- audit : 8 tests, 100 %, 0 violation ;
- total des gates Python : **114 tests** ;
- 8 smokes Java autonomes réussis ;
- 34 scénarios Core Events compilés et exécutés par le harnais local ;
- Agent : `go vet`, race detector, couverture **98,4 %**, build statique ;
- Web : **27/27**, 99,65 % lignes, 98,28 % branches, 100 % fonctions.

## Limites

Le statut reste **NON TERMINÉ** jusqu’à la réexécution du reactor Maven sous Java 25 et du job PostgreSQL 17/18 sur le runner hébergé. Ces exécutions doivent confirmer que le Core Events atteint réellement les deux seuils JaCoCo de 98 % et que les modules amont ne bloquent plus le test ciblé.

Docker Compose reste non applicable tant que le JAR Server Java 25 et le bootstrap d’installation neuve ne sont pas prouvés exécutables.
