DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM infranexum_dcim.equipment_model WHERE equipment_category <> 'OTHER' OR equipment_type <> 'OTHER_EQUIPMENT' OR manufacturer_reference IS NOT NULL OR rack_units=0 OR width_mm=0 OR depth_mm=0 OR weight_kg=0)
     OR EXISTS (SELECT 1 FROM infranexum_dcim.cable_connection WHERE cable_type <> 'OTHER' OR length_meters <> 1 OR manufacturer_partner_id IS NOT NULL OR manufacturer_reference IS NOT NULL) THEN
    RAISE EXCEPTION 'rollback would discard DCIM taxonomy/cable metadata; migrate or export affected records first';
  END IF;
END $$;
ALTER TABLE infranexum_dcim.cable_connection DROP CONSTRAINT IF EXISTS ck_inx_dcim_cable_type;
ALTER TABLE infranexum_dcim.cable_connection DROP CONSTRAINT IF EXISTS ck_inx_dcim_cable_vendor_ref;
ALTER TABLE infranexum_dcim.cable_connection DROP CONSTRAINT IF EXISTS ck_inx_dcim_cable_length;
ALTER TABLE infranexum_dcim.cable_connection DROP COLUMN IF EXISTS manufacturer_reference;
ALTER TABLE infranexum_dcim.cable_connection DROP COLUMN IF EXISTS manufacturer_partner_id;
ALTER TABLE infranexum_dcim.cable_connection DROP COLUMN IF EXISTS length_meters;
ALTER TABLE infranexum_dcim.cable_connection DROP COLUMN IF EXISTS cable_type;
ALTER TABLE infranexum_dcim.equipment_model DROP CONSTRAINT IF EXISTS ck_inx_dcim_model_rack_dims;
ALTER TABLE infranexum_dcim.equipment_model DROP CONSTRAINT IF EXISTS ck_inx_dcim_model_taxonomy;
ALTER TABLE infranexum_dcim.equipment_model DROP CONSTRAINT IF EXISTS ck_inx_dcim_model_type;
ALTER TABLE infranexum_dcim.equipment_model DROP CONSTRAINT IF EXISTS ck_inx_dcim_model_category;
ALTER TABLE infranexum_dcim.equipment_model DROP CONSTRAINT IF EXISTS ck_inx_dcim_model_dims;
ALTER TABLE infranexum_dcim.equipment_model ADD CONSTRAINT ck_inx_dcim_model_dims CHECK(rack_units BETWEEN 1 AND 100 AND width_mm BETWEEN 1 AND 5000 AND depth_mm BETWEEN 1 AND 5000 AND weight_kg>0);
ALTER TABLE infranexum_dcim.equipment_model DROP COLUMN IF EXISTS manufacturer_reference;
ALTER TABLE infranexum_dcim.equipment_model DROP COLUMN IF EXISTS equipment_type;
ALTER TABLE infranexum_dcim.equipment_model DROP COLUMN IF EXISTS equipment_category;
