# Deterministic financial calculation engine

## Scope and entry points

The engine runs inside the existing Java backend. The engine requires no schema/migration, extra dependency, HTTP route, deployed service, dashboard, ML model, or forecast. Separate manual synthetic tooling is described in [synthetic-data.md](synthetic-data.md). It reads current persisted records and returns immutable Java result records; calculations never write or update financial data.

`FinancialReportService` exposes:

```java
CashFlowReport dailyCashFlow(long smeId, OpeningCash opening, LocalDate endDate);
InvoiceSummary invoiceSummary(long smeId, LocalDate asOfDate);
```

Inputs/results are nested records/enums in `FinancialModels`. `FinancialReadRepository` retrieves SME-scoped rows through JDBC; `FinancialCalculator` performs pure, independently testable BigDecimal arithmetic. Each service call runs in a read-only PostgreSQL REPEATABLE READ transaction, keeping its currency, invoice, and movement reads in one consistent snapshot. Separate calls are separate snapshots.

## Opening cash: explicit dated input, not an invented transaction

`OpeningCash(date, currency, amount)` represents this SME's cash balance **immediately before all movements booked on date**, equivalently the preceding calendar day's closing balance. It is mandatory, may be zero or negative, and must match the SME's supported currency. Missing balance is an error; it never silently defaults to zero.

The caller supplies an externally reconciled balance covering the same cash scope as the recorded transactions. The prototype has no bank-account table, reconciliation, opening-balance storage, or audit evidence attachment. The opening balance is echoed in the report to make its assumption visible. Callers must retain its provenance; an arbitrary opening input does not establish actual bank cash.

No schema change is necessary: a dated calculation input satisfies this milestone without adding a partial balance-snapshot persistence design or encoding the opening amount as a cash movement. If persistent reconciled snapshots are required later, design them explicitly in a reviewed migration.

## Daily formulas and date boundaries

For every calendar date `d` from opening date `s` through end date `e`, both inclusive:

```text
inflow[d]  = sum(amount for recorded INFLOW transactions booked on d)
outflow[d] = sum(amount for recorded OUTFLOW transactions booked on d)
net[d]     = inflow[d] - outflow[d]
opening[s] = supplied opening cash
closing[d] = opening[d] + net[d]
opening[d + 1] = closing[d]
```

Amounts on transaction rows are positive magnitudes. OUTFLOW is not encoded as a negative amount; `OUTFLOW` determines subtraction. Every stored movement in scope is counted once by record ID, including invoice-linked receipts/payments. Distinct IDs with identical amounts/dates remain distinct recorded transactions; the engine is not a bank-import deduplication system.

Transactions before `s` are excluded because their effect must already be in opening cash. Transactions after `e` are excluded. Empty days have inflow/outflow/net `0.00` and carry the balance unchanged. Leap days and weekends are ordinary calendar dates; there is no business-day adjustment, timezone conversion, intraday ordering, or hidden current-date lookup.

Invoice face amounts and outstanding balances are **not** automatically added as cash movements, even when due. This prevents counting a receipt twice. Scenario inputs are not applied. Dates in the future may contain already recorded movements; if no movement is recorded, the balance carries unchanged.

The resulting projected closing balance is a conditional arithmetic roll-forward using the supplied opening balance and recorded movements only. It is not an ML forecast, a prediction of invoice collection, or proof that future records are complete. Negative closing values are retained.

## Invoice summaries and partial payments

The explicit as-of date `a` is inclusive. An invoice is included only if `issue_date <= a`. Its paid-to-date amount is the sum of linked transactions with `booked_date <= a`; payments on later dates are excluded. Advance payments booked before issue date count once the invoice itself is in scope. Unlinked transactions affect cash but do not settle an invoice. Any correctly linked same-direction transaction is treated as an allocated settlement, regardless of transaction classification; allocation correctness remains a data responsibility.

For each noncancelled invoice:

```text
paidToDate   = sum(linked payment amounts through a)
outstanding  = max(faceAmount - paidToDate, 0)
overpayment  = max(paidToDate - faceAmount, 0)
effectiveStatus = UNPAID         when paidToDate == 0
                  PARTIALLY_PAID when 0 < paidToDate < faceAmount
                  PAID           when paidToDate >= faceAmount
```

A payment that exceeds the face amount is reported as overpayment; outstanding never becomes a negative receivable/payable. The engine does not invent a refund, allocate excess to another invoice, or reject the entire read because stored status differs from linked payment evidence.

The stored `payment_status` is returned alongside effective status. `statusMismatch` highlights a difference, including a recorded PAID invoice with missing/late-linked payment evidence. No stored status is updated. This preserves the previous milestone's explicit status storage while providing a calculated view.

A recorded CANCELLED invoice stays visible in the detail list with outstanding `0.00`, effective status CANCELLED, and no overdue flag. It is excluded from the six unpaid/partial/paid summary buckets. Actual linked cash is still included in daily cash flow; cancellation does not reverse it.

