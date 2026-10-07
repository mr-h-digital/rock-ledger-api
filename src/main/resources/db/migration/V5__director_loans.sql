-- Money a director lends to the ministry is NOT income, and repaying it is NOT an expense.
-- LOAN_IN  = director lends money to the ministry (bank balance goes up, loan owed goes up)
-- LOAN_OUT = ministry repays a director (bank balance goes down, loan owed goes down)
-- The counterparty field holds the lender's name. Gifts that need not be repaid stay INCOME
-- (category "Donations"); expenses a director pays personally are an EXPENSE plus a LOAN_IN.
ALTER TABLE transactions DROP CONSTRAINT transactions_kind_check;
ALTER TABLE transactions ADD CONSTRAINT transactions_kind_check
    CHECK (kind IN ('INCOME','EXPENSE','TRANSFER','LOAN_IN','LOAN_OUT'));
ALTER TABLE transactions ADD CONSTRAINT loans_need_lender
    CHECK (kind NOT IN ('LOAN_IN','LOAN_OUT') OR (counterparty IS NOT NULL AND counterparty <> ''));
