CREATE SCHEMA IF NOT EXISTS infranexum_rsot;

CREATE TABLE IF NOT EXISTS infranexum_rsot.canonical_object (
  id UUID PRIMARY KEY,
  object_type VARCHAR(160) NOT NULL,
  version BIGINT NOT NULL,
  organization_id UUID NOT NULL,
  schema_version VARCHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL,
  status_reason VARCHAR(500) NULL,
  effective_from TIMESTAMPTZ NOT NULL,
  effective_until TIMESTAMPTZ NULL,
  archived_at TIMESTAMPTZ NULL,
  archived_by UUID NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT ck_inx_rsot_obj_uuidv7 CHECK (id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'),
  CONSTRAINT ck_inx_rsot_obj_version CHECK (version >= 1),
  CONSTRAINT ck_inx_rsot_obj_status CHECK (status IN ('PROPOSED','VALIDATED','RECONCILED','DEPRECATED','ARCHIVED')),
  CONSTRAINT ck_inx_rsot_obj_period CHECK (effective_until IS NULL OR effective_until > effective_from),
  CONSTRAINT ck_inx_rsot_obj_archive_pair CHECK ((archived_at IS NULL AND archived_by IS NULL) OR (archived_at IS NOT NULL AND archived_by IS NOT NULL)),
  CONSTRAINT ck_inx_rsot_obj_archived CHECK (status <> 'ARCHIVED' OR archived_at IS NOT NULL),
  CONSTRAINT ck_inx_rsot_obj_time CHECK (updated_at >= created_at AND effective_from >= created_at)
);
CREATE INDEX IF NOT EXISTS ix_inx_rsot_obj_org_type_status ON infranexum_rsot.canonical_object(organization_id,object_type,status,updated_at DESC);

CREATE TABLE IF NOT EXISTS infranexum_rsot.attribute_authority_policy (
  id UUID PRIMARY KEY,
  object_type VARCHAR(160) NOT NULL,
  attribute_path VARCHAR(256) NOT NULL,
  authority_context VARCHAR(32) NOT NULL,
  source_priority VARCHAR(1000) NOT NULL,
  effective_from TIMESTAMPTZ NOT NULL,
  effective_until TIMESTAMPTZ NULL,
  policy_version VARCHAR(64) NOT NULL,
  approval_ref VARCHAR(200) NOT NULL,
  CONSTRAINT uq_inx_rsot_authority_policy UNIQUE(object_type,attribute_path,policy_version),
  CONSTRAINT ck_inx_rsot_auth_uuidv7 CHECK (id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'),
  CONSTRAINT ck_inx_rsot_auth_context CHECK (authority_context IN ('ORGANIZATION','IAM','RSOT','DISCOVERY','DCIM','DDI','ITAM','CORE_CAPABILITIES','GOVERNANCE_RSOT')),
  CONSTRAINT ck_inx_rsot_auth_period CHECK (effective_until IS NULL OR effective_until > effective_from),
  CONSTRAINT ck_inx_rsot_auth_no_global CHECK (object_type NOT IN ('*','.*') AND attribute_path NOT IN ('*','.*'))
);
CREATE INDEX IF NOT EXISTS ix_inx_rsot_auth_lookup ON infranexum_rsot.attribute_authority_policy(object_type,attribute_path,effective_from,effective_until);

CREATE TABLE IF NOT EXISTS infranexum_rsot.authority_matrix (
  position_no SMALLINT PRIMARY KEY,
  information_text VARCHAR(300) NOT NULL,
  authority_name VARCHAR(80) NOT NULL,
  rsot_contribution VARCHAR(300) NOT NULL,
  conflict_strategy VARCHAR(300) NOT NULL,
  matrix_version VARCHAR(64) NOT NULL,
  CONSTRAINT ck_inx_rsot_matrix_position CHECK (position_no BETWEEN 1 AND 9)
);

CREATE TABLE IF NOT EXISTS infranexum_rsot.context_relationship (
  position_no SMALLINT PRIMARY KEY,
  provider_name VARCHAR(100) NOT NULL,
  contribution VARCHAR(300) NOT NULL,
  direct_storage_write_allowed BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_inx_rsot_context_position CHECK (position_no BETWEEN 1 AND 10),
  CONSTRAINT ck_inx_rsot_context_no_direct_write CHECK (direct_storage_write_allowed = FALSE)
);

INSERT INTO infranexum_rsot.authority_matrix(position_no,information_text,authority_name,rsot_contribution,conflict_strategy,matrix_version) VALUES
(1,'Organisation, subdivision','Organisation','référence, scope, snapshot d’affichage','l’autorité Organisation prévaut','2.0.0-draft.21'),
(2,'Identité utilisateur et groupes','IAM','référence d’acteur, audit, ownership','l’autorité IAM prévaut','2.0.0-draft.21'),
(3,'Identité canonique d’un actif','RSOT','création, fusion, séparation, alias','workflow RSOT','2.0.0-draft.21'),
(4,'Observation brute','Discovery','association et provenance','observation immuable, pas d’écrasement','2.0.0-draft.21'),
(5,'Localisation physique','DCIM','consolidation sur l’actif','conflit remonté à DCIM/RSOT','2.0.0-draft.21'),
(6,'Adresse IP, préfixe, DNS, DHCP','DDI','relation canonique et recherche','DDI prévaut','2.0.0-draft.21'),
(7,'Contrat, garantie, licence patrimoniale','ITAM','référence et statut consolidé','ITAM prévaut','2.0.0-draft.21'),
(8,'Profil d’installation, quota, capability','Core Capabilities','lecture pour décisions','Core prévaut','2.0.0-draft.21'),
(9,'Politique de qualité','Governance/RSOT','exécution et preuve','version active approuvée','2.0.0-draft.21')
ON CONFLICT (position_no) DO UPDATE SET information_text=EXCLUDED.information_text,authority_name=EXCLUDED.authority_name,rsot_contribution=EXCLUDED.rsot_contribution,conflict_strategy=EXCLUDED.conflict_strategy,matrix_version=EXCLUDED.matrix_version;

INSERT INTO infranexum_rsot.context_relationship(position_no,provider_name,contribution,direct_storage_write_allowed) VALUES
(1,'Organization','scope et identifiants d’organisation',FALSE),
(2,'IAM','acteurs et décisions d’accès',FALSE),
(3,'Discovery','observations et preuves immuables',FALSE),
(4,'DDI','changements dont DDI est autorité',FALSE),
(5,'DCIM','changements dont DCIM est autorité',FALSE),
(6,'ITAM','changements dont ITAM est autorité',FALSE),
(7,'Governance','approbation des politiques selon le workflow défini',FALSE),
(8,'Core Audit','réception des événements d’audit',FALSE),
(9,'Core Contracts/Compatibility','registre de schémas',FALSE),
(10,'Core Capabilities','capabilities et quotas',FALSE)
ON CONFLICT (position_no) DO UPDATE SET provider_name=EXCLUDED.provider_name,contribution=EXCLUDED.contribution,direct_storage_write_allowed=FALSE;
