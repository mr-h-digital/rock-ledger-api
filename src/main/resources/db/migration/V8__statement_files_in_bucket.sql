ALTER TABLE bank_statements
    ALTER COLUMN pdf DROP NOT NULL,
    ADD COLUMN storage_key VARCHAR(500),
    ADD CONSTRAINT bank_statements_file_location_check
        CHECK ((pdf IS NULL) <> (storage_key IS NULL));
