CREATE INDEX IF NOT EXISTS ix_inx_itam_asset_sync_cursor
  ON infranexum_itam.asset(updated_at,id);
