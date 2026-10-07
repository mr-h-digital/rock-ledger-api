CREATE TABLE users (
    id                   BIGSERIAL PRIMARY KEY,
    email                VARCHAR(200) NOT NULL UNIQUE,          -- stored lower-case
    full_name            VARCHAR(120) NOT NULL,
    role                 VARCHAR(10)  NOT NULL CHECK (role IN ('ADMIN','TREASURER','VIEWER')),
    password_hash        VARCHAR(100) NOT NULL,                 -- BCrypt
    must_change_password BOOLEAN      NOT NULL DEFAULT TRUE,
    totp_secret_enc      TEXT,                                  -- AES-GCM encrypted authenticator secret
    totp_enabled         BOOLEAN      NOT NULL DEFAULT FALSE,
    last_totp_step       BIGINT,                                -- stops a code being used twice
    active               BOOLEAN      NOT NULL DEFAULT TRUE,
    failed_attempts      INT          NOT NULL DEFAULT 0,
    locked_until         TIMESTAMPTZ,
    last_login_at        TIMESTAMPTZ,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE refresh_tokens (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  CHAR(64)    NOT NULL UNIQUE,                    -- only a hash of the token is stored
    expires_at  TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
