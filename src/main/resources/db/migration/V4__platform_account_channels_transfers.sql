-- Money held by a fundraising platform (BackaBuddy) is tracked like an account:
-- donations land there, fees come out of it, and a payout is a TRANSFER to the bank.
ALTER TABLE accounts DROP CONSTRAINT accounts_type_check;
ALTER TABLE accounts ADD CONSTRAINT accounts_type_check CHECK (type IN ('BANK','CASH','PLATFORM'));

INSERT INTO accounts (name, type) VALUES ('BackaBuddy (held by platform)', 'PLATFORM');

-- Where the money came in (for reports and reconciliation).
ALTER TABLE transactions ADD COLUMN channel VARCHAR(20) NOT NULL DEFAULT 'BANK_DEPOSIT'
    CHECK (channel IN ('BANK_DEPOSIT','CASH','BACKABUDDY','OTHER'));

-- Transfers move money between accounts (e.g. BackaBuddy payout into Capitec).
ALTER TABLE transactions ADD COLUMN to_account_id BIGINT REFERENCES accounts(id);
ALTER TABLE transactions ADD CONSTRAINT transfer_needs_destination
    CHECK ((kind = 'TRANSFER') = (to_account_id IS NOT NULL) AND to_account_id IS DISTINCT FROM account_id);

INSERT INTO categories (name, kind) VALUES ('Platform & transaction fees', 'EXPENSE');
