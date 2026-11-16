# Foundation API contract

## Java

`GET http://127.0.0.1:8080/api/health`

HTTP 200, JSON:

```json
{"status":"UP","service":"backend"}
```

## Python

`GET http://127.0.0.1:8000/health`

HTTP 200, JSON:

```json
{"status":"UP","service":"forecasting"}
```

Interactive docs: `/docs`; OpenAPI schema: `/openapi.json` on the Python server.

Both endpoints take no parameters and are unauthenticated process-liveness checks. JSON field order is irrelevant. Neither checks PostgreSQL or a model. Frontend failures display an unavailable message; no business response schema exists yet.

During development `http://127.0.0.1:5173/api/health` proxies to Java. Python is checked independently; Java–Python integration is deferred. No financial API is defined or implemented.

## Database readiness

`GET http://127.0.0.1:8080/api/health/database`

Runs a live `SELECT 1` through the configured JDBC datasource. HTTP 200 returns:

```json
{"status":"UP","service":"database"}
```

A database-access failure returns HTTP 503 with `status: DOWN` and `service: database`, without exposing credentials or SQL errors. This does not replace process liveness or validate financial data. No financial CRUD API has been added.
