-- Bank statements are imported into a review area first. Nothing reaches the ledger until a person posts it.
CREATE TABLE bank_statements (
    id              BIGSERIAL PRIMARY KEY,
    account_id      BIGINT        NOT NULL REFERENCES accounts(id),
    file_name       VARCHAR(300)  NOT NULL,
    sha256          CHAR(64)      NOT NULL UNIQUE,
    layout          VARCHAR(10)   NOT NULL,
    period_from     DATE,
    period_to       DATE,
    opening_balance NUMERIC(19,2) NOT NULL,
    closing_balance NUMERIC(19,2) NOT NULL,
    line_count      INT           NOT NULL,
    pdf             BYTEA         NOT NULL,   -- original statement kept as evidence (move to the bucket later)
    imported_by     VARCHAR(120)  NOT NULL,
    imported_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE bank_lines (
    id                 BIGSERIAL PRIMARY KEY,
    statement_id       BIGINT        NOT NULL REFERENCES bank_statements(id),
    account_id         BIGINT        NOT NULL REFERENCES accounts(id),
    post_date          DATE,
    txn_date           DATE          NOT NULL,
    description        TEXT          NOT NULL,
    amount             NUMERIC(19,2) NOT NULL,   -- positive = money in, negative = money out (excludes fee)
    fee                NUMERIC(19,2) NOT NULL DEFAULT 0,
    balance_after      NUMERIC(19,2) NOT NULL,
    line_hash          CHAR(64)      NOT NULL UNIQUE,   -- stops overlapping statements importing twice
    transaction_id     BIGINT REFERENCES transactions(id),
    fee_transaction_id BIGINT REFERENCES transactions(id),
    posted_by          VARCHAR(120),
    posted_at          TIMESTAMPTZ
);
CREATE INDEX idx_bank_lines_open ON bank_lines (txn_date) WHERE transaction_id IS NULL;

INSERT INTO categories (name, kind) VALUES ('Bank charges', 'EXPENSE');
