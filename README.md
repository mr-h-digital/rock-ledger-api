# Rock Ledger — Backend

The Spring Boot REST API and PostgreSQL persistence layer for Rock Ledger. It provides authenticated ledger operations, user administration, immutable transaction history, and Capitec PDF statement import.

[Project overview and deployment guide](../README.md) · [Frontend documentation](../frontend/README.md)

## Stack

- Java 21 and Spring Boot 3.3
- Maven
- PostgreSQL
- Spring Security with signed JWT access tokens
- Flyway database migrations
- Apache PDFBox for statement text extraction

## Requirements

- JDK 21
- Maven 3.6.3 or later
- PostgreSQL 16 (or a compatible PostgreSQL server)

## Run locally

Start PostgreSQL. For example, using Docker:

```bash
docker run -d --name ledger-db `
  -e POSTGRES_USER=ledger `
  -e POSTGRES_PASSWORD=ledger `
  -e POSTGRES_DB=ledger `
  -p 5432:5432 postgres:16
```

PowerShell uses the backtick for line continuation; the same command can be written on one line or adapted for another shell.

Set the required application secret and bootstrap-admin credentials, then start the API from this directory:

```bash
$env:APP_SECRET = (openssl rand -base64 48)
$env:BOOTSTRAP_ADMIN_EMAIL = "you@example.org"
$env:BOOTSTRAP_ADMIN_PASSWORD = "a-temporary-12+char-password"
$env:COOKIE_SECURE = "false"
mvn spring-boot:run
```

`APP_SECRET` must be a random string of at least 32 characters. The bootstrap admin is created only when the users table is empty. Use a temporary password, sign in, replace it, enrol an authenticator app, and remove the bootstrap credentials from the environment afterward.

The defaults connect to `jdbc:postgresql://localhost:5432/ledger` as `ledger` / `ledger`. Flyway applies the schema and seed migrations automatically. The API listens on `http://localhost:8080`; its health endpoint is `GET /actuator/health`.

## Configuration

| Variable | Purpose | Default |
|---|---|---|
| `PORT` | HTTP server port | `8080` |
| `DATABASE_URL` | JDBC PostgreSQL URL | `jdbc:postgresql://localhost:5432/ledger` |
| `DATABASE_USER` | Database username | `ledger` |
| `DATABASE_PASSWORD` | Database password | `ledger` |
| `APP_SECRET` | Random secret (32+ characters), used to derive JWT and TOTP-encryption keys | Required |
| `ALLOWED_ORIGIN` | Exact allowed browser origin for credentialed CORS | `http://localhost:5173` |
| `COOKIE_SECURE` | Set refresh-cookie `Secure` flag; disable only for local HTTP development | `true` |
| `BOOTSTRAP_ADMIN_EMAIL` | Initial administrator email, used only if there are no users | Empty |
| `BOOTSTRAP_ADMIN_NAME` | Initial administrator name | `Administrator` |
| `BOOTSTRAP_ADMIN_PASSWORD` | Initial administrator's temporary password | Empty |

Changing `APP_SECRET` invalidates existing sessions and makes stored authenticator secrets unreadable; users will need their authenticator setup reset. Keep it stable, private, and backed up securely. Never commit production secrets.

## API overview

All API routes are under `/api`. Protected endpoints require a bearer access token, obtained through the sign-in flow.

| Area | Routes |
|---|---|
| Authentication | `POST /auth/login`, `/auth/set-password`, `/auth/enrol/start`, `/auth/enrol/confirm`, `/auth/verify`, `/auth/refresh`, `/auth/logout`, `/auth/change-password` |
| Lookups | `GET /lookups` |
| Transactions | `GET /transactions`, `POST /transactions`, `POST /transactions/{id}/reverse` |
| Bank statements | `POST /bank-statements`, `GET /bank-statements` |
| Bank lines | `GET /bank-lines?status=unposted\|posted\|all`, `POST /bank-lines/{id}/post`, `POST /bank-lines/post-fees` |
| Organisation and reports | `GET /organisation`, `GET /financial-years`, `GET /reports/year/{id}`, `GET /reports/balances`, `GET /reports/director-loans` |
| User administration | `GET /users`, `POST /users`, `POST /users/{id}/reset`, `PATCH /users/{id}/active` |

Role authorization is enforced by the API:

| Role | Access |
|---|---|
| `ADMIN` | All application operations, including user administration |
| `TREASURER` | Ledger capture, statement import/review/posting, and read access |
| `VIEWER` | Read-only access; cannot access imported bank statements |

## Accounting and statement handling

- Transactions are not edited or deleted. Use the reversal endpoint to record a correction; the original entry remains in the audit trail.
- Loans are represented separately from income and expenses (`LOAN_IN` and `LOAN_OUT`).
- Financial-year boundaries and posting restrictions are maintained by database migrations and constraints.
- Statement PDFs are retained in the database as evidence. Uploads are limited to 10 MB per file.
- Statement imports detect duplicate statements and duplicate lines, return opening/closing-balance continuity warnings, and queue new lines for explicit review before posting.
- Bank fees can be posted as Bank charges; transaction fees discovered on a posted line are recorded separately.

## Database migrations

Flyway migration scripts live in `src/main/resources/db/migration/`. They create the ledger schema and seed the organisation, accounts, financial years, categories, and authentication structures. Do not edit a migration that has already been applied to a deployed database; add a new versioned migration instead.

## Build and tests

```bash
mvn test
mvn package
```

The packaged Spring Boot JAR is written to `target/`.

## Deployment

Deploy this directory as the backend service and configure a PostgreSQL database plus the production variables above. Set `ALLOWED_ORIGIN` to the exact frontend origin and keep `COOKIE_SECURE=true` in HTTPS environments. For Railway and custom-domain setup, follow the [root deployment guide](../README.md).

## Source layout

```text
src/main/java/za/co/rockmission/ledger/
  auth/          Password, TOTP, token, and user-management flows
  config/        Spring Security, authorization, and CORS
  lookup/        Capture-form reference data
  report/        Financial-year, balance, and loan reports
  statement/     PDF extraction, parsing, and bank-line posting
  transaction/   Ledger transaction API and persistence
src/main/resources/
  application.yml
  db/migration/  Versioned Flyway SQL migrations
```
