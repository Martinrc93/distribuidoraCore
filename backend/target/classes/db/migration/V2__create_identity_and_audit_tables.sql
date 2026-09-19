CREATE TABLE identity.users (
    id UUID PRIMARY KEY,
    email VARCHAR(320) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL,
    failed_login_attempts SMALLINT NOT NULL DEFAULT 0,
    locked_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_users_status CHECK (status IN ('INVITED', 'ACTIVE', 'BLOCKED', 'DISABLED')),
    CONSTRAINT ck_users_failed_login_attempts CHECK (failed_login_attempts >= 0)
);

CREATE UNIQUE INDEX ux_users_email_lower ON identity.users (LOWER(email));

CREATE TABLE identity.roles (
    id UUID PRIMARY KEY,
    code VARCHAR(80) NOT NULL UNIQUE,
    description VARCHAR(255) NOT NULL
);

CREATE TABLE identity.permissions (
    id UUID PRIMARY KEY,
    code VARCHAR(80) NOT NULL UNIQUE,
    description VARCHAR(255) NOT NULL
);

CREATE TABLE identity.user_roles (
    user_id UUID NOT NULL REFERENCES identity.users (id),
    role_id UUID NOT NULL REFERENCES identity.roles (id),
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE identity.role_permissions (
    role_id UUID NOT NULL REFERENCES identity.roles (id),
    permission_id UUID NOT NULL REFERENCES identity.permissions (id),
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE identity.refresh_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES identity.users (id),
    token_hash VARCHAR(128) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_refresh_tokens_user_id ON identity.refresh_tokens (user_id);

CREATE TABLE audit.audit_events (
    id UUID PRIMARY KEY,
    actor_user_id UUID,
    operation VARCHAR(120) NOT NULL,
    resource_type VARCHAR(120) NOT NULL,
    resource_id VARCHAR(120),
    result VARCHAR(30) NOT NULL,
    correlation_id VARCHAR(120) NOT NULL,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_audit_result CHECK (result IN ('SUCCESS', 'FAILURE'))
);

CREATE INDEX ix_audit_events_created_at ON audit.audit_events (created_at DESC);
CREATE INDEX ix_audit_events_correlation_id ON audit.audit_events (correlation_id);
