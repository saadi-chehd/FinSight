import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { CashFlowForecastView } from '../components/CashFlowForecastView';
import { BankPilotView, buildMarkdownBrief } from '../components/BankPilotView';
import { FORECAST_MODELS } from '../api/finsightApi';

const SAMPLE_SME = {
  id: 1,
  name: 'SYNTHETIC FS1-20261018-A Alpha Services',
  currency: 'USD',
  createdAt: '2026-10-18T12:00:00Z',
};

const SAMPLE_PARAMS = {
  openingDate: '2024-01-01',
  openingAmount: '5000.00',
  currency: 'USD',
  endDate: '2024-12-31',
};

function makeCashFlowPayload() {
  return {
    smeId: 1,
    opening: { date: '2024-01-01', currency: 'USD', amount: '5000.00' },
    endDate: '2024-12-31',
    warningThreshold: '0.00',
    days: [
      {
        date: '2024-12-30',
        openingBalance: '5000.00',
        inflow: '200.00',
        outflow: '50.00',
        netMovement: '150.00',
        closingBalance: '5150.00',
      },
      {
        date: '2024-12-31',
        openingBalance: '5150.00',
        inflow: '100.00',
        outflow: '0.00',
        netMovement: '100.00',
        closingBalance: '5250.00',
      },
    ],
    warnings: [],
  };
}

function makeInvoiceSummaryPayload() {
  return {
    smeId: 1,
    currency: 'USD',
    asOfDate: '2024-12-31',
    buckets: [],
    invoices: [],
  };
}

function makeForecastPayload(modelName) {
  const isEnsemble = modelName === 'ensemble_ml';
  const isInvoiceAware = modelName === 'ensemble_ml' || modelName === 'invoice_aware_ml';
  const isCm2 = isInvoiceAware || modelName === 'gbdt_direct';
  return {
    smeId: 1,
    openingDate: '2024-01-01',
    openingAmount: '5000.00',
    endDate: '2024-12-31',
    currency: 'USD',
    originDate: '2024-12-31',
    historyStartDate: '2024-01-01',
    historyDays: 366,
    model: {
      name: modelName,
      version: isCm2 ? 'CM2' : 'CM1',
      description: `Model ${modelName}`,
      intervalMethod: 'split_conformal_past_rolling_residuals',
      minHistoryDays: isCm2 ? 139 : 109,
      residualWindowMax: isCm2 ? 180 : 365,
      usesOpenInvoices: isInvoiceAware,
      openInvoiceCount: isInvoiceAware ? 4 : null,
      activeMembers: isEnsemble ? ['drift_calendar', 'gbdt_direct', 'invoice_aware_ml'] : null,
      ensembleWeights: isEnsemble
        ? {
            7: { drift_calendar: 0.3, gbdt_direct: 0.2, invoice_aware_ml: 0.5 },
            30: { drift_calendar: 0.25, gbdt_direct: 0.15, invoice_aware_ml: 0.6 },
          }
        : null,
    },
    forecasts: [
      {
        horizonDays: 7,
        targetDate: '2025-01-07',
        predictedClosingBalance: '5420.15',
        interval80: { lower: '4890.00', upper: '5950.30' },
        interval95: { lower: '4410.50', upper: '6429.80' },
        residualCount: 180,
      },
      {
        horizonDays: 30,
        targetDate: '2025-01-30',
        predictedClosingBalance: '6110.80',
        interval80: { lower: '4980.10', upper: '7241.50' },
        interval95: { lower: '4120.00', upper: '8101.60' },
        residualCount: 180,
      },
    ],
    disclaimer:
      'Conditional mathematical estimates; outputs are not solvency proofs, liquidity guarantees, financial advice, or credit/lending decisions.',
  };
}

function createCm2FetchMock() {
  return vi.fn(async (url) => {
    const path = String(url);
    if (path.includes('/cash-flow')) {
      return {
        ok: true,
        status: 200,
        text: async () => JSON.stringify(makeCashFlowPayload()),
      };
    }
    if (path.includes('/invoices/summary')) {
      return {
        ok: true,
        status: 200,
        text: async () => JSON.stringify(makeInvoiceSummaryPayload()),
      };
    }
    if (path.includes('/forecast')) {
      const query = path.split('?')[1] || '';
      const qs = new URLSearchParams(query);
      const model = qs.get('model') || 'drift_calendar';
      return {
        ok: true,
        status: 200,
        text: async () => JSON.stringify(makeForecastPayload(model)),
      };
    }
    return {
      ok: true,
      status: 200,
      text: async () => JSON.stringify({}),
    };
  });
}

