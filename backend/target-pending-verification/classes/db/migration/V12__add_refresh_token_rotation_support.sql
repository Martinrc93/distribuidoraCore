ALTER TABLE identity.refresh_tokens
    ADD COLUMN replaced_by UUID REFERENCES identity.refresh_tokens (id);
