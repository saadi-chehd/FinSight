# Financial database design

## Tables and relationships

| Table | Stored information | Relationships |
| --- | --- | --- |
| `smes` | Generated ID, name that is not empty/all spaces, one supported reporting currency, creation instant | Parent of all company records |
| `invoices` | SME/currency, scoped reference, receivable/payable direction, positive face amount, issue/due dates, recorded payment status | Belongs to one SME; may have multiple linked cash transactions |
| `financial_transactions` | SME/currency, actual booked date, direction, transaction type, positive amount, optional description and invoice | Belongs to one SME; optional link to an invoice of the same SME/currency/direction |
| `scenarios` | SME, name, kind, creation instant | Belongs to one SME; parent of typed inputs |
| `scenario_inputs` | Scenario/SME/currency/kind plus a delayed-invoice reference and delay, or a proposed contract receipt and expected date | Matches scenario SME/kind and SME currency; optional invoice must match SME/currency |

All IDs use `BIGINT GENERATED ALWAYS AS IDENTITY`. Foreign keys restrict deletion by default; no automatic cascading deletion of financial records. Names are not unique company identifiers; authentication, legal registration numbers, and tenant authorization are future work. Ownership constraints preserve relationships; they are not user-access authorization or row-level security.

## Currency and money

This prototype supports **USD, EUR, GBP, TRY**, all with two decimal minor units. Each SME has one currency; child records repeat it with composite foreign keys to enforce agreement. No conversion, mixed-currency consolidation, or FX rate table exists.

Amounts use PostgreSQL `NUMERIC(19,2)` and Java `BigDecimal`. Maximum positive value is `99999999999999999.99`. Financial records use positive magnitudes; zero, negative, NaN, and infinite amounts are rejected. Direction is explicit:

- `INFLOW`: money entering the SME; an invoice with this direction is a receivable.
- `OUTFLOW`: money leaving the SME; an invoice with this direction is a payable.

The transaction repository validates positive amounts, exact whole minor units using `RoundingMode.UNNECESSARY`, and precision before insertion. PostgreSQL itself rounds excess fractional digits when assigning to `NUMERIC(19,2)`; direct SQL clients must validate scale themselves. No financial invoice/scenario write API exists yet; future Java write boundaries must apply the same validation before writing their monetary fields. Do not describe NUMERIC alone as rejecting all excess decimal places.

Transaction types are `CUSTOMER_RECEIPT`, `SUPPLIER_PAYMENT`, `PAYROLL`, `TAX`, `FINANCING_RECEIPT`, `FINANCING_REPAYMENT`, and `OTHER`. Named receipt types require INFLOW; named payment/repayment types require OUTFLOW; OTHER permits either. These are movement classifications, not accounting calculations or automatic categorizations. Refunds and other movements can use OTHER with the explicit direction until a later schema adds types.

## Dates and status

Financial dates use `DATE` / `LocalDate`, without a timezone: `booked_date`, invoice `issue_date`/`due_date`, and scenario `expected_date`. Infinite dates are rejected. Invoice due date cannot precede issue date. Backdated/future dates remain valid because business date policies are outside this milestone.

`created_at` uses `TIMESTAMPTZ`, defaults to the insertion instant, and is read as an offset-aware value. Both databases default to UTC. Calendar dates must not be shifted by UTC conversion.

Payment status is one of `UNPAID`, `PARTIALLY_PAID`, `PAID`, `CANCELLED`. It remains explicitly recorded; no trigger or Java code updates it from transactions. The financial engine derives a separate effective status and flags mismatches in read results; see [financial-engine.md](financial-engine.md). Overdue depends on an as-of date and is not a stored status. Several transactions may reference one invoice; the database does not calculate remaining balances, enforce a payment total, or prohibit links based on invoice status. A transaction can reference at most one invoice; multi-invoice allocation is deferred.

## Scenario assumptions

Scenario kinds are `BASELINE`, `LATE_PAYMENT`, `NEW_CONTRACT`.

- Baseline scenarios have no input rows: input kinds deliberately exclude BASELINE, and a composite FK enforces kind agreement.
- Late-payment inputs require an invoice ID and a positive delay of 1–3650 days (an explicit prototype bound); monetary amount and expected date must be null. Either a receivable or payable may be delayed; execution is deferred.
- New-contract inputs require a positive amount and finite expected receipt date; invoice and delay fields must be null. Each row represents one proposed receipt event, rather than legal contract lifecycle management.

Scenario inputs do not update invoices or create actual transactions. There is no scenario execution or generated forecast. Opening cash is supplied to the Java engine as a dated input, and cash-flow calculations run in Java without storing results or changing this schema.

## Constraints and indexes

Primary/unique indexes cover identities, SME+currency keys, same-SME invoice links, and scoped invoice references. References are case-sensitive and unique within an SME. Required strings cannot be empty or consist only of spaces.

Additional indexes support `(sme_id, booked_date)` transaction queries, `(sme_id, payment_status, due_date)` invoice queries, SME scenario queries, and scenario/input/invoice foreign-key lookups. Optional invoice-link indexes are partial (`IS NOT NULL`). Constraints reject unsupported enums, missing required fields, inconsistent type/direction, invalid input shapes, and mismatched ownership/currency.

No performance claims have been measured. Query plans should be revisited when real usage and properly sourced data exist.
