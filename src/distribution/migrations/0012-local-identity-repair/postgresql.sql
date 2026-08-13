CREATE SCHEMA IF NOT EXISTS infranexum_iam;
CREATE TABLE IF NOT EXISTS infranexum_iam.local_account (
 id UUID PRIMARY KEY,
 username VARCHAR(128) NOT NULL UNIQUE,
 display_name VARCHAR(160) NOT NULL,
 password_hash VARCHAR(1024) NOT NULL,
 must_change BOOLEAN NOT NULL,
 status VARCHAR(16) NOT NULL,
 failed_attempts INTEGER NOT NULL,
 locked_until TIMESTAMPTZ NULL,
 security_epoch BIGINT NOT NULL,
 version BIGINT NOT NULL,
 created_at TIMESTAMPTZ NOT NULL,
 updated_at TIMESTAMPTZ NOT NULL,
 CONSTRAINT ck_inx_iam_local_username CHECK (username ~ '^[a-z0-9][a-z0-9._@-]{2,127}$'),
 CONSTRAINT ck_inx_iam_local_status CHECK (status IN ('ACTIVE','SUSPENDED')),
 CONSTRAINT ck_inx_iam_local_failed CHECK (failed_attempts >= 0),
 CONSTRAINT ck_inx_iam_local_epoch CHECK (security_epoch >= 0),
 CONSTRAINT ck_inx_iam_local_version CHECK (version >= 0),
 CONSTRAINT ck_inx_iam_local_time CHECK (updated_at >= created_at AND (locked_until IS NULL OR locked_until >= created_at)),
 CONSTRAINT ck_inx_iam_local_uuidv7 CHECK (SUBSTRING(id::TEXT FROM 15 FOR 1)='7' AND SUBSTRING(id::TEXT FROM 20 FOR 1) IN ('8','9','a','b'))
);
CREATE INDEX IF NOT EXISTS ix_inx_iam_local_status ON infranexum_iam.local_account(status, locked_until);
CREATE TABLE IF NOT EXISTS infranexum_iam.local_session (
 id UUID PRIMARY KEY,
 account_id UUID NOT NULL REFERENCES infranexum_iam.local_account(id),
 token_hash CHAR(64) NOT NULL UNIQUE,
 csrf_hash CHAR(64) NOT NULL,
 security_epoch BIGINT NOT NULL,
 created_at TIMESTAMPTZ NOT NULL,
 last_seen_at TIMESTAMPTZ NOT NULL,
 idle_expires_at TIMESTAMPTZ NOT NULL,
 absolute_expires_at TIMESTAMPTZ NOT NULL,
 revoked_at TIMESTAMPTZ NULL,
 CONSTRAINT ck_inx_iam_session_token CHECK (token_hash ~ '^[0-9a-f]{64}$'),
 CONSTRAINT ck_inx_iam_session_csrf CHECK (csrf_hash ~ '^[0-9a-f]{64}$'),
 CONSTRAINT ck_inx_iam_session_epoch CHECK (security_epoch >= 0),
 CONSTRAINT ck_inx_iam_session_expiry CHECK (last_seen_at >= created_at AND idle_expires_at > last_seen_at AND idle_expires_at <= absolute_expires_at AND absolute_expires_at > created_at AND (revoked_at IS NULL OR revoked_at >= created_at)),
 CONSTRAINT ck_inx_iam_session_uuidv7 CHECK (SUBSTRING(id::TEXT FROM 15 FOR 1)='7' AND SUBSTRING(id::TEXT FROM 20 FOR 1) IN ('8','9','a','b'))
);
CREATE INDEX IF NOT EXISTS ix_inx_iam_session_account ON infranexum_iam.local_session(account_id, revoked_at, absolute_expires_at);

DO $$
BEGIN
  IF to_regclass('infranexum_iam.local_account') IS NULL THEN
    RAISE EXCEPTION 'local identity repair failed: local_account is absent';
  END IF;
  IF to_regclass('infranexum_iam.local_session') IS NULL THEN
    RAISE EXCEPTION 'local identity repair failed: local_session is absent';
  END IF;
END $$;
