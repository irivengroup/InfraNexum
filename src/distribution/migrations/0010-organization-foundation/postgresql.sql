CREATE SCHEMA IF NOT EXISTS infranexum_org;
CREATE TABLE IF NOT EXISTS infranexum_org.organization (
 id UUID PRIMARY KEY, code VARCHAR(32) NOT NULL UNIQUE, display_name VARCHAR(160) NOT NULL, legal_name VARCHAR(255) NOT NULL,
 country_code CHAR(2) NOT NULL, default_language VARCHAR(35) NOT NULL, timezone VARCHAR(80) NOT NULL, currency CHAR(3) NOT NULL,
 parent_organization_id UUID NULL REFERENCES infranexum_org.organization(id), status VARCHAR(32) NOT NULL, version BIGINT NOT NULL,
 created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
 CONSTRAINT ck_inx_org_code CHECK (code ~ '^[A-Z0-9][A-Z0-9-]{2,31}$'),
 CONSTRAINT ck_inx_org_state CHECK (status IN ('PROVISIONING','ACTIVE','SUSPENDED','ARCHIVING','ARCHIVED','DELETION_PENDING','DELETED')),
 CONSTRAINT ck_inx_org_version CHECK (version >= 0), CONSTRAINT ck_inx_org_parent CHECK (parent_organization_id IS NULL OR parent_organization_id <> id),
 CONSTRAINT ck_inx_org_uuidv7 CHECK (SUBSTRING(id::TEXT FROM 15 FOR 1)='7' AND SUBSTRING(id::TEXT FROM 20 FOR 1) IN ('8','9','a','b'))
);
CREATE INDEX IF NOT EXISTS ix_inx_org_status ON infranexum_org.organization(status);
CREATE INDEX IF NOT EXISTS ix_inx_org_country ON infranexum_org.organization(country_code);
CREATE INDEX IF NOT EXISTS ix_inx_org_display ON infranexum_org.organization(display_name,code);
CREATE TABLE IF NOT EXISTS infranexum_org.subdivision (
 id UUID NOT NULL, organization_id UUID NOT NULL REFERENCES infranexum_org.organization(id), code VARCHAR(32) NOT NULL,
 display_name VARCHAR(160) NOT NULL, description_text VARCHAR(4000), type_name VARCHAR(32) NOT NULL, status VARCHAR(16) NOT NULL,
 parent_subdivision_id UUID NULL, version BIGINT NOT NULL, created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL, deleted_at TIMESTAMPTZ,
 PRIMARY KEY(id), UNIQUE(organization_id,code), UNIQUE(organization_id,id),
 CONSTRAINT fk_inx_sub_parent FOREIGN KEY(organization_id,parent_subdivision_id) REFERENCES infranexum_org.subdivision(organization_id,id),
 CONSTRAINT ck_inx_sub_code CHECK (code ~ '^[A-Z0-9][A-Z0-9-]{2,31}$'), CONSTRAINT ck_inx_sub_type CHECK (type_name IN ('DEPARTMENT','SITE','FUNCTION','PROJECT','COST_CENTER')),
 CONSTRAINT ck_inx_sub_state CHECK (status IN ('DRAFT','ACTIVE','INACTIVE','ARCHIVED','DELETED')), CONSTRAINT ck_inx_sub_parent_self CHECK (parent_subdivision_id IS NULL OR parent_subdivision_id<>id),
 CONSTRAINT ck_inx_sub_deleted CHECK ((status='DELETED' AND deleted_at IS NOT NULL) OR status<>'DELETED'), CONSTRAINT ck_inx_sub_uuidv7 CHECK (SUBSTRING(id::TEXT FROM 15 FOR 1)='7' AND SUBSTRING(id::TEXT FROM 20 FOR 1) IN ('8','9','a','b'))
);
CREATE INDEX IF NOT EXISTS ix_inx_sub_org_state ON infranexum_org.subdivision(organization_id,status);
CREATE TABLE IF NOT EXISTS infranexum_org.temporal_scope (
 id UUID PRIMARY KEY, organization_id UUID NOT NULL REFERENCES infranexum_org.organization(id), subdivision_id UUID NULL,
 scope_type VARCHAR(24) NOT NULL, valid_from TIMESTAMPTZ NOT NULL, valid_to TIMESTAMPTZ NULL, version BIGINT NOT NULL, created_at TIMESTAMPTZ NOT NULL,
 CONSTRAINT fk_inx_scope_sub FOREIGN KEY(organization_id,subdivision_id) REFERENCES infranexum_org.subdivision(organization_id,id),
 CONSTRAINT ck_inx_scope_type CHECK (scope_type IN ('LEGAL','GEOGRAPHIC','OPERATIONAL','ADMINISTRATIVE','DATA')), CONSTRAINT ck_inx_scope_period CHECK (valid_to IS NULL OR valid_to>valid_from),
 CONSTRAINT ck_inx_scope_uuidv7 CHECK (SUBSTRING(id::TEXT FROM 15 FOR 1)='7' AND SUBSTRING(id::TEXT FROM 20 FOR 1) IN ('8','9','a','b'))
);
CREATE INDEX IF NOT EXISTS ix_inx_scope_effective ON infranexum_org.temporal_scope(organization_id,valid_from,valid_to);
CREATE TABLE IF NOT EXISTS infranexum_org.command_dedup (
 idempotency_key VARCHAR(128) PRIMARY KEY, payload_sha256 CHAR(64) NOT NULL, resource_type VARCHAR(32) NOT NULL, resource_id UUID NOT NULL, created_at TIMESTAMPTZ NOT NULL,
 CONSTRAINT ck_inx_dedup_hash CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'), CONSTRAINT ck_inx_dedup_type CHECK (resource_type IN ('organization','organization-transition','subdivision','scope'))
);
