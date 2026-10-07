-- The ministry has a single bank account. Only the last 4 digits are stored on purpose:
-- the full account number is not needed to keep the books.
ALTER TABLE accounts ADD COLUMN opened_on DATE;
ALTER TABLE accounts ADD COLUMN last4 CHAR(4);

INSERT INTO accounts (name, type, opened_on, last4)
VALUES ('Capitec Business Account', 'BANK', DATE '2022-11-12', '8928');
