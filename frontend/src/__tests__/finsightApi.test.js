import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  ApiClientError,
  createScenario,
  getCashFlow,
  getForecast,
  getFriendlyErrorMessage,
  getInvoiceSummary,
  simulateScenario,
} from '../api/finsightApi';

function mockJsonResponse(body, { status = 200, statusText = 'OK' } = {}) {
  return {
    ok: status >= 200 && status < 300,
    status,
    statusText,
    text: async () => (body === null ? '' : JSON.stringify(body)),
  };
}

describe('finsightApi endpoints and error handling', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('calls getCashFlow with exact relative /api path and query params', async () => {
    const fetchMock = vi.fn().mockResolvedValue(mockJsonResponse({ smeId: 1, days: [], warnings: [] }));
    vi.stubGlobal('fetch', fetchMock);

    await getCashFlow(1, {
      openingDate: '2024-01-01',
      openingAmount: '5000.00',
      currency: 'USD',
      endDate: '2024-01-31',
    });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe(
      '/api/smes/1/cash-flow?openingDate=2024-01-01&openingAmount=5000.00&currency=USD&endDate=2024-01-31',
    );
    expect(init.method).toBe('GET');
    expect(init.headers.Accept).toBe('application/json');
  });

  it('calls getInvoiceSummary with exact relative /api path and asOfDate query param', async () => {
    const fetchMock = vi.fn().mockResolvedValue(mockJsonResponse({ smeId: 2, buckets: [], invoices: [] }));
    vi.stubGlobal('fetch', fetchMock);

    await getInvoiceSummary(2, { asOfDate: '2024-01-15' });

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/smes/2/invoices/summary?asOfDate=2024-01-15');
    expect(init.method).toBe('GET');
  });

  it('calls createScenario with POST and JSON body', async () => {
    const fetchMock = vi.fn().mockResolvedValue(mockJsonResponse({ id: 5, name: 'Delay' }, { status: 201 }));
    vi.stubGlobal('fetch', fetchMock);

    const payload = {
      name: 'Delayed key receivable',
      kind: 'LATE_PAYMENT',
      inputs: [{ invoiceId: 10, delayDays: 14, amount: null, expectedDate: null }],
    };

    await createScenario(1, payload);

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/smes/1/scenarios');
    expect(init.method).toBe('POST');
    expect(init.headers['Content-Type']).toBe('application/json');
    expect(JSON.parse(init.body)).toEqual(payload);
  });

  it('calls simulateScenario with POST and opening cash JSON body', async () => {
    const fetchMock = vi.fn().mockResolvedValue(mockJsonResponse({ smeId: 1, days: [] }));
    vi.stubGlobal('fetch', fetchMock);

    const opening = {
      openingDate: '2024-01-01',
      openingAmount: '5000.00',
      currency: 'USD',
      endDate: '2024-01-31',
    };

    await simulateScenario(1, 5, opening);

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/smes/1/scenarios/5/simulate');
    expect(init.method).toBe('POST');
    expect(init.headers['Content-Type']).toBe('application/json');
    expect(JSON.parse(init.body)).toEqual(opening);
  });

  it('calls getForecast with exact relative /api path and horizons/model query params', async () => {
    const fetchMock = vi.fn().mockResolvedValue(mockJsonResponse({ smeId: 1, forecasts: [] }));
    vi.stubGlobal('fetch', fetchMock);

    await getForecast(1, {
      openingDate: '2024-01-01',
      openingAmount: '5000.00',
      currency: 'USD',
      endDate: '2024-12-31',
      horizons: '7,30',
      model: 'drift_calendar',
    });

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe(
      '/api/smes/1/forecast?openingDate=2024-01-01&openingAmount=5000.00&currency=USD&endDate=2024-12-31&horizons=7%2C30&model=drift_calendar',
    );
    expect(init.method).toBe('GET');
  });

  it.each([
    {
      status: 400,
      error: 'BAD_REQUEST',
      message: 'Invalid monetary amount',
      path: '/api/smes/1/cash-flow',
      friendlyMatch: /Invalid request parameters \(400\): Invalid monetary amount/,
    },
    {
      status: 404,
      error: 'NOT_FOUND',
      message: 'SME 99 not found',
      path: '/api/smes/99/cash-flow',
      friendlyMatch: /Resource not found \(404\): SME 99 not found/,
    },
    {
      status: 422,
      error: 'FORECAST_VALIDATION_ERROR',
      message: 'Insufficient history for requested horizon',
      path: '/api/smes/1/forecast',
      friendlyMatch: /Forecast validation rejected \(422\): Insufficient history/,
    },
    {
      status: 503,
      error: 'FORECAST_SERVICE_UNAVAILABLE',
      message: 'Connection refused',
      path: '/api/smes/1/forecast',
      friendlyMatch: /Forecasting service unavailable \(503\): Connection refused.*8000/,
    },
  ])(
    'parses HTTP $status ApiError envelope into ApiClientError and friendly message',
    async ({ status, error, message, path, friendlyMatch }) => {
      const fetchMock = vi.fn().mockResolvedValue(
        mockJsonResponse({ status, error, message, path }, { status }),
      );
      vi.stubGlobal('fetch', fetchMock);

      let caught = null;
      try {
        await getCashFlow(1, {
          openingDate: '2024-01-01',
          openingAmount: '5000.00',
          currency: 'USD',
          endDate: '2024-01-31',
        });
      } catch (err) {
        caught = err;
      }

      expect(caught).toBeInstanceOf(ApiClientError);
      expect(caught.status).toBe(status);
      expect(caught.error).toBe(error);
      expect(caught.message).toBe(message);
      expect(caught.path).toBe(path);
      expect(getFriendlyErrorMessage(caught)).toMatch(friendlyMatch);
    },
  );
});
