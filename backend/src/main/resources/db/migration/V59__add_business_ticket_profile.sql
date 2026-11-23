ALTER TABLE app.business_settings
    ADD COLUMN business_name VARCHAR(120) NOT NULL DEFAULT '',
    ADD COLUMN business_address VARCHAR(240) NOT NULL DEFAULT '',
    ADD COLUMN business_phone VARCHAR(60) NOT NULL DEFAULT '';
