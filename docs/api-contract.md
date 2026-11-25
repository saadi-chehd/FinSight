# FinSight API Contract

## Health & Liveness

### Java

`GET http://127.0.0.1:8080/api/health`

HTTP 200, JSON:

```json
{"status":"UP","service":"backend"}
```

### Python

`GET http://127.0.0.1:8000/health`

HTTP 200, JSON:

```json
{"status":"UP","service":"forecasting"}
```

Interactive docs: `/docs`; OpenAPI schema: `/openapi.json` on the Python server.

Both endpoints take no parameters and are unauthenticated process-liveness checks. JSON field order is irrelevant. Neither checks PostgreSQL or a model. During development `http://127.0.0.1:5173/api/health` (and `/api/*`) proxies to Java.

## Database readiness

`GET http://127.0.0.1:8080/api/health/database`

Runs a live `SELECT 1` through the configured JDBC datasource. HTTP 200 returns:

```json
{"status":"UP","service":"database"}
```

A database-access failure returns HTTP 503 with `status: DOWN` and `service: database`, without exposing credentials or SQL errors. This does not replace process liveness or validate financial data.

---

## Cross-Cutting Conventions

### Exact 2-Decimal Monetary Rules
- Supported reporting currencies: `USD`, `EUR`, `GBP`, `TRY`.
- All monetary values in requests and responses are formatted as exact 2-decimal strings (e.g. `"5000.00"`, `"-4.70"`), backed by `BigDecimal` / `NUMERIC(19,2)` with signed magnitude bound `99999999999999999.99`.
- Binary floating-point rounding and fractional minor units (e.g. `"100.123"`) are rejected with HTTP `400` on Java endpoints (and HTTP `422` on Python `POST /forecast`).

### Error Format (`ApiError`)
All Java error responses use the standard `ApiError` JSON envelope without exposing SQL queries, credentials, or stack traces:

```json
{
  "status": 400,
  "error": "BAD_REQUEST",
  "message": "Invalid monetary amount",
  "path": "/api/smes/1/cash-flow"
}
```

Standard error codes:
- `400` (`BAD_REQUEST`): Missing/malformed parameters, invalid dates or ranges, fractional minor units, unsupported/mismatched currency, invalid scenario or forecast parameters.
- `404` (`NOT_FOUND`): Unknown `smeId` or `scenarioId` not owned by `smeId`.
- `422` (`FORECAST_VALIDATION_ERROR`): Downstream Python forecasting service rejected the input (e.g. insufficient history for requested model and max horizon).
- `503` (`SERVICE_UNAVAILABLE` / `FORECAST_SERVICE_UNAVAILABLE`): Database or Python forecasting service unavailable or timed out.
- `500` (`INTERNAL_SERVER_ERROR`): Unexpected server error (sanitized generic message).

### Conditional-Analysis Disclaimers
- **Cash-flow reports & scenario simulations** are deterministic, conditional arithmetic roll-forwards based strictly on the caller-supplied opening balance, recorded ledger movements, and user-entered scenario assumptions. They do not invent cash receipts, mutate stored ledger records, or constitute solvency proofs.
- **Forecasting outputs** are conditional mathematical estimates derived solely from historical daily closing cash balances. Outputs are **not** solvency proofs, liquidity guarantees, financial advice, or credit/lending decisions.

---

## Financial Read APIs (Java)

### List SMEs
`GET /api/smes`

Returns all SMEs ordered by `id`:

```json
[
  {
    "id": 1,
    "name": "Acme Bakery LLC",
    "currency": "USD",
    "createdAt": "2026-10-18T12:00:00Z"
  }
]
```

### Daily Cash Flow
`GET /api/smes/{smeId}/cash-flow`

Required query parameters:
- `openingDate` (`YYYY-MM-DD`)
- `openingAmount` (exact 2-decimal monetary string; zero or negative allowed)
- `currency` (`USD | EUR | GBP | TRY`; must match SME currency)
- `endDate` (`YYYY-MM-DD`; inclusive, `>= openingDate`, within `financial.max-report-days`)

HTTP 200 response:
```json
{
  "smeId": 1,
  "opening": {"date": "2024-01-01", "currency": "USD", "amount": "5000.00"},
  "endDate": "2024-01-02",
  "warningThreshold": "0.00",
  "days": [
    {
      "date": "2024-01-01",
      "openingBalance": "5000.00",
      "inflow": "200.00",
      "outflow": "50.00",
      "netMovement": "150.00",
      "closingBalance": "5150.00"
    }
  ],
  "warnings": [
    {
      "date": "2024-01-02",
      "kind": "POTENTIAL_CASH_SHORTFALL",
      "closingBalance": "-4.70",
      "threshold": "0.00",
      "gapToThreshold": "4.70"
    }
  ]
}
```

### Invoice Summary
`GET /api/smes/{smeId}/invoices/summary`

