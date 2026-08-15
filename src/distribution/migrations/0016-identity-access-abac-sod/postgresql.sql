CREATE TABLE IF NOT EXISTS infranexum_iam.access_policy (
  id UUID PRIMARY KEY,
  organization_id UUID NULL,
  code VARCHAR(128) NOT NULL,
  policy_version BIGINT NOT NULL,
  owner_id UUID NOT NULL,
  purpose VARCHAR(500) NOT NULL,
  priority INTEGER NOT NULL,
  scope_kind VARCHAR(16) NOT NULL,
  subdivision_id UUID NULL,
  state VARCHAR(16) NOT NULL,
  effective_from TIMESTAMPTZ NOT NULL,
  approved_by UUID NULL,
  approved_at TIMESTAMPTZ NULL,
  activated_at TIMESTAMPTZ NULL,
  deprecated_at TIMESTAMPTZ NULL,
  retired_at TIMESTAMPTZ NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT ck_inx_iam_policy_uuidv7 CHECK (id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'),
  CONSTRAINT ck_inx_iam_policy_version CHECK (policy_version >= 1),
  CONSTRAINT ck_inx_iam_policy_priority CHECK (priority BETWEEN 0 AND 10000),
  CONSTRAINT ck_inx_iam_policy_scope CHECK (scope_kind IN ('PLATFORM','ORGANIZATION','SUBDIVISION')),
  CONSTRAINT ck_inx_iam_policy_scope_pair CHECK (
    (scope_kind='PLATFORM' AND organization_id IS NULL AND subdivision_id IS NULL) OR
    (scope_kind='ORGANIZATION' AND organization_id IS NOT NULL AND subdivision_id IS NULL) OR
    (scope_kind='SUBDIVISION' AND organization_id IS NOT NULL AND subdivision_id IS NOT NULL)),
  CONSTRAINT ck_inx_iam_policy_state CHECK (state IN ('DRAFT','VALIDATED','APPROVED','ACTIVE','DEPRECATED','RETIRED')),
  CONSTRAINT ck_inx_iam_policy_approval CHECK ((approved_by IS NULL AND approved_at IS NULL) OR (approved_by IS NOT NULL AND approved_at IS NOT NULL)),
  CONSTRAINT ck_inx_iam_policy_active CHECK (state NOT IN ('ACTIVE','DEPRECATED','RETIRED') OR activated_at IS NOT NULL),
  CONSTRAINT ck_inx_iam_policy_deprecated CHECK (state NOT IN ('DEPRECATED','RETIRED') OR deprecated_at IS NOT NULL),
  CONSTRAINT ck_inx_iam_policy_retired CHECK (state <> 'RETIRED' OR retired_at IS NOT NULL),
  CONSTRAINT ck_inx_iam_policy_time CHECK (updated_at >= created_at AND effective_from >= created_at)
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_inx_iam_policy_version_platform
  ON infranexum_iam.access_policy(code,policy_version) WHERE organization_id IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_inx_iam_policy_version_org
  ON infranexum_iam.access_policy(organization_id,code,policy_version) WHERE organization_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_inx_iam_policy_active_platform
  ON infranexum_iam.access_policy(code) WHERE organization_id IS NULL AND state='ACTIVE';
CREATE UNIQUE INDEX IF NOT EXISTS uq_inx_iam_policy_active_org
  ON infranexum_iam.access_policy(organization_id,code) WHERE organization_id IS NOT NULL AND state='ACTIVE';
CREATE INDEX IF NOT EXISTS ix_inx_iam_policy_active_scope
  ON infranexum_iam.access_policy(state,organization_id,priority DESC,code,policy_version);

CREATE TABLE IF NOT EXISTS infranexum_iam.access_policy_rule (
  id UUID PRIMARY KEY,
  policy_id UUID NOT NULL,
  position_no INTEGER NOT NULL,
  effect VARCHAR(8) NOT NULL,
  action_selector VARCHAR(128) NOT NULL,
  resource_type VARCHAR(80) NOT NULL,
  obligations_csv VARCHAR(500) NOT NULL DEFAULT '',
  advice VARCHAR(500) NOT NULL DEFAULT '',
  CONSTRAINT fk_inx_iam_policy_rule_policy FOREIGN KEY (policy_id) REFERENCES infranexum_iam.access_policy(id) ON DELETE CASCADE,
  CONSTRAINT uq_inx_iam_policy_rule_position UNIQUE(policy_id,position_no),
  CONSTRAINT ck_inx_iam_policy_rule_uuidv7 CHECK (id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'),
  CONSTRAINT ck_inx_iam_policy_rule_position CHECK (position_no BETWEEN 1 AND 10000),
  CONSTRAINT ck_inx_iam_policy_rule_effect CHECK (effect IN ('PERMIT','DENY'))
);
CREATE INDEX IF NOT EXISTS ix_inx_iam_policy_rule_policy ON infranexum_iam.access_policy_rule(policy_id,position_no);

CREATE TABLE IF NOT EXISTS infranexum_iam.access_policy_condition (
  rule_id UUID NOT NULL,
  position_no INTEGER NOT NULL,
  source_name VARCHAR(24) NOT NULL,
  attribute_name VARCHAR(64) NOT NULL,
  operator_name VARCHAR(16) NOT NULL,
  expected_value VARCHAR(256) NOT NULL,
  PRIMARY KEY(rule_id,position_no),
  CONSTRAINT fk_inx_iam_policy_condition_rule FOREIGN KEY (rule_id) REFERENCES infranexum_iam.access_policy_rule(id) ON DELETE CASCADE,
  CONSTRAINT ck_inx_iam_policy_condition_position CHECK (position_no BETWEEN 1 AND 32),
  CONSTRAINT ck_inx_iam_policy_condition_source CHECK (source_name IN ('SUBJECT','RESOURCE','ORGANIZATION','SUBDIVISION','ENVIRONMENT','AUTHENTICATION','CAPABILITY','RBAC')),
  CONSTRAINT ck_inx_iam_policy_condition_operator CHECK (operator_name IN ('EQUALS','NOT_EQUALS','CONTAINS','EXISTS'))
);

CREATE TABLE IF NOT EXISTS infranexum_iam.sod_constraint (
  id UUID PRIMARY KEY,
  policy_id UUID NOT NULL,
  organization_id UUID NULL,
  first_role_id UUID NOT NULL,
  second_role_id UUID NOT NULL,
  reason VARCHAR(500) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  created_by UUID NOT NULL,
  CONSTRAINT fk_inx_iam_sod_policy FOREIGN KEY (policy_id) REFERENCES infranexum_iam.access_policy(id) ON DELETE CASCADE,
  CONSTRAINT fk_inx_iam_sod_first_role FOREIGN KEY (first_role_id) REFERENCES infranexum_iam.role(id),
  CONSTRAINT fk_inx_iam_sod_second_role FOREIGN KEY (second_role_id) REFERENCES infranexum_iam.role(id),
  CONSTRAINT uq_inx_iam_sod_pair UNIQUE(policy_id,first_role_id,second_role_id),
  CONSTRAINT ck_inx_iam_sod_uuidv7 CHECK (id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'),
  CONSTRAINT ck_inx_iam_sod_distinct CHECK (first_role_id <> second_role_id)
);
CREATE INDEX IF NOT EXISTS ix_inx_iam_sod_lookup ON infranexum_iam.sod_constraint(organization_id,first_role_id,second_role_id);

INSERT INTO infranexum_iam.access_policy(
  id,organization_id,code,policy_version,owner_id,purpose,priority,scope_kind,subdivision_id,state,effective_from,
  approved_by,approved_at,activated_at,deprecated_at,retired_at,created_at,updated_at)
VALUES (
  '00000000-0000-7000-8000-000000000110',NULL,'system.rbac-bridge',1,
  '00000000-0000-7000-8000-000000000001','Preserve RBAC permits while advanced authorization policies are introduced.',0,
  'PLATFORM',NULL,'ACTIVE','2026-08-14T00:00:00Z','00000000-0000-7000-8000-000000000001','2026-08-14T00:00:00Z',
  '2026-08-14T00:00:00Z',NULL,NULL,'2026-08-14T00:00:00Z','2026-08-14T00:00:00Z')
ON CONFLICT DO NOTHING;

INSERT INTO infranexum_iam.access_policy_rule(id,policy_id,position_no,effect,action_selector,resource_type,obligations_csv,advice)
VALUES ('00000000-0000-7000-8000-000000000111','00000000-0000-7000-8000-000000000110',1,'PERMIT','*','*','','RBAC permit bridged into advanced authorization.')
ON CONFLICT DO NOTHING;

INSERT INTO infranexum_iam.access_policy_condition(rule_id,position_no,source_name,attribute_name,operator_name,expected_value)
VALUES ('00000000-0000-7000-8000-000000000111',1,'RBAC','permitted','EQUALS','true')
ON CONFLICT DO NOTHING;
