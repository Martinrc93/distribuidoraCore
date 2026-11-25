ALTER TABLE identity.users
    ADD COLUMN session_version BIGINT NOT NULL DEFAULT 0;

UPDATE identity.users
   SET session_version = version;
