export const FORECAST_MODELS = [
  {
    id: 'ensemble_ml',
    label: 'Weighted Ensemble ML (CM2 — Invoice-Aware)',
    shortLabel: 'Ensemble ML (CM2)',
    version: 'CM2',
    invoiceAware: true,
    ml: true,
  },
  {
    id: 'invoice_aware_ml',
    label: 'Invoice-Aware GBDT + Ridge (CM2)',
    shortLabel: 'Invoice-Aware ML (CM2)',
    version: 'CM2',
    invoiceAware: true,
    ml: true,
  },
  {
    id: 'gbdt_direct',
    label: 'Gradient Boosting Direct (CM2)',
    shortLabel: 'GBDT Direct (CM2)',
    version: 'CM2',
    invoiceAware: false,
    ml: true,
  },
  {
    id: 'drift_calendar',
    label: 'Drift + Calendar (CM1)',
    shortLabel: 'Drift + Calendar (CM1)',
    version: 'CM1',
    invoiceAware: false,
    ml: false,
  },
  {
    id: 'ridge_lag_calendar',
    label: 'Ridge Lag + Calendar (CM1)',
    shortLabel: 'Ridge + Calendar (CM1)',
    version: 'CM1',
    invoiceAware: false,
    ml: false,
  },
  {
    id: 'naive',
    label: 'Naïve Persistence',
    shortLabel: 'Naïve',
    version: 'CM1',
    invoiceAware: false,
    ml: false,
  },
];

/**
 * Normalizes per-horizon ensemble weights into sorted rows with formatted percentages.
 */
export function getEnsembleWeightRows(ensembleWeights) {
  if (!ensembleWeights || typeof ensembleWeights !== 'object') {
    return [];
  }
  return Object.entries(ensembleWeights)
    .sort(([hA], [hB]) => Number(hA) - Number(hB))
    .map(([horizon, weights]) => {
      const members = Object.entries(weights || {})
        .filter(([, value]) => typeof value === 'number' && Number.isFinite(value))
        .map(([member, value]) => ({
          member,
          weight: value,
          percentText: `${(value * 100).toFixed(1)}%`,
        }));
      return { horizon: String(horizon), members };
    })
    .filter((row) => row.members.length > 0);
}

/**
 * Formats per-horizon ensemble weights into a single compact text summary.
 */
export function formatEnsembleWeightsSummary(ensembleWeights) {
  const rows = getEnsembleWeightRows(ensembleWeights);
  if (rows.length === 0) return null;
  return rows
    .map(
      (row) =>
        `+${row.horizon}d: ${row.members.map((m) => `${m.member} ${m.percentText}`).join(', ')}`,
    )
    .join(' | ');
}

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
