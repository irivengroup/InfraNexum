# InfraNexum 2.0.0-alpha.0.2 — état d’implémentation

## Implémenté dans cet incrément

- runtime Web autonome Node.js, sans accès base ni communication Agent ;
- composition root explicite avec configuration validée avant ouverture du listener ;
- configuration publique distincte des secrets et exposée sous contrat versionné ;
- probes `/health/live`, `/health/ready` et `/health/startup` ;
- identité de build Web sous `/api/v1/system/build` ;
- service sécurisé des assets avec défense path traversal/symlink, limites de taille et cache immuable ;
- en-têtes CSP, isolation, anti-framing, anti-MIME-sniffing et absence de cache sur les diagnostics ;
- arrêt gracieux borné, collisions de port et erreurs de fermeture testées ;
- page de bootstrap accessible et responsive, sans autorité métier ;
- gate Node intégré à la CI avec Node.js 24.18.1 et pnpm 11.17.0 exacts ;
- extension du gate de secrets aux sources JavaScript, MJS, HTML et CSS.
- stabilisation du test de timeout d’arrêt Agent par une barrière TCP explicite, vérifiée sur 20 répétitions sous race detector.

Les incréments précédents restent présents : monorepo huit espaces, Architecture-as-Code, Agent Go, Server Java, Domain Contract Pack Core et catalogue de migrations PostgreSQL/Oracle appariées.

## Limites explicites

Le produit complet reste **NON TERMINÉ**. Le shell React/TypeScript piloté par capabilities, l’internationalisation DE/EN/ES/FR/IT, les bounded contexts métier, IAM, activation, audit, persistence et installateurs ne sont pas encore implémentés.

Le runtime Web est exécuté localement sous Node.js 22.16.0 pour validation de compatibilité. La campagne normative Node.js 24.18.1/pnpm 11.17.0 reste à exécuter dans la CI. Le reactor Java 25, Go 1.26.5 exact et les migrations sur moteurs réels restent également non exécutés dans cet environnement.

## Prochaine tranche

La prochaine tranche doit exécuter les toolchains normatives disponibles en CI, puis démarrer `PGM-02-E03` — événements transactionnels, outbox, inbox, déduplication et idempotence — sur la base du Domain Contract Pack et du catalogue de migrations. Le shell React métier de `PGM-05-E02` restera dépendant de `PGM-02-E04` conformément à la roadmap.
