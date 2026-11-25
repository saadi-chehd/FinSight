import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App } from '../App';
import { BankPilotView } from '../components/BankPilotView';
import { BANKPILOT_GOVERNANCE_STATEMENT, CASH_FLOW_DISCLAIMER } from '../api/finsightApi';

const SAMPLE_SMES = [
  {
    id: 1,
    name: 'SYNTHETIC FS1-20261018-A Alpha Services',
    currency: 'USD',
    createdAt: '2026-10-18T12:00:00Z',
  },
  {
    id: 2,
    name: 'SYNTHETIC FS1-20261018-B Beta Manufacturing',
    currency: 'USD',
    createdAt: '2026-10-18T12:00:00Z',
  },
  {
    id: 3,
    name: 'Acme Bakery LLC',
    currency: 'USD',
    createdAt: '2026-10-18T12:00:00Z',
  },
];

function createFetchMock(smes = SAMPLE_SMES) {
  return vi.fn(async (url) => {
    const path = String(url);
    if (path === '/api/health') {
      return {
        ok: true,
        status: 200,
        text: async () => JSON.stringify({ status: 'UP', service: 'backend' }),
      };
    }
    if (path === '/api/health/database') {
      return {
        ok: true,
        status: 200,
        text: async () => JSON.stringify({ status: 'UP', service: 'database' }),
      };
    }
    if (path === '/api/smes') {
      return {
        ok: true,
        status: 200,
        text: async () => JSON.stringify(smes),
      };
    }
    if (path.includes('/cash-flow')) {
      return {
        ok: true,
        status: 200,
        text: async () =>
          JSON.stringify({
            smeId: 1,
            opening: { date: '2024-01-01', currency: 'USD', amount: '5000.00' },
            endDate: '2025-12-31',
            warningThreshold: '0.00',
            days: [
              {
                date: '2024-01-01',
                openingBalance: '5000.00',
                inflow: '200.00',
                outflow: '50.00',
                netMovement: '150.00',
                closingBalance: '5150.00',
              },
              {
                date: '2024-01-02',
                openingBalance: '5150.00',
                inflow: '0.00',
                outflow: '250.00',
                netMovement: '-250.00',
                closingBalance: '4900.00',
              },
              {
                date: '2024-01-03',
                openingBalance: '4900.00',
                inflow: '350.00',
                outflow: '0.00',
                netMovement: '350.00',
                closingBalance: '5250.00',
              },
            ],
            warnings: [
              {
                date: '2024-01-02',
                kind: 'BELOW_CONFIGURED_BUFFER',
                closingBalance: '4900.00',
                threshold: '5000.00',
                gapToThreshold: '100.00',
              },
            ],
          }),
      };
    }
    if (path.includes('/invoices/summary')) {
      return {
        ok: true,
        status: 200,
        text: async () =>
          JSON.stringify({
            smeId: 1,
            currency: 'USD',
            asOfDate: '2025-12-31',
            buckets: [
              {
                direction: 'INFLOW',
                status: 'PARTIALLY_PAID',
                count: 1,
                faceAmount: '300.00',
                paidToDate: '100.00',
                outstanding: '200.00',
                overdueCount: 1,
                overdueOutstanding: '200.00',
              },
              {
                direction: 'OUTFLOW',
                status: 'UNPAID',
                count: 1,
                faceAmount: '150.00',
                paidToDate: '0.00',
                outstanding: '150.00',
                overdueCount: 1,
                overdueOutstanding: '150.00',
              },
            ],
            invoices: [
              {
                id: 10,
                reference: 'INV-001',
                direction: 'INFLOW',
                dueDate: '2024-01-10',
                recordedStatus: 'UNPAID',
                effectiveStatus: 'PARTIALLY_PAID',
                statusMismatch: true,
                faceAmount: '300.00',
                paidToDate: '100.00',
                outstanding: '200.00',
                overpayment: '0.00',
                overdue: true,
              },
            ],
          }),
      };
    }
    if (path.includes('/forecast')) {
      return {
        ok: false,
        status: 503,
        text: async () =>
          JSON.stringify({
            status: 503,
            error: 'FORECAST_SERVICE_UNAVAILABLE',
            message: 'Forecast service offline in test',
            path,
          }),
      };
    }
    return {
      ok: true,
      status: 200,
      text: async () => JSON.stringify({}),
    };
  });
}

