CREATE TABLE accounts (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(120) NOT NULL UNIQUE,
    type        VARCHAR(20)  NOT NULL CHECK (type IN ('BANK','CASH')),
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE funds (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(120) NOT NULL UNIQUE,
    restricted  BOOLEAN      NOT NULL DEFAULT FALSE,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE categories (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(120) NOT NULL,
    kind        VARCHAR(10)  NOT NULL CHECK (kind IN ('INCOME','EXPENSE')),
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    UNIQUE (name, kind)
);

CREATE TABLE transactions (
    id             BIGSERIAL PRIMARY KEY,
    txn_date       DATE          NOT NULL,
    kind           VARCHAR(10)   NOT NULL CHECK (kind IN ('INCOME','EXPENSE','TRANSFER')),
    amount         NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    account_id     BIGINT        NOT NULL REFERENCES accounts(id),
    fund_id        BIGINT        NOT NULL REFERENCES funds(id),
    category_id    BIGINT        REFERENCES categories(id),
    counterparty   VARCHAR(200),
    reference      VARCHAR(200),
    notes          TEXT,
    reversal_of_id BIGINT        REFERENCES transactions(id),
    created_by     VARCHAR(120)  NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_txn_date ON transactions (txn_date);
CREATE INDEX idx_txn_fund ON transactions (fund_id);

CREATE TABLE attachments (
    id             BIGSERIAL PRIMARY KEY,
    transaction_id BIGINT       NOT NULL REFERENCES transactions(id),
    object_key     VARCHAR(300) NOT NULL,
    file_name      VARCHAR(300) NOT NULL,
    content_type   VARCHAR(100),
    uploaded_by    VARCHAR(120) NOT NULL,
    uploaded_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE audit_log (
    id          BIGSERIAL PRIMARY KEY,
    actor       VARCHAR(120) NOT NULL,
    action      VARCHAR(60)  NOT NULL,
    entity      VARCHAR(60)  NOT NULL,
    entity_id   BIGINT,
    detail      TEXT,
    at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Starter data
INSERT INTO funds (name, restricted) VALUES
    ('General', FALSE), ('Youth Outreach', TRUE), ('Benevolence', TRUE), ('Building', TRUE);
INSERT INTO categories (name, kind) VALUES
    ('Tithes', 'INCOME'), ('Offerings', 'INCOME'), ('Donations', 'INCOME'),
    ('Grants', 'INCOME'), ('Fundraising', 'INCOME'),
    ('Outreach costs', 'EXPENSE'), ('Stipends', 'EXPENSE'), ('Rent & utilities', 'EXPENSE'),
    ('Admin & compliance', 'EXPENSE'), ('Benevolence aid', 'EXPENSE');
