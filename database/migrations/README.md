# Database migrations

Authoritative Flyway SQL lives here and is packaged into `db/migration` by Maven.

- `V1__financial_records.sql`: SMEs, invoices, actual cash transactions, constraints/indexes.
- `V2__scenario_inputs.sql`: scenarios and typed assumptions, constraints/indexes.
- `R__schema_comments.sql`: repeatable table/column documentation; no data or calculations.

Boot applies pending migrations at startup. Never edit an applied versioned migration; add a new version. Repeatable scripts rerun when their checksums change. No clean, automatic baseline, or second initializer is configured. See `../../docs/database.md` and `../../docs/database-schema.md`.