describe('FinSight & BankPilot Governance Guardrails', () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('starts opening cash blank, rejects 3 decimal places without calling /cash-flow, toggles synthetic banner, and loads FS1 preset', async () => {
    const fetchMock = createFetchMock(SAMPLE_SMES);
    vi.stubGlobal('fetch', fetchMock);

    render(<App />);

    // Wait for SMEs to load
    const smeSelect = await screen.findByLabelText(/Selected SME/i);
    await waitFor(() => {
      expect(smeSelect.value).toBe('1');
    });

    // Opening cash inputs must start blank, NEVER defaulted to 0.00
    const openingDateInput = screen.getByLabelText(/Opening Date/i);
    const openingAmountInput = screen.getByLabelText(/Opening Balance/i);
    const endDateInput = screen.getByLabelText(/End \/ Cutoff Date/i);

    expect(openingDateInput.value).toBe('');
    expect(openingAmountInput.value).toBe('');
    expect(endDateInput.value).toBe('');

    // Synthetic banner renders when SME name contains SYNTHETIC
    expect(screen.getByTestId('synthetic-provenance-banner')).toBeInTheDocument();

    // Switch to non-synthetic SME and verify SyntheticProvenanceBanner does NOT render
    fireEvent.change(smeSelect, { target: { value: '3' } });
    expect(screen.queryByTestId('synthetic-provenance-banner')).not.toBeInTheDocument();

    // Switch back to FS1-20261018-A synthetic SME
    fireEvent.change(smeSelect, { target: { value: '1' } });
    expect(screen.getByTestId('synthetic-provenance-banner')).toBeInTheDocument();

    // Enter >2 decimal places (5000.123) and submit -> must be rejected client-side without calling /cash-flow
    fireEvent.change(openingDateInput, { target: { value: '2024-01-01' } });
    fireEvent.change(openingAmountInput, { target: { value: '5000.123' } });
    fireEvent.change(endDateInput, { target: { value: '2025-12-31' } });

    fireEvent.click(screen.getByRole('button', { name: /Apply Opening Cash/i }));

    expect(
      await screen.findByText(/Fractional minor units are not allowed/i),
    ).toBeInTheDocument();

    const cashFlowCallsBeforePreset = fetchMock.mock.calls.filter(([url]) =>
      String(url).includes('/cash-flow'),
    );
    expect(cashFlowCallsBeforePreset).toHaveLength(0);

    // Clicking "Load FS1 Synthetic Preset" populates 5000.00 and 2024-01-01..2025-12-31
    const presetBtn = screen.getByRole('button', { name: /Load FS1 Synthetic Preset/i });
    fireEvent.click(presetBtn);

    expect(openingAmountInput.value).toBe('5000.00');
    expect(openingDateInput.value).toBe('2024-01-01');
    expect(endDateInput.value).toBe('2025-12-31');

    // Allow triggered CashFlowForecastView fetches to settle cleanly
    await waitFor(() => {
      expect(
        fetchMock.mock.calls.some(([url]) => String(url).includes('/cash-flow')),
      ).toBe(true);
    });
    await screen.findByText(/Forecasting Service Unavailable/i).catch(() => null);
  });

  it('renders BankPilotView with governance boundary, blank opening inputs, graceful forecast degradation, dossier checklist, and zero automated scoring UI', async () => {
    const fetchMock = createFetchMock(SAMPLE_SMES);
    vi.stubGlobal('fetch', fetchMock);

    render(<BankPilotView smes={SAMPLE_SMES} smesLoading={false} smesError={null} />);

    // Unmistakable governance boundary banner is visible
    expect(screen.getByText(/Human-Governed Financial Review Boundary/i)).toBeInTheDocument();
    expect(screen.getByText(BANKPILOT_GOVERNANCE_STATEMENT)).toBeInTheDocument();

    // All explicit opening inputs start blank (not 0.00)
    const openingAmountA = screen.getByLabelText(
      /Opening amount for SYNTHETIC FS1-20261018-A Alpha Services/i,
    );
    const openingDateA = screen.getByLabelText(
      /Opening date for SYNTHETIC FS1-20261018-A Alpha Services/i,
    );
    const endDateA = screen.getByLabelText(
      /End date for SYNTHETIC FS1-20261018-A Alpha Services/i,
    );
    expect(openingAmountA.value).toBe('');
    expect(openingDateA.value).toBe('');
    expect(endDateA.value).toBe('');

    // Verify NO automated credit score / loan approval / default probability UI exists in the portfolio table
    const table = screen.getByRole('table');
    const tableText = table.textContent || '';
    expect(tableText).not.toMatch(/credit score/i);
    expect(tableText).not.toMatch(/risk grade/i);
    expect(tableText).not.toMatch(/default probability/i);
    expect(tableText).not.toMatch(/loan approval/i);
    expect(tableText).not.toMatch(/creditworthiness/i);

    // Populate FS1 Synthetic Presets (Demo Only) fills FS1 SMEs while leaving non-FS1 SME blank
    fireEvent.click(
      screen.getByRole('button', { name: /Populate FS1 Synthetic Presets \(Demo Only\)/i }),
    );
    expect(openingAmountA.value).toBe('5000.00');
    expect(openingDateA.value).toBe('2024-01-01');
    expect(endDateA.value).toBe('2025-12-31');
    expect(
      screen.getByLabelText(/Opening amount for SYNTHETIC FS1-20261018-B Beta Manufacturing/i)
        .value,
    ).toBe('12000.00');
    expect(screen.getByLabelText(/Opening amount for Acme Bakery LLC/i).value).toBe('');

    // Analyze first row -> cash flow & invoice summary succeed while forecast 503 degrades gracefully
    const analyzeButtons = within(table).getAllByRole('button', { name: /^Analyze$/i });
    fireEvent.click(analyzeButtons[0]);

    expect(await screen.findByText(/Forecast unavailable/i)).toBeInTheDocument();
    expect(screen.getByText('$5,250.00')).toBeInTheDocument();
    expect(screen.getByText('$4,900.00')).toBeInTheDocument();
    expect(screen.getByText('$200.00')).toBeInTheDocument();
    expect(screen.getByText('$150.00')).toBeInTheDocument();

    // Open Dossier and verify provenance, disclaimers, 5 checklist items, notes, and export options
    const openDossierBtns = within(table).getAllByRole('button', { name: /Open Dossier/i });
    fireEvent.click(openDossierBtns[0]);

    const dossier = screen.getByRole('region', { name: /Human-Governed Review Dossier/i });
    expect(within(dossier).getByTestId('synthetic-provenance-banner')).toBeInTheDocument();
    expect(within(dossier).getByText(CASH_FLOW_DISCLAIMER)).toBeInTheDocument();
    expect(
      within(dossier).getByText(/split_conformal_past_rolling_residuals/i),
    ).toBeInTheDocument();

    const checkboxes = within(dossier).getAllByRole('checkbox');
    expect(checkboxes).toHaveLength(5);

    const dossierText = dossier.textContent || '';
    expect(dossierText).not.toMatch(/credit score/i);
    expect(dossierText).not.toMatch(/risk grade/i);
    expect(dossierText).not.toMatch(/default probability/i);
    expect(dossierText).not.toMatch(/loan approval/i);
    expect(dossierText).not.toMatch(/creditworthiness/i);

    fireEvent.click(
      within(dossier).getByRole('button', { name: /Print \/ Export Review Brief/i }),
    );
    expect(within(dossier).getByRole('button', { name: /Print Brief/i })).toBeInTheDocument();
    expect(
      within(dossier).getByRole('button', { name: /Download Markdown Brief \(\.md\)/i }),
    ).toBeInTheDocument();
  });
});
