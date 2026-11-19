CREATE SCHEMA expense;

CREATE TABLE expense.expenses (
    id UUID PRIMARY KEY,
    amount NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    type VARCHAR(20) NOT NULL CHECK (type IN ('PETTY_CASH', 'OTHER', 'SERVICES', 'FUEL')),
    observation VARCHAR(500),
    created_by UUID NOT NULL REFERENCES identity.users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX ix_expenses_created_at ON expense.expenses (created_at DESC, id DESC);
