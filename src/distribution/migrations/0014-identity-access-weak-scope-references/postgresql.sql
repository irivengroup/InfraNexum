-- PGM-04-E02 / ADR-0029 remediation: Organization-owned identifiers remain weak IAM references.
ALTER TABLE infranexum_iam.user_membership DROP CONSTRAINT IF EXISTS user_membership_organization_id_fkey;
ALTER TABLE infranexum_iam.user_membership DROP CONSTRAINT IF EXISTS fk_inx_iam_membership_sub;
ALTER TABLE infranexum_iam.iam_group DROP CONSTRAINT IF EXISTS iam_group_organization_id_fkey;
ALTER TABLE infranexum_iam.permission DROP CONSTRAINT IF EXISTS permission_organization_id_fkey;
ALTER TABLE infranexum_iam.role DROP CONSTRAINT IF EXISTS role_organization_id_fkey;
ALTER TABLE infranexum_iam.role_assignment DROP CONSTRAINT IF EXISTS role_assignment_organization_id_fkey;
ALTER TABLE infranexum_iam.role_assignment DROP CONSTRAINT IF EXISTS fk_inx_iam_ra_sub;
