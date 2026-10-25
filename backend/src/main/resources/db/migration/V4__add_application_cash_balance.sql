ALTER TABLE credit_application
    ADD COLUMN cash_balance numeric(14,2)
    CONSTRAINT ck_application_cash_balance CHECK (cash_balance BETWEEN 0 AND 999999999999.99);