Required query parameter:
- `asOfDate` (`YYYY-MM-DD`)

HTTP 200 response:
```json
{
  "smeId": 1,
  "currency": "USD",
  "asOfDate": "2024-01-15",
  "buckets": [
    {
      "direction": "INFLOW",
      "status": "PARTIALLY_PAID",
      "count": 1,
      "faceAmount": "100.00",
      "paidToDate": "30.00",
      "outstanding": "70.00",
      "overdueCount": 1,
      "overdueOutstanding": "70.00"
    }
  ],
  "invoices": [
    {
      "id": 10,
      "reference": "INV-001",
      "direction": "INFLOW",
      "dueDate": "2024-01-10",
      "recordedStatus": "UNPAID",
      "effectiveStatus": "PARTIALLY_PAID",
      "statusMismatch": true,
      "faceAmount": "100.00",
      "paidToDate": "30.00",
      "outstanding": "70.00",
      "overpayment": "0.00",
      "overdue": true
    }
  ]
}
```

### Transactions Ledger
`GET /api/smes/{smeId}/transactions`

Optional query parameters:
- `from` (`YYYY-MM-DD`, inclusive)
- `to` (`YYYY-MM-DD`, inclusive; when both provided, `to >= from`)

HTTP 200 response:
```json
{
  "smeId": 1,
  "currency": "USD",
  "from": "2024-01-01",
  "to": "2024-01-31",
  "transactions": [
    {
      "id": 42,
      "invoiceId": 10,
      "bookedDate": "2024-01-05",
      "currency": "USD",
      "direction": "INFLOW",
      "transactionType": "CUSTOMER_RECEIPT",
      "amount": "30.00",
      "description": "Partial settlement"
    }
  ]
}
```

---

## Scenario Management & Simulation APIs (Java)

### List Scenarios
`GET /api/smes/{smeId}/scenarios`

Returns scenarios for `smeId` ordered by `id`, each containing its `inputs` ordered by `id`:

```json
[
  {
    "id": 5,
    "smeId": 1,
    "name": "Delayed key receivable",
    "kind": "LATE_PAYMENT",
    "createdAt": "2026-10-18T12:10:00Z",
    "inputs": [
      {
        "id": 12,
        "invoiceId": 10,
        "delayDays": 14,
        "amount": null,
        "expectedDate": null
      }
    ]
  }
]
```

### Create Scenario
`POST /api/smes/{smeId}/scenarios`

Request body:
- `name`: non-blank string
- `kind`: `BASELINE | LATE_PAYMENT | NEW_CONTRACT`
- `inputs`:
  - `BASELINE`: empty list (or omitted)
  - `LATE_PAYMENT`: nonempty list of `{ "invoiceId": <positive id owned by smeId, unique within scenario>, "delayDays": <1..3650>, "amount": null, "expectedDate": null }`
  - `NEW_CONTRACT`: nonempty list of `{ "invoiceId": null, "delayDays": null, "amount": "<positive 2-decimal string>", "expectedDate": "YYYY-MM-DD" }`

Returns HTTP `201 Created` with the created scenario object.

### Delete Scenario
`DELETE /api/smes/{smeId}/scenarios/{scenarioId}`

Deletes `scenario_inputs` and the `scenarios` row owned by `smeId`. Returns HTTP `204 No Content` (or `404` if SME or scenario is not found).

### Simulate Scenario
`POST /api/smes/{smeId}/scenarios/{scenarioId}/simulate`

Runs inside a read-only `REPEATABLE_READ` transaction and **never** mutates stored transactions or invoice statuses.

Request body:
```json
{
  "openingDate": "2024-01-01",
  "openingAmount": "5000.00",
  "currency": "USD",
  "endDate": "2024-01-31"
}
```

HTTP 200 response:
```json
{
  "smeId": 1,
  "scenario": { "...": "scenario metadata and inputs" },
  "opening": {"date": "2024-01-01", "currency": "USD", "amount": "5000.00"},
  "endDate": "2024-01-31",
  "warningThreshold": "0.00",
  "baselineMinClosingBalance": {"date": "2024-01-15", "amount": "1200.00"},
  "scenarioMinClosingBalance": {"date": "2024-01-20", "amount": "850.00"},
  "minClosingBalanceDelta": "-350.00",
  "days": [
    {
      "date": "2024-01-01",
      "baselineInflow": "200.00",
      "baselineOutflow": "50.00",
      "baselineClosingBalance": "5150.00",
      "scenarioInflow": "200.00",
      "scenarioOutflow": "50.00",
      "scenarioClosingBalance": "5150.00",
      "closingBalanceDelta": "0.00"
    }
  ],
  "baselineWarnings": [],
  "scenarioWarnings": [],
  "notes": [
    "Shifted payment(s) for invoice 10 by 14 day(s) within simulation window."
  ],
  "disclaimer": "Conditional arithmetic comparison based on explicit user assumptions and recorded ledger entries; not a guarantee, solvency proof, or credit/lending decision."
}
```

