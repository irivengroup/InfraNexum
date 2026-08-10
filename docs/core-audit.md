# Core Audit — append-only et export signé

## Périmètre

L’incrément `2.0.0-alpha.0.14` matérialise le socle de `PGM-02-E06` dans `src/components/core/audit` et son adaptateur JDBC. Il ne publie pas encore l’API IAM d’audit : IAM doit devenir producteur des événements complets et autoriser les recherches/exports dans un incrément ultérieur.

## Invariants

Chaque `AuditEntry` est immutable et scoped. Le contrat transporte au minimum l’acteur, son type, l’action, la cible, la décision d’autorisation, l’horodatage UTC, le correlation ID, le résultat et l’origine. Les métadonnées sont plates, triées canoniquement, bornées à 4 KiB UTF-8 et refusent les clés susceptibles de contenir mot de passe, secret, token, credential, Authorization, cookie ou clé privée.

Les entrées persistées sont reliées par une chaîne SHA-256 par scope. Chaque scope possède une tête `(last_sequence, head_hash)` verrouillée avec `SELECT ... FOR UPDATE`. L’adaptateur JDBC utilise `READ_COMMITTED` par défaut : le verrou pessimiste de la tête fournit la sérialisation utile sans imposer les aborts supplémentaires de `SERIALIZABLE` sous concurrence.

Les tables d’audit ne proposent aucune opération applicative UPDATE/DELETE. PostgreSQL et Oracle installent des triggers `BEFORE UPDATE OR DELETE` qui refusent toute mutation d’une entrée ou d’un tombstone. Le rollback de migration refuse de détruire les tables lorsqu’une preuve d’audit existe.

## Export

`AuditExportService` produit un snapshot borné et contigu :

1. relecture du segment demandé ;
2. vérification de la chaîne cryptographique ;
3. payload JSON Lines UTF-8 canonique ;
4. manifeste déterministe contenant scope, intervalle, nombre d’entrées, timestamp, hash du payload, hash précédent et hash de tête ;
5. signature Ed25519 du manifeste ;
6. ZIP déterministe en mode STORED avec timestamps normalisés.

L’archive contient :

```text
audit.jsonl
manifest.properties
signature.ed25519.b64
```

`AuditExportVerifier` vérifie le SHA-256 du payload avec comparaison constante puis la signature Ed25519.

## Purge réglementaire

Le modèle `AuditPurgeTombstone` et la table appariée PostgreSQL/Oracle imposent :

- deux approbateurs distincts ;
- une politique explicite ;
- une preuve SHA-256 ;
- un timestamp ;
- une justification ;
- l’immutabilité du tombstone.

Le workflow de purge lui-même n’est pas encore exposé. Il devra traiter réplicas, index, caches et sauvegardes, produire une preuve signée et conserver le tombstone. Aucun `DELETE` direct du corpus d’audit n’est autorisé.

## Concurrence

Le journal mémoire utilise un verrou équitable. Le journal JDBC sérialise les append par scope via la ligne de tête. Le test PostgreSQL vivant lance 32 writers concurrents sur le même scope et exige une séquence continue sans doublon ainsi qu’une chaîne vérifiable.

## Sécurité — menaces couvertes

| Menace | Contrôle |
|---|---|
| Altération d’une entrée | triggers DB + chaîne SHA-256 |
| Répudiation | acteur/action/cible/décision/correlation/origine persistés |
| Fuite de secrets dans metadata | liste de clés sensibles interdite + taille bornée |
| Export modifié | manifeste SHA-256 + Ed25519 |
| Export tronqué/réordonné | intervalle contigu + hash précédent/tête |
| Concurrence d’append | verrou de tête par scope |
| Purge abusive | modèle double approbation + tombstone ; workflow complet encore requis |

## Limites de cet incrément

Le socle reste **NON TERMINÉ** pour `PGM-02-E06` au sens de la certification globale. Restent notamment :

- exécution JUnit/JaCoCo sous Java 25 ;
- PostgreSQL 17/18 réel du journal et des triggers ;
- Oracle 19c/26ai réel ;
- recherche avancée scoped avec droits IAM ;
- audit des lectures, recherches et exports eux-mêmes ;
- workflow complet de purge réglementaire ;
- chiffrement des exports, durée de disponibilité et stockage sécurisé ;
- endpoint/API/CLI d’export après disponibilité IAM ;
- objectifs de performance P95 sur volumétrie représentative.
