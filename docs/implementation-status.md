# InfraNexum 2.0.0-alpha.0.9 — état d’implémentation

## Objet de l’incrément

Cet incrément corrige les défaillances d’exécution observées dans GitHub Actions sans modifier les contrats métier, les migrations ni les API publiques.

## Corrections CI intégrées

- chaque job invoquant directement `./mvnw` exécute auparavant `chmod 0755 mvnw && test -x mvnw` ;
- le bit exécutable attendu du Maven Wrapper reste `100755` dans le dépôt et la commande Git de livraison le force explicitement ;
- le projet Web possède désormais `src/applications/web/pnpm-workspace.yaml` ;
- `autoInstallPeers: false` est identique dans le workspace et dans `pnpm-lock.yaml` ;
- les autres politiques pnpm sont explicites : `strictPeerDependencies`, `engineStrict`, `saveExact` et `ignoreScripts` ;
- le fichier projet `.npmrc` est supprimé et interdit par le gate, conformément au modèle de configuration pnpm 11 ;
- le sélecteur Temurin reste `25.0.4+7.0.LTS` ;
- le runtime Web reste installé par `pnpm/setup` épinglé sur SHA, avec Node.js `24.18.1` et pnpm `11.17.0` ;
- le smoke événementiel reste compatible avec le JDK de bootstrap.

## Non-régression exécutée

- 103 tests Python sur les sept gates ;
- couvertures : architecture 100 %, toolchains 99 %, migrations 99 %, eventing 100 %, persistence 98 %, capabilities 99 %, entitlements 100 % ;
- six smokes Java sous OpenJDK 21 avec `-Xlint:all -Werror` ;
- test de permission Maven : passage simulé de `0644` à `0755`, puis exécution réelle du wrapper jusqu’au garde JDK 25 ;
- Agent : `go vet`, race detector, couverture 98,4 %, build statique et 20 exécutions concurrentes réparties en deux lots bornés ;
- Web : 27/27 tests, 99,65 % lignes, 98,28 % branches et 100 % fonctions ;
- validation syntaxique JSON, YAML, shell, JavaScript et Go.

## Limites explicites

Le statut global reste **NON TERMINÉ**.

- le workflow corrigé n’a pas encore été réexécuté sur un runner GitHub hébergé ;
- le reactor Maven complet reste à exécuter sous Java 25 ;
- `pnpm install --frozen-lockfile --offline` sous pnpm 11.17.0 reste à exécuter : l’environnement local ne peut pas résoudre `registry.npmjs.org` pour acquérir pnpm ;
- l’Agent reste à exécuter sous Go 1.26.5 exact ;
- PostgreSQL 17/18 et Oracle 19c/26ai réels restent à certifier ;
- l’intégration Spring autoritative des opérations d’activation et l’environnement Docker Compose restent à fermer.
