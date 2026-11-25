export const CASH_FLOW_DISCLAIMER =
  'Conditional arithmetic roll-forward based strictly on the caller-supplied opening balance and recorded ledger movements. Does not invent receipts, mutate ledger records, or constitute a solvency proof.';

export const BANKPILOT_GOVERNANCE_STATEMENT =
  'Human-Governed Review Boundary: BankPilot surfaces audited cash-flow indicators, invoice aging, and conditional forecasts for human analyst review only. It never computes an automated credit score, default probability, risk grade, or lending approval.';

export class ApiClientError extends Error {
  constructor({ status, error, message, path, cause }) {
    super(message || `Request failed (${status})`);
    this.name = 'ApiClientError';
    this.status = status;
    this.error = error || 'REQUEST_FAILED';
    this.path = path || '';
    if (cause) this.cause = cause;
  }
}

export function getFriendlyErrorMessage(err) {
  if (!err) return 'An unexpected error occurred.';
  if (err instanceof ApiClientError) {
    if (err.status === 503) {
      if (err.error === 'FORECAST_SERVICE_UNAVAILABLE') {
        return `Forecasting service unavailable (503): ${err.message}. Ensure the Python forecasting service is running on port 8000.`;
      }
      return `Service unavailable (503): ${err.message}. Verify backend and PostgreSQL are running.`;
    }
    if (err.status === 422) {
      return `Forecast validation rejected (422): ${err.message}`;
    }
    if (err.status === 404) {
      return `Resource not found (404): ${err.message}`;
    }
    if (err.status === 400) {
      return `Invalid request parameters (400): ${err.message}`;
    }
    return `${err.error} (${err.status}): ${err.message}`;
  }
  return err.message || 'Network request failed. Check that the backend and Vite proxy are running.';
}

async function requestJson(path, { method = 'GET', body, signal } = {}) {
  const headers = { Accept: 'application/json' };
  const init = { method, headers, signal };
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(body);
  }

  let response;
  try {
    response = await fetch(path, init);
  } catch (networkErr) {
    if (networkErr?.name === 'AbortError') {
      throw networkErr;
    }
    throw new ApiClientError({
      status: 0,
      error: 'NETWORK_ERROR',
      message: 'Unable to reach /api proxy. Check that backend and Vite dev server are running.',
      path,
      cause: networkErr,
    });
  }

  if (response.status === 204) {
    return null;
  }

  let payload = null;
  const text = await response.text();
  if (text) {
    try {
      payload = JSON.parse(text);
    } catch {
      payload = null;
    }
  }

  if (!response.ok) {
    throw new ApiClientError({
      status: payload?.status ?? response.status,
      error: payload?.error ?? `HTTP_${response.status}`,
      message: payload?.message ?? response.statusText ?? 'Request failed',
      path: payload?.path ?? path,
    });
  }

  return payload;
}

export function getBackendHealth(signal) {
  return requestJson('/api/health', { signal });
}

export function getDatabaseHealth(signal) {
  return requestJson('/api/health/database', { signal });
}

export function listSmes(signal) {
  return requestJson('/api/smes', { signal });
}

export function getCashFlow(smeId, { openingDate, openingAmount, currency, endDate }, signal) {
  const qs = new URLSearchParams({ openingDate, openingAmount, currency, endDate });
  return requestJson(`/api/smes/${encodeURIComponent(smeId)}/cash-flow?${qs.toString()}`, { signal });
}

export function getInvoiceSummary(smeId, { asOfDate }, signal) {
  const qs = new URLSearchParams({ asOfDate });
  return requestJson(`/api/smes/${encodeURIComponent(smeId)}/invoices/summary?${qs.toString()}`, { signal });
}

export function getTransactions(smeId, { from, to } = {}, signal) {
  const qs = new URLSearchParams();
  if (from) qs.set('from', from);
  if (to) qs.set('to', to);
  const suffix = qs.toString() ? `?${qs.toString()}` : '';
  return requestJson(`/api/smes/${encodeURIComponent(smeId)}/transactions${suffix}`, { signal });
}

export function listScenarios(smeId, signal) {
  return requestJson(`/api/smes/${encodeURIComponent(smeId)}/scenarios`, { signal });
}

export function createScenario(smeId, { name, kind, inputs }, signal) {
  return requestJson(`/api/smes/${encodeURIComponent(smeId)}/scenarios`, {
    method: 'POST',
    body: { name, kind, inputs },
    signal,
  });
}

export function deleteScenario(smeId, scenarioId, signal) {
  return requestJson(`/api/smes/${encodeURIComponent(smeId)}/scenarios/${encodeURIComponent(scenarioId)}`, {
    method: 'DELETE',
    signal,
  });
}

export function simulateScenario(smeId, scenarioId, { openingDate, openingAmount, currency, endDate }, signal) {
  return requestJson(`/api/smes/${encodeURIComponent(smeId)}/scenarios/${encodeURIComponent(scenarioId)}/simulate`, {
    method: 'POST',
    body: { openingDate, openingAmount, currency, endDate },
    signal,
  });
}

export function getForecast(
  smeId,
  { openingDate, openingAmount, currency, endDate, horizons = '7,30', model = 'drift_calendar' },
  signal,
) {
  const qs = new URLSearchParams({ openingDate, openingAmount, currency, endDate });
  if (horizons) qs.set('horizons', horizons);
  if (model) qs.set('model', model);
  return requestJson(`/api/smes/${encodeURIComponent(smeId)}/forecast?${qs.toString()}`, { signal });
}