The six buckets are INFLOW/receivables and OUTFLOW/payables × UNPAID/PARTIALLY_PAID/PAID. Each includes count, face amount, paid-to-date amount, outstanding amount, overdue count, and overdue outstanding. Empty buckets have zero counts/amounts. Receivables and payables are not silently netted.

## Overdue is date-dependent

```text
isOverdue = invoice is not cancelled
            AND outstanding > 0
            AND dueDate < asOfDate
```

An invoice due today is not overdue. An unpaid or partially paid invoice becomes overdue on the following calendar day; there is no implicit grace period. A fully paid invoice is not overdue as of a date when its complete payment is included, even if it was paid late. The report does not calculate historical days-late or credit default.

These are date-cutoff views of the **current ledger**, not reconstructed historical database snapshots. The schema has no cancellation/status history or payment reversals. A cancellation recorded today affects a report requested for a past date, and a later-entered backdated transaction can change a past result. Stored status mismatches are evidence for review, not automatically corrected facts. Refund/reversal allocation and historical reconciliation need a separate design before they can be interpreted as authoritative historical reporting.

## Configurable liquidity rule

Configuration is explicit in `application.properties` and `.env.example`:

| Environment variable | Default | Meaning |
| --- | --- | --- |
| `LIQUIDITY_WARNING_THRESHOLD` | `0.00` | Nonnegative closing-cash buffer in the SME's currency units |
| `FINANCIAL_MAX_REPORT_DAYS` | `3660` | Maximum inclusive calendar-day report length; a technical bound |

The threshold is one application-wide numerical buffer applied in each SME's own currency; no FX equivalence or currency-specific policy is implied. Change it via environment before Java startup. Existing `.env` is preserved; omitted settings use the documented defaults. The threshold is echoed in every cash-flow report and warning.

```text
warn when closing[d] < configuredThreshold  // strict; equality does not warn
gapToThreshold = configuredThreshold - closing[d]
```

Warnings carry date, closing balance, threshold, and gap. If closing is negative, the kind is `POTENTIAL_CASH_SHORTFALL`; otherwise it is `BELOW_CONFIGURED_BUFFER`. With a zero threshold, exactly zero does not warn. Warnings are evaluated at daily close only: opening/intraday dips can be missed if the day closes above the threshold.

A potential shortfall means the stated opening and recorded movements produce negative modeled cash. A below-buffer warning means closing cash is below a chosen review buffer. Neither proves insolvency, loan default, inability to obtain funding, or a lending outcome. No automatic loan decision, creditworthiness score, notification, or legal determination is made.

## Monetary precision and invalid inputs

All monetary arithmetic uses `BigDecimal`, never binary floating point. Inputs must be exact whole minor units, normalized to scale 2 with `RoundingMode.UNNECESSARY`. Stored movements and invoices must be positive; opening/closing/net balances may be negative. Currency support remains USD/EUR/GBP/TRY.

Every reported monetary amount must fit the existing signed magnitude limit `99999999999999999.99`, consistent with NUMERIC(19,2). Aggregation uses exact addition before range validation. If a daily inflow/outflow, closing balance, invoice bucket total, payment total, or warning gap exceeds that range, the request fails explicitly; values are not silently rounded, clamped, or returned as partial reports. The zero floor on invoice outstanding/overpayment is the stated allocation rule, not a cash-balance floor.

Null required values, invalid SME IDs, unknown SMEs, mismatched currencies, nonpositive movement/invoice amounts, fractional minor units, duplicate record IDs, inconsistent payment references/directions, reversed dates, and oversized report ranges fail with `IllegalArgumentException`. Database/currency/ownership constraints continue to protect stored records. Cancelled invoices and genuine overpayments are valid read inputs under the rules above.

## Run tests and use the engine

From repository root with PostgreSQL running:

```sh
set -a
source .env
set +a
MAVEN_USER_HOME="$PWD/.cache/maven" ./backend/mvnw -o -B -ntp -f backend/pom.xml -Dmaven.repo.local="$PWD/.cache/m2" verify
```

Remove `-o` only if dependencies are not cached on a collaborator's machine. The pure calculator tests require no database when selected independently:

```sh
MAVEN_USER_HOME="$PWD/.cache/maven" ./backend/mvnw -o -B -ntp -f backend/pom.xml -Dmaven.repo.local="$PWD/.cache/m2" -Dtest=FinancialCalculatorTests test
```

`FinancialEngineIntegrationTests` demonstrates calling the injected Java service with explicit test inputs and actual PostgreSQL records. There is no new financial HTTP API or UI in this milestone. Engine tests use small deterministic fixtures in the separate test database and remove them afterward. Synthetic application datasets are generated and loaded only through the separate explicit synthetic CLI, never by this engine or ordinary startup.

See `financial-engine-verification.md` for measured test results and limitations.
