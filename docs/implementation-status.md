# InfraNexum 2.0.0-alpha.0.8 — état d’implémentation

## Objet de l’incrément

Cet incrément corrige les défaillances observées dans GitHub Actions sans modifier les contrats métier, les migrations ou les API publiques.

## Corrections CI intégrées

- le job `architecture` installe désormais Java avant les smokes Java ;
- le sélecteur Temurin utilisé par `setup-java` est `25.0.4+7.0.LTS`, valeur réellement résoluble par la distribution ;
- le runtime Web est installé par `pnpm/setup` épinglé sur SHA, avec Node.js `24.18.1` et pnpm `11.17.0` ;
- l’ancien enchaînement `setup-node` puis Corepack, qui demandait le cache pnpm avant l’existence de l’exécutable, est interdit ;
- le smoke événementiel n’utilise plus `List.getFirst()` ni `ExecutorService` comme ressource AutoCloseable ;
- les actions et sélecteurs CI sont vérifiés par Architecture-as-Code/toolchain-as-code ;
- le warning `runpy` de la suite entitlements est supprimé.

## Non-régression exécutée

- 101 tests Python sur les sept gates ;
- couvertures : architecture 100 %, toolchains 99 %, migrations 99 %, eventing 100 %, persistence 98 %, capabilities 99 %, entitlements 100 % ;
- six smokes Java sous OpenJDK 21 avec `-Xlint:all -Werror` ;
- Agent : `go vet`, race detector, couverture 98,4 %, build statique et 20 répétitions du test concurrent ;
- Web : 27/27 tests, 99,65 % lignes, 98,28 % branches, 100 % fonctions ;
- validation syntaxique JSON/YAML, shell, JavaScript et Go.

## Limites explicites

Le statut global reste **NON TERMINÉ**.

- le workflow corrigé n’a pas encore été réexécuté sur un runner GitHub hébergé ;
- le reactor Maven complet reste à exécuter sous Java 25 ;
- l’Agent reste à exécuter sous Go 1.26.5 exact ;
- le Web reste à exécuter sous Node.js 24.18.1 et pnpm 11.17.0 exacts ;
- PostgreSQL 17/18 et Oracle 19c/26ai réels restent à certifier ;
- l’intégration Spring autoritative des opérations d’activation et l’environnement Docker Compose restent à fermer.
