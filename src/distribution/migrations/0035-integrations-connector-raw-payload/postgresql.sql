ALTER TABLE infranexum_integrations.connector_inbox ADD COLUMN payload_raw TEXT;
UPDATE infranexum_integrations.connector_inbox SET payload_raw = payload_json::text WHERE payload_raw IS NULL;
ALTER TABLE infranexum_integrations.connector_inbox ALTER COLUMN payload_raw SET NOT NULL;