describe('CM2 Forecast Model Selection and Provenance UI', () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('defaults CashFlowForecastView to ensemble_ml, displays Invoice-Aware ML badges and ensemble weight diagnostics, and switches models', async () => {
    const fetchMock = createCm2FetchMock();
    vi.stubGlobal('fetch', fetchMock);

    render(<CashFlowForecastView sme={SAMPLE_SME} params={SAMPLE_PARAMS} />);

    const modelSelect = screen.getByLabelText(/Forecasting Model/i);
    expect(modelSelect.value).toBe('ensemble_ml');
    expect(within(modelSelect).getAllByRole('option')).toHaveLength(FORECAST_MODELS.length);

    expect(await screen.findByText('Version: CM2')).toBeInTheDocument();
    expect(screen.getByText('Model: ensemble_ml')).toBeInTheDocument();
    expect(screen.getByText('Invoice-Aware ML')).toBeInTheDocument();
    expect(screen.getByText('Open Invoices: 4')).toBeInTheDocument();
    expect(
      screen.getByText('Active Members: drift_calendar, gbdt_direct, invoice_aware_ml'),
    ).toBeInTheDocument();

    const weightsBox = screen.getByLabelText('Ensemble Horizon Weights');
    expect(within(weightsBox).getByText('+7d:')).toBeInTheDocument();
    expect(within(weightsBox).getByText('invoice_aware_ml: 50.0%')).toBeInTheDocument();
    expect(within(weightsBox).getByText('+30d:')).toBeInTheDocument();
    expect(within(weightsBox).getByText('invoice_aware_ml: 60.0%')).toBeInTheDocument();

    expect(
      fetchMock.mock.calls.some(
        ([url]) => String(url).includes('/forecast') && String(url).includes('model=ensemble_ml'),
      ),
    ).toBe(true);

    // Switch to cash-only gbdt_direct
    fireEvent.change(modelSelect, { target: { value: 'gbdt_direct' } });

    expect(await screen.findByText('Model: gbdt_direct')).toBeInTheDocument();
    expect(screen.queryByText('Invoice-Aware ML')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Ensemble Horizon Weights')).not.toBeInTheDocument();
    expect(
      fetchMock.mock.calls.some(
        ([url]) => String(url).includes('/forecast') && String(url).includes('model=gbdt_direct'),
      ),
    ).toBe(true);
  });

  it('adds portfolio model selector to BankPilotView, renders Invoice-Aware ML metadata in table and dossier, and exports CM2 metadata in Markdown brief', async () => {
    const fetchMock = createCm2FetchMock();
    vi.stubGlobal('fetch', fetchMock);

    render(<BankPilotView smes={[SAMPLE_SME]} smesLoading={false} smesError={null} />);

    const portfolioSelect = screen.getByLabelText(/Portfolio Forecast Model/i);
    expect(portfolioSelect.value).toBe('ensemble_ml');
    expect(within(portfolioSelect).getAllByRole('option')).toHaveLength(FORECAST_MODELS.length);

    // Populate FS1 preset and switch model to invoice_aware_ml before analyzing
    fireEvent.click(
      screen.getByRole('button', { name: /Populate FS1 Synthetic Presets \(Demo Only\)/i }),
    );
    fireEvent.change(portfolioSelect, { target: { value: 'invoice_aware_ml' } });

    const table = screen.getByRole('table');
    fireEvent.click(within(table).getByRole('button', { name: /^Analyze$/i }));

    expect(await within(table).findByText('Invoice-Aware ML')).toBeInTheDocument();
    expect(within(table).getByText(/invoice_aware_ml \(CM2\)/)).toBeInTheDocument();
    expect(within(table).getByText(/4 open inv\./)).toBeInTheDocument();

    await waitFor(() => {
      expect(
        fetchMock.mock.calls.some(
          ([url]) =>
            String(url).includes('/forecast') && String(url).includes('model=invoice_aware_ml'),
        ),
      ).toBe(true);
    });

    // Switch to ensemble_ml and re-analyze to verify ensemble dossier provenance
    fireEvent.change(portfolioSelect, { target: { value: 'ensemble_ml' } });
    fireEvent.click(within(table).getByRole('button', { name: /^Analyze$/i }));

    expect(await within(table).findByText(/ensemble_ml \(CM2\)/)).toBeInTheDocument();

    fireEvent.click(within(table).getByRole('button', { name: /Open Dossier/i }));
    const dossier = screen.getByRole('region', { name: /Human-Governed Review Dossier/i });
    expect(within(dossier).getByText('ensemble_ml (CM2)')).toBeInTheDocument();
    expect(within(dossier).getByText('Invoice-Aware ML')).toBeInTheDocument();
    expect(within(dossier).getByText('drift_calendar, gbdt_direct, invoice_aware_ml')).toBeInTheDocument();
    expect(
      within(dossier).getByText(
        /\+7d: drift_calendar 30\.0%, gbdt_direct 20\.0%, invoice_aware_ml 50\.0%/,
      ),
    ).toBeInTheDocument();

    // Verify Markdown brief export includes CM2 provenance fields
    const forecastPayload = makeForecastPayload('ensemble_ml');
    const markdown = buildMarkdownBrief({
      sme: SAMPLE_SME,
      draft: SAMPLE_PARAMS,
      analysis: {
        params: SAMPLE_PARAMS,
        cashFlow: makeCashFlowPayload(),
        invoiceSummary: makeInvoiceSummaryPayload(),
        forecast: forecastPayload,
        forecastError: null,
      },
      indicators: {
        endingCash: '5250.00',
        minCash: { amount: '5150.00', date: '2024-12-30' },
        warningsCount: 0,
        overdueReceivables: { amount: '0.00', count: 0 },
        overduePayables: { amount: '0.00', count: 0 },
        statusMismatchCount: 0,
        forecast7d: forecastPayload.forecasts[0],
        forecast30d: forecastPayload.forecasts[1],
      },
      checklist: {},
      notes: '',
      selectedModel: 'ensemble_ml',
    });

    expect(markdown).toContain('| Forecast Model & Version | ensemble_ml (CM2) |');
    expect(markdown).toContain('| Invoice-Aware ML (usesOpenInvoices) | Yes |');
    expect(markdown).toContain('| Open Invoice Count | 4 |');
    expect(markdown).toContain(
      '| Active Ensemble Members | drift_calendar, gbdt_direct, invoice_aware_ml |',
    );
    expect(markdown).toContain(
      '| Ensemble Weights | +7d: drift_calendar 30.0%, gbdt_direct 20.0%, invoice_aware_ml 50.0% | +30d: drift_calendar 25.0%, gbdt_direct 15.0%, invoice_aware_ml 60.0% |',
    );
  });
});
