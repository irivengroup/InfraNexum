# PGM-07-E05 — Racks, équipements, empreintes, ports et câblage

## Objet

`alpha.0.82` implémente le gate roadmap **« occupation, connectivité physique et modèles multi-constructeurs »** au-dessus de la hiérarchie DCIM E04. DCIM devient l’autorité de localisation, d’occupation en rack, d’empreinte physique, de ports et de câblage. RSOT reste l’autorité de l’identité technique, ITAM celle du patrimoine, Partner celle des constructeurs et Organization celle des scopes : E05 les référence uniquement par weak references validées aux frontières applicatives.

La capability `dcim.physical` dépend de `dcim.facilities` et reste fail-closed si la hiérarchie physique n’est pas disponible. Les quotas existants `dcim.racks.max`, `dcim.ports.max` et `dcim.connections.max` sont évalués depuis le plan d’allocation effectif ; aucun quota parallèle n’est créé.

## Modèles multi-constructeurs et empreinte

`EquipmentModel` est l’autorité DCIM de l’empreinte physique réutilisable d’un équipement. Un modèle appartient à une Organisation et à un constructeur Partner canonique. Il porte un code unique dans le scope `(organization, manufacturer)`, un nom, un form factor, une hauteur en unités U, largeur, profondeur, poids optionnel et une liste ordonnée de templates de ports.

Les templates de ports définissent un préfixe, un nombre, un type (`network`, `power`, `console`, `fiber`, `other`), un média et un connecteur. Les ports physiques ne sont pas saisis librement lors de l’installation : ils sont instanciés à partir du modèle afin que la topologie réelle reste compatible avec l’empreinte publiée.

Les modèles sont créés `DRAFT`, peuvent devenir `ACTIVE`, puis être archivés selon le lifecycle gouverné. Un équipement ne peut être installé depuis un modèle non actif.

## Racks et occupation

Un `Rack` appartient à une Organisation/Subdivision et à une Room DCIM active. Il porte son code, nom, hauteur U, largeur et profondeur. Le code est unique dans la Room. Un rack doit être `ACTIVE` avant toute installation et ne peut être décommissionné ou archivé tant qu’il contient encore un équipement.

L’installation vérifie :

- scope Organisation/Subdivision identique au Rack ;
- RSOT canonique existant et actif selon le port de référence ;
- Asset ITAM valide lorsqu’il est renseigné ;
- modèle et rack actifs ;
- largeur/profondeur du modèle compatibles avec le rack ;
- plage `startU..endU` dans la hauteur du rack ;
- absence de chevauchement avec un équipement actif ou en maintenance ;
- numéro de série globalement unique lorsqu’il est renseigné ;
- quota de ports après instanciation du modèle.

Le placement et le déplacement verrouillent transactionnellement la ligne Rack avant le second contrôle d’occupation. Deux installations concurrentes ne peuvent donc pas réserver simultanément les mêmes unités U sur la base d’une lecture périmée.

## Équipements et références d’autorité

Un `Equipment` conserve des weak references vers son `EquipmentModel`, son objet RSOT canonique et, facultativement, son Asset ITAM. Il porte le Rack courant, la position U, la face `front|rear`, le numéro de série et l’asset tag optionnels, le statut et la version optimiste.

Le déplacement réévalue le scope, l’empreinte, la hauteur et l’occupation dans la même unité de travail. Un équipement décommissionné ou archivé ne peut plus être déplacé. Un équipement possédant encore un câble actif ne peut pas être décommissionné ou archivé : la connectivité doit d’abord être retirée explicitement.

## Ports et câblage point-à-point

Les ports physiques sont immuablement rattachés à leur équipement et héritent du type, média et connecteur définis par le modèle. `CableConnection` représente une liaison physique point-à-point auditable entre deux ports appartenant à deux équipements distincts.

Une connexion est refusée lorsque :

- les deux extrémités sont identiques ou appartiennent au même équipement ;
- une extrémité est déjà occupée par un câble actif ;
- les types de ports diffèrent ;
- le média ou le connecteur diffère ;
- le quota de connexions est atteint ;
- les ports sortent du scope Organisation autorisé.

La réservation verrouille les deux lignes Port dans un ordre déterministe avant de relire leur état. Ce verrouillage évite les deadlocks d’ordre et ferme la race où deux transactions tenteraient de connecter simultanément le même port comme extrémité A ou B. Les contraintes SQL d’unicité restent une seconde ligne de défense.

La déconnexion est une transition versionnée `ACTIVE → DECOMMISSIONED`; aucun câble actif n’est supprimé silencieusement.

## Transactions, idempotence et événements

Toutes les mutations exigent une `Idempotency-Key` de 8 à 200 caractères sûrs, une justification d’audit et, pour les modifications, une version attendue. La mutation métier, la persistance JDBC, l’idempotence et l’outbox partagent la même unité de travail.

Les événements couvrent notamment création/activation/archivage des modèles, création/lifecycle des racks, `dcim.equipment.installed.v1`, `dcim.equipment.moved.v1`, lifecycle des équipements, `dcim.cable.connected.v1` et `dcim.cable.disconnected.v1`.

## Sécurité et permissions

E05 ajoute 17 permissions atomiques organisation-scoped :

- `dcim.model.read/create/update/archive` ;
- `dcim.rack.read/create/update/decommission` ;
- `dcim.equipment.read/create/update/move/decommission` ;
- `dcim.port.read` ;
- `dcim.cable.read/create/disconnect`.

Les contrôleurs utilisent le scope réel de l’objet ou de la requête pour RBAC/ABAC et ne font pas d’oracle d’existence/type avant autorisation. Une capability indisponible produit un refus contrôlé, jamais un fallback silencieux.

## API, CLI et Web

OpenAPI 3.1 publie 14 opérations natives couvrant :

- modèles : list/create/status ;
- racks : list/create/status ;
- équipements : list/install/move/status/ports ;
- câblage : list/connect/disconnect.

La CLI Server consomme les mêmes use cases, authentification et contrôles d’autorisation, avec secret lu depuis un fichier, sorties structurées et mutations idempotentes.

La Web UI est livrée dans la même tranche, dans le workspace DCIM existant. Elle expose modèles, racks, équipements, ports et câbles ainsi que les transitions nécessaires pour rendre le flux opérateur réellement utilisable. Les relations vers Organisation, Subdivision, Room, constructeur, modèle, RSOT, Asset ITAM, Rack et ports sont sélectionnées depuis les catalogues gouvernés : aucun UUID métier n’est demandé en saisie libre. Les motifs d’audit sont saisis par l’opérateur et ne sont pas codés en dur. DE/EN/ES/FR/IT restent les cinq locales supportées.

## Migrations et rollback

- `0028-dcim-rack-equipment-cabling` : modèles, templates de ports, racks, équipements, ports, câbles, idempotence, index, contraintes et verrous compatibles PostgreSQL/Oracle.
- `0029-identity-access-dcim-physical-permissions` : 17 permissions atomiques et bootstrap du rôle système administrateur, avec rollback borné au périmètre E05.

Les FK sont limitées au bounded context DCIM. Les références Partner/RSOT/ITAM/Organization ne créent aucune dépendance relationnelle inter-contexte. Les `verify.sql.yaml` et rollbacks sont fournis pour PostgreSQL et Oracle ; leur exécution live reste un gate de promotion distinct de la validation statique de l’artefact.