---

## Cash-Flow Forecasting APIs

### Java Forecast Bridge
`GET /api/smes/{smeId}/forecast`

Computes daily historical closing balances for `smeId` from `openingDate` through `endDate` via `FinancialReportService.dailyCashFlow`, forwards the continuous daily history to Python `POST /forecast`, and enriches the response with SME and opening-cash context.

Required query parameters:
- `openingDate` (`YYYY-MM-DD`)
- `openingAmount` (exact 2-decimal monetary string)
- `currency` (`USD | EUR | GBP | TRY`; must match SME currency)
- `endDate` (`YYYY-MM-DD`)

Optional query parameters:
- `horizons`: comma-separated positive unique integers in `1..365` (default `"7,30"`)
- `model`: `drift_calendar | ridge_lag_calendar | naive` (default `"drift_calendar"`)

HTTP 200 response (`SmeForecastResponse`):
```json
{
  "smeId": 1,
  "openingDate": "2024-01-01",
  "openingAmount": "5000.00",
  "endDate": "2024-12-31",
  "currency": "USD",
  "originDate": "2024-12-31",
  "historyStartDate": "2024-01-01",
  "historyDays": 366,
  "model": {
    "name": "drift_calendar",
    "version": "CM1",
    "description": "...",
    "intervalMethod": "split_conformal_past_rolling_residuals",
    "minHistoryDays": 109,
    "residualWindowMax": 365
  },
  "forecasts": [
    {
      "horizonDays": 7,
      "targetDate": "2025-01-07",
      "predictedClosingBalance": "5420.15",
      "interval80": {"lower": "4890.00", "upper": "5950.30"},
      "interval95": {"lower": "4410.50", "upper": "6429.80"},
      "residualCount": 300
    },
    {
      "horizonDays": 30,
      "targetDate": "2025-01-30",
      "predictedClosingBalance": "6110.80",
      "interval80": {"lower": "4980.10", "upper": "7241.50"},
      "interval95": {"lower": "4120.00", "upper": "8101.60"},
      "residualCount": 277
    }
  ],
  "disclaimer": "Conditional mathematical estimates derived solely from historical daily closing cash balances. Outputs are not solvency proofs, liquidity guarantees, financial advice, or credit/lending decisions."
}
```

Error mappings:
- `400 BAD_REQUEST`: Invalid/missing query params, fractional `openingAmount`, currency mismatch, reversed dates, invalid `horizons`, or unsupported `model`.
- `404 NOT_FOUND`: Unknown `smeId`.
- `422 FORECAST_VALIDATION_ERROR`: Python service rejected the request (e.g. `historyDays < minHistoryDays` for the chosen model and max horizon).
- `503 FORECAST_SERVICE_UNAVAILABLE`: Python service unreachable, timed out, or returned `5xx`.

### Python Forecasting Service
`POST http://127.0.0.1:8000/forecast`

Direct wire protocol consumed by the Java backend (`ForecastClient`):

Request JSON:
```json
{
  "currency": "USD",
  "history": [
    {"date": "2024-01-01", "closing_balance": "5000.00"},
    {"date": "2024-01-02", "closing_balance": "5125.50"}
  ],
  "horizons": [7, 30],
  "model": "drift_calendar"
}
```

Minimum required consecutive daily history length:
- `min_fit_days[model] + max(horizons) + 19`
  - `drift_calendar` / `ridge_lag_calendar`: `60 + max(horizons) + 19` (e.g. `109` days at `max(horizons) = 30`)
  - `naive`: `1 + max(horizons) + 19` (e.g. `50` days at `max(horizons) = 30`)

HTTP 200 response JSON:
```json
{
  "currency": "USD",
  "origin_date": "2024-12-31",
  "history_start_date": "2024-01-01",
  "history_days": 366,
  "model": {
    "name": "drift_calendar",
    "version": "CM1",
    "description": "...",
    "interval_method": "split_conformal_past_rolling_residuals",
    "min_history_days": 109,
    "residual_window_max": 365
  },
  "forecasts": [
    {
      "horizon_days": 7,
      "target_date": "2025-01-07",
      "predicted_closing_balance": "5420.15",
      "interval_80": {"lower": "4890.00", "upper": "5950.30"},
      "interval_95": {"lower": "4410.50", "upper": "6429.80"},
      "residual_count": 300
    }
  ],
  "disclaimer": "Conditional mathematical estimates derived solely from historical daily closing cash balances. Outputs are not solvency proofs, liquidity guarantees, financial advice, or credit/lending decisions."
}
```

HTTP 422 is returned on missing calendar days, duplicate or non-ascending dates, fractional minor units, invalid currencies, duplicate or out-of-range horizons, unsupported model names, or insufficient history.
