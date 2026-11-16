# Synthetic financial data

The only dataset here is **entirely synthetic**, generated locally from project code. No real companies, accounts, personal records, or external datasets are included.

- Generator: `backend/src/main/java/com/finsight/synthetic/SyntheticGenerator.java`, version FS1.
- Fixed default seed: 20261018.
- Default artifacts: `synthetic/FS1-20261018/` (five labelled CSV files plus checksum manifest).
- Range: 2024-01-01 through 2025-12-31 inclusive; 731 daily dates for each of 3 fictional USD SMEs.
- Counts: 3 opening inputs, 942 invoices, 2514 transactions (including 932 linked payments), 2193 daily cash-flow rows.
- Provenance: invented integer-cent generation rules, not public/real SME observations. No empirical behavioral or model-performance claims.

Run `./scripts/synthetic_data.sh generate --seed 20261018 --output data/synthetic/FS1-20261018` after building the Java JAR. Generation does not connect to a database. Loading requires the separate explicit `load --seed 20261018` command and exported local environment credentials; nothing seeds on application startup.

Opening balances remain dated external inputs and are never inserted as cash transactions. Invoices are not cash movements; their linked payment rows are already in `transactions.csv`. `daily_cash_flow.csv` contains deterministic historical arithmetic, not predictions. Final-cutoff invoice status must not be used as a historical forecasting feature.

See [design, commands, assumptions, and limitations](../docs/synthetic-data.md), [verification](../docs/synthetic-data-verification.md), and [manifest](synthetic/FS1-20261018/manifest.json).

Any future nonsynthetic data must have its source, license, units, and limitations documented separately before use. Never place credentials or real private financial records in this directory.
