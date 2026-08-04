# InfraNexum 2.0.0-alpha.0.10 — état d’implémentation

## Objet de l’incrément

Cet incrément ferme le raccordement applicatif autoritatif de l’activation au Server. Il ne modifie pas le format du manifeste signé, le cycle Lite, la grâce Pro/Enterprise, les migrations existantes ni les contrats des événements transactionnels.

## Implémentation intégrée

### Décision avant exposition réseau

`EntitlementWebServerStartupGuard` initialise l’autorité durable depuis la base et le stockage indépendant dans un `WebServerFactoryCustomizer`. La décision de hard stop est donc prise avant que le conteneur Servlet ouvre son port.

Un profil Lite arrivé à J210, ou un profil Pro/Enterprise arrivé après sa grâce, empêche le démarrage HTTP. Aucun mode dégradé silencieux n’est appliqué.

### Garde des opérations mutatives

`EntitlementMutationInterceptor` protège les méthodes POST, PUT, PATCH et DELETE sous `/api/**`. La décision provient exclusivement de `EntitlementRuntimeAuthority`.

Les accès refusés sont traduits en HTTP 403 `application/problem+json`. Une autorité non initialisée ou indisponible produit HTTP 503 avec un code stable, sans fuite de détail cryptographique ou JDBC.

### Synchronisation capabilities et quotas

Le snapshot durable d’entitlements alimente `PlatformCapabilityService` :

- capacités autorisées par le manifeste ;
- état d’activation ;
- tier d’allocation ;
- substitutions de quotas.

La vue est remplacée atomiquement, empêchant une lecture partielle entre capacités et quotas. Le nom du profil ne devient pas une branche métier de contournement.

### Rafraîchissement et disponibilité

`EntitlementRefreshScheduler` recharge périodiquement l’état autoritatif. Une erreur de vérification ou de persistance ferme le contexte Spring afin de préserver le refus fermé.

`EntitlementHealthIndicator` contribue à la readiness. Le statut public est disponible via :

```text
GET /api/v1/platform/evaluation/status
```

La réponse est non cachable et n’expose ni manifeste signé, ni clés, ni preuve HMAC.

### Configuration et secrets

La composition Spring impose :

- PostgreSQL ou Oracle lorsque les entitlements sont actifs ;
- trust store externe pour Pro et Enterprise ;
- clé HMAC Base64 de 32 à 64 octets issue d’un fichier secret ;
- preuve indépendante sous un répertoire persistant ;
- configuration stricte de la taille maximale du manifeste et de l’intervalle de rafraîchissement.

Le mode MEMORY utilise un `UnavailableDataSource` explicite et fail-closed pour les tests sans autorité. Aucun DataSource implicite ou fallback vers une base embarquée n’est accepté.

## Non-régression exécutée

- 104 tests Python sur les sept gates ;
- architecture : 28 tests, couverture 100 %, 0 violation ;
- toolchains : 16 tests, couverture 99 %, 0 violation ;
- migrations : 20 tests, couverture 99 %, 0 violation ;
- eventing : 10 tests, couverture 100 %, 0 violation ;
- persistence : 10 tests, couverture 98 %, 0 violation ;
- capabilities : 10 tests, couverture 99 %, 0 violation ;
- entitlements : 10 tests, couverture 100 %, 0 violation ;
- sept smokes Java sans dépendances externes ;
- compilation statique de toutes les sources Server/Core/JDBC avec `javac -Xlint:all -Werror` contre un harnais minimal d’API ;
- Agent : `go vet`, race detector, couverture 98,4 %, build statique, smoke processus et 20 répétitions ;
- Web : 27/27 tests, 99,65 % lignes, 98,28 % branches, 100 % fonctions ;
- validation syntaxique Python, JSON, YAML, XML, JavaScript, shell et Go.

## Limites explicites

Le statut global reste **NON TERMINÉ**.

### Java 25 et Spring réels

Le poste local utilise OpenJDK 21. Le Maven Wrapper fonctionne mais refuse correctement le runtime non supporté. Le reactor complet, les tests Spring/JUnit/Modulith et JaCoCo doivent être exécutés sous Java 25.

### Bases de données réelles

Les stratégies et migrations PostgreSQL/Oracle sont validées statiquement et par smokes simulés, mais aucun moteur réel n’est disponible dans l’environnement courant. Les transactions, verrous, conversions JSON/UUID et scénarios de crash doivent être certifiés sur les versions cibles.

### API d’administration

L’import signé est implémenté comme service applicatif interne. Aucun endpoint HTTP d’import n’est publié avant disponibilité de l’IAM, du contrôle d’autorisation, de l’audit append-only et de la protection anti-CSRF adaptée.

Le préflight de migration Lite vers Pro/Enterprise reste à implémenter avec usages de quotas, modules, migrations, connecteurs, IAM, espace disque, sauvegarde vérifiée, blockers et warnings.

### Provisioning et Docker

Une installation neuve ne sait pas encore générer et persister transactionnellement son identité, appliquer les migrations, provisionner les secrets et construire le JAR Java 25. Docker Compose reste donc non livré dans cet incrément. Le prochain environnement Compose devra démarrer PostgreSQL, migrations, Server, Web et Agent avec volumes, secrets, réseaux, health checks, smoke tests et rollback réels.
