-- Organisation profile (single row). Keep personal data of directors OUT of this table.
CREATE TABLE organisation (
    id                   SMALLINT PRIMARY KEY CHECK (id = 1),
    name                 VARCHAR(200) NOT NULL,
    registration_number  VARCHAR(30)  NOT NULL,
    registered_on        DATE         NOT NULL,
    year_end_month       SMALLINT     NOT NULL CHECK (year_end_month BETWEEN 1 AND 12),
    tax_number           VARCHAR(30),
    pbo_status           VARCHAR(20)  NOT NULL DEFAULT 'NONE' CHECK (pbo_status IN ('NONE','APPLIED','APPROVED'))
);

INSERT INTO organisation (id, name, registration_number, registered_on, year_end_month)
VALUES (1, 'Rock Mission Ministries NPC', '2022/798592/08', DATE '2022-10-26', 4);

-- Financial years run 1 May to 30 April. The first year is short (incorporation to 30 April 2023).
CREATE TABLE financial_years (
    id          BIGSERIAL PRIMARY KEY,
    label       VARCHAR(20) NOT NULL UNIQUE,
    start_date  DATE        NOT NULL,
    end_date    DATE        NOT NULL,
    status      VARCHAR(10) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','CLOSED')),
    closed_by   VARCHAR(120),
    closed_at   TIMESTAMPTZ,
    CHECK (end_date > start_date)
);

INSERT INTO financial_years (label, start_date, end_date) VALUES
    ('FY2023', DATE '2022-10-26', DATE '2023-04-30'),
    ('FY2024', DATE '2023-05-01', DATE '2024-04-30'),
    ('FY2025', DATE '2024-05-01', DATE '2025-04-30'),
    ('FY2026', DATE '2025-05-01', DATE '2026-04-30'),
    ('FY2027', DATE '2026-05-01', DATE '2027-04-30'),
    ('FY2028', DATE '2027-05-01', DATE '2028-04-30');

-- Ledger integrity enforced in the database itself:
--  1. entries can never be updated or deleted (corrections are reversals)
--  2. nothing can be posted into a closed financial year
CREATE FUNCTION transactions_guard() RETURNS trigger AS $$
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        RAISE EXCEPTION 'Ledger entries are immutable; post a reversal instead';
    END IF;
    IF EXISTS (SELECT 1 FROM financial_years
               WHERE status = 'CLOSED' AND NEW.txn_date BETWEEN start_date AND end_date) THEN
        RAISE EXCEPTION 'Financial year containing % is closed', NEW.txn_date;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER transactions_guard_trg
    BEFORE INSERT OR UPDATE OR DELETE ON transactions
    FOR EACH ROW EXECUTE FUNCTION transactions_guard();
