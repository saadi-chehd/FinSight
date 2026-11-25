import React, { useEffect, useMemo, useState } from 'react';
import {
  AlertTriangle,
  ChevronLeft,
  ChevronRight,
  Download,
  RefreshCw,
  Search,
  TrendingUp,
  Wallet,
} from 'lucide-react';
import {
  CASH_FLOW_DISCLAIMER,
  FORECAST_MODELS,
  getCashFlow,
  getEnsembleWeightRows,
  getForecast,
} from '../api/finsightApi';
import {
  compareMoneyStrings,
  exportCsv,
  formatMoneyDisplay,
  sumMoneyStrings,
} from '../utils/money';
import { CashFlowChart } from './CashFlowChart';
import {
  ApiErrorAlert,
  Badge,
  DisclaimerBanner,
  EmptyState,
  LoadingSkeleton,
} from './Common';

const QUICK_HORIZONS = ['7,30', '7,14,30', '7,30,60'];
const PAGE_SIZE = 25;

function validateHorizonsInput(raw) {
  const trimmed = String(raw ?? '').trim();
  if (!trimmed) {
    return { valid: false, normalized: null, error: 'Enter at least one forecast horizon (1–365).' };
  }
  const parts = trimmed.split(',').map((p) => p.trim());
  if (parts.some((p) => p === '')) {
    return {
      valid: false,
      normalized: null,
      error: 'Horizons must be comma-separated integers without empty entries (e.g. 7,30).',
    };
  }
  const seen = new Set();
  const parsed = [];
  for (const part of parts) {
    if (!/^\d+$/.test(part)) {
      return {
        valid: false,
        normalized: null,
        error: `Invalid horizon "${part}". Use positive integers between 1 and 365.`,
      };
    }
    const num = Number(part);
    if (!Number.isInteger(num) || num < 1 || num > 365) {
      return {
        valid: false,
        normalized: null,
        error: `Horizon ${part} is out of range. Allowed range is 1..365 days.`,
      };
    }
    if (seen.has(num)) {
      return {
        valid: false,
        normalized: null,
        error: `Duplicate horizon ${num}. All horizon days must be unique.`,
      };
    }
    seen.add(num);
    parsed.push(num);
  }
  return { valid: true, normalized: parsed.join(','), error: null };
}

export function CashFlowForecastView({ sme, params }) {
  const [cashFlowReport, setCashFlowReport] = useState(null);
  const [cashFlowLoading, setCashFlowLoading] = useState(false);
  const [cashFlowError, setCashFlowError] = useState(null);
  const [cashFlowNonce, setCashFlowNonce] = useState(0);

  const [model, setModel] = useState('ensemble_ml');
  const [horizonsDraft, setHorizonsDraft] = useState('7,30');
  const [committedHorizons, setCommittedHorizons] = useState('7,30');
  const [horizonsError, setHorizonsError] = useState(null);

  const [forecast, setForecast] = useState(null);
  const [forecastLoading, setForecastLoading] = useState(false);
  const [forecastError, setForecastError] = useState(null);
  const [forecastNonce, setForecastNonce] = useState(0);

  const [dateSearch, setDateSearch] = useState('');
  const [warningsOnly, setWarningsOnly] = useState(false);
  const [currentPage, setCurrentPage] = useState(1);

  const smeId = sme?.id;
  const openingDate = params?.openingDate;
  const openingAmount = params?.openingAmount;
  const currency = params?.currency || sme?.currency || 'USD';
  const endDate = params?.endDate;

  // Fetch deterministic daily cash-flow report independently
  useEffect(() => {
    if (!smeId || !openingDate || openingAmount === undefined || !currency || !endDate) {
      setCashFlowReport(null);
      setCashFlowError(null);
      setCashFlowLoading(false);
      return undefined;
    }

    const controller = new AbortController();
    setCashFlowLoading(true);
    setCashFlowError(null);

    getCashFlow(smeId, { openingDate, openingAmount, currency, endDate }, controller.signal)
      .then((data) => {
        if (!controller.signal.aborted) {
          setCashFlowReport(data);
          setCurrentPage(1);
        }
      })
      .catch((err) => {
        if (!controller.signal.aborted && err?.name !== 'AbortError') {
          setCashFlowError(err);
          setCashFlowReport(null);
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) {
          setCashFlowLoading(false);
        }
      });

    return () => controller.abort();
  }, [smeId, openingDate, openingAmount, currency, endDate, cashFlowNonce]);

  // Fetch conditional forecast independently so 503/422 never blocks the cash-flow report
  useEffect(() => {
    if (!smeId || !openingDate || openingAmount === undefined || !currency || !endDate) {
      setForecast(null);
      setForecastError(null);
      setForecastLoading(false);
      return undefined;
    }

    const controller = new AbortController();
    setForecastLoading(true);
    setForecastError(null);

    getForecast(
      smeId,
      { openingDate, openingAmount, currency, endDate, horizons: committedHorizons, model },
      controller.signal,
    )
      .then((data) => {
        if (!controller.signal.aborted) {
          setForecast(data);
        }
      })
      .catch((err) => {
        if (!controller.signal.aborted && err?.name !== 'AbortError') {
          setForecastError(err);
          setForecast(null);
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) {
          setForecastLoading(false);
        }
      });

    return () => controller.abort();
  }, [smeId, openingDate, openingAmount, currency, endDate, committedHorizons, model, forecastNonce]);

  const warningsByDate = useMemo(() => {
    const map = new Map();
    for (const w of cashFlowReport?.warnings || []) {
      if (w?.date) {
        map.set(w.date, w);
      }
    }
    return map;
  }, [cashFlowReport]);

  const kpis = useMemo(() => {
    if (!cashFlowReport) return null;
    const days = cashFlowReport.days || [];
    const warnings = cashFlowReport.warnings || [];
    const threshold = cashFlowReport.warningThreshold || '0.00';

    const openingAmountStr = cashFlowReport.opening?.amount ?? openingAmount;
    const endingClosing =
      days.length > 0 ? days[days.length - 1].closingBalance : openingAmountStr;

    let minDay = days.length > 0 ? days[0] : null;
    for (let i = 1; i < days.length; i += 1) {
      if (compareMoneyStrings(days[i].closingBalance, minDay.closingBalance) < 0) {
        minDay = days[i];
      }
    }

    const totalInflows = sumMoneyStrings(days.map((d) => d.inflow));
    const totalOutflows = sumMoneyStrings(days.map((d) => d.outflow));

    let shortfallCount = 0;
    let belowBufferCount = 0;
    for (const w of warnings) {
      if (w.kind === 'POTENTIAL_CASH_SHORTFALL') {
        shortfallCount += 1;
      } else if (w.kind === 'BELOW_CONFIGURED_BUFFER') {
        belowBufferCount += 1;
      }
    }

    return {
      openingAmount: openingAmountStr,
      openingDate: cashFlowReport.opening?.date ?? openingDate,
      endingClosing,
      endDate: cashFlowReport.endDate ?? endDate,
      minBalance: minDay ? minDay.closingBalance : openingAmountStr,
      minDate: minDay ? minDay.date : openingDate,
      totalInflows,
      totalOutflows,
      dayCount: days.length,
      warningThreshold: threshold,
      totalWarnings: warnings.length,
      shortfallCount,
      belowBufferCount,
    };
  }, [cashFlowReport, openingAmount, openingDate, endDate]);

  const filteredDays = useMemo(() => {
    const days = cashFlowReport?.days || [];
    const searchTrimmed = dateSearch.trim();
    return days.filter((day) => {
      if (warningsOnly && !warningsByDate.has(day.date)) {
        return false;
      }
      if (searchTrimmed && !day.date.includes(searchTrimmed)) {
        return false;
      }
      return true;
    });
  }, [cashFlowReport, dateSearch, warningsOnly, warningsByDate]);

  const totalPages = Math.max(1, Math.ceil(filteredDays.length / PAGE_SIZE));
  const safePage = Math.min(currentPage, totalPages);
  const paginatedDays = useMemo(() => {
    const startIdx = (safePage - 1) * PAGE_SIZE;
    return filteredDays.slice(startIdx, startIdx + PAGE_SIZE);
  }, [filteredDays, safePage]);

  const sortedForecasts = useMemo(() => {
    if (!forecast || !Array.isArray(forecast.forecasts)) return [];
    return [...forecast.forecasts].sort((a, b) => a.horizonDays - b.horizonDays);
  }, [forecast]);

  const ensembleWeightRows = useMemo(
    () => getEnsembleWeightRows(forecast?.model?.ensembleWeights),
    [forecast],
  );

  if (!sme || !params) {
    return (
      <EmptyState
        icon={Wallet}
        title="Explicit Opening Cash Required"
        description="Enter explicit openingDate, openingAmount, currency, and endDate values above (or click the FS1 Synthetic Preset when available) to compute daily cash flow and conditional forecasts. Opening balances are never silently defaulted to 0.00."
      />
    );
  }

  const handleApplyHorizons = (e) => {
    e.preventDefault();
    const check = validateHorizonsInput(horizonsDraft);
    if (!check.valid) {
      setHorizonsError(check.error);
      return;
    }
    setHorizonsError(null);
    setHorizonsDraft(check.normalized);
    setCommittedHorizons(check.normalized);
  };

  const handleQuickHorizon = (preset) => {
    setHorizonsError(null);
    setHorizonsDraft(preset);
    setCommittedHorizons(preset);
  };

  const handleExportCsv = () => {
    const reportCurrency = cashFlowReport?.opening?.currency || currency;
    const headers = [
      'Date',
      'Currency',
      'Opening Balance',
      'Inflow',
      'Outflow',
      'Net Movement',
      'Closing Balance',
      'Status',
      'Gap To Threshold',
    ];
    const rows = filteredDays.map((d) => {
      const warning = warningsByDate.get(d.date);
      return [
        d.date,
        reportCurrency,
        d.openingBalance,
        d.inflow,
        d.outflow,
        d.netMovement,
        d.closingBalance,
        warning ? warning.kind : 'OK',
        warning ? warning.gapToThreshold : '0.00',
      ];
    });
    exportCsv(`finsight-cashflow-sme-${sme.id}-${openingDate}-to-${endDate}.csv`, headers, rows);
  };

  const reportCurrency = cashFlowReport?.opening?.currency || currency;

  return (
    <div className="section-stack">
      <DisclaimerBanner title="Conditional Cash-Flow Roll-Forward Notice" text={CASH_FLOW_DISCLAIMER} />

      {cashFlowLoading && !cashFlowReport && <LoadingSkeleton rows={6} />}

      {cashFlowError && (
        <ApiErrorAlert
          error={cashFlowError}
          title="Unable to load daily cash-flow report"
          onRetry={() => setCashFlowNonce((n) => n + 1)}
        />
      )}

      {cashFlowReport && kpis && (
        <>
          {/* KPI Strip */}
          <section className="kpi-grid" aria-label="Cash-Flow Summary Metrics">
            <div className="kpi-card">
              <span className="kpi-label">Opening Cash</span>
              <span className="kpi-value tabular-nums">
                {formatMoneyDisplay(kpis.openingAmount, reportCurrency)}
              </span>
              <span className="kpi-meta mono">As of {kpis.openingDate}</span>
            </div>

            <div className="kpi-card">
              <span className="kpi-label">Ending Closing Cash</span>
              <span
                className={`kpi-value tabular-nums ${
                  compareMoneyStrings(kpis.endingClosing, '0.00') < 0 ? 'negative' : ''
                }`}
              >
                {formatMoneyDisplay(kpis.endingClosing, reportCurrency)}
              </span>
              <span className="kpi-meta mono">Cutoff {kpis.endDate}</span>
            </div>

            <div className="kpi-card">
              <span className="kpi-label">Minimum Balance</span>
              <span
                className={`kpi-value tabular-nums ${
                  compareMoneyStrings(kpis.minBalance, '0.00') < 0
                    ? 'negative'
                    : compareMoneyStrings(kpis.minBalance, kpis.warningThreshold) < 0
                      ? 'warning'
                      : ''
                }`}
              >
                {formatMoneyDisplay(kpis.minBalance, reportCurrency)}
              </span>
              <span className="kpi-meta mono">Observed {kpis.minDate}</span>
            </div>

            <div className="kpi-card">
              <span className="kpi-label">Total Inflows</span>
              <span className="kpi-value positive tabular-nums">
                {formatMoneyDisplay(kpis.totalInflows, reportCurrency)}
              </span>
              <span className="kpi-meta">{kpis.dayCount} day(s) recorded</span>
            </div>

            <div className="kpi-card">
              <span className="kpi-label">Total Outflows</span>
              <span className="kpi-value tabular-nums">
                {formatMoneyDisplay(kpis.totalOutflows, reportCurrency)}
              </span>
              <span className="kpi-meta">Gross daily disbursements</span>
            </div>

            <div className="kpi-card">
              <span className="kpi-label">Liquidity Warnings</span>
              <span
                className={`kpi-value tabular-nums ${
                  kpis.shortfallCount > 0
                    ? 'negative'
                    : kpis.belowBufferCount > 0
                      ? 'warning'
                      : 'positive'
                }`}
              >
                {kpis.totalWarnings}
              </span>
              <span className="kpi-meta">
                Buffer {formatMoneyDisplay(kpis.warningThreshold, reportCurrency)} ·{' '}
                {kpis.shortfallCount} shortfall, {kpis.belowBufferCount} below buffer
              </span>
            </div>
          </section>

          {kpis.totalWarnings > 0 && (
            <div className="banner banner-warning" role="note">
              <AlertTriangle size={18} className="banner-icon" aria-hidden="true" />
              <div className="banner-content">
                <p className="banner-title">Liquidity Threshold Interpretation</p>
                <p className="banner-text">
                  <strong>POTENTIAL_CASH_SHORTFALL</strong> ({kpis.shortfallCount} day(s)) indicates a
                  closing balance below {formatMoneyDisplay('0.00', reportCurrency)} under the supplied
                  opening cash. <strong>BELOW_CONFIGURED_BUFFER</strong> ({kpis.belowBufferCount} day(s))
                  indicates a non-negative closing balance below the configured{' '}
                  {formatMoneyDisplay(kpis.warningThreshold, reportCurrency)} threshold.
                </p>
              </div>
            </div>
          )}

          {/* Interactive Cash Flow & Conformal Forecast Chart */}
          <CashFlowChart
            days={cashFlowReport.days}
            warnings={cashFlowReport.warnings}
            warningThreshold={cashFlowReport.warningThreshold}
            currency={reportCurrency}
            forecast={forecast}
          />
        </>
      )}

      {/* Forecast Controls & Diagnostics Card (independent error boundary) */}
      <section className="card section-stack" aria-label="Forecast Controls and Diagnostics">
        <div className="card-header" style={{ marginBottom: 0 }}>
          <div>
            <h3 className="card-title">
              <TrendingUp size={17} color="var(--accent-primary)" />
              Conditional Forecast Controls &amp; Diagnostics
            </h3>
            <p className="card-subtitle">
              Conformal prediction intervals derived strictly from historical daily closing balances
            </p>
          </div>
          <button
            type="button"
            className="btn btn-secondary btn-sm no-print"
            onClick={() => setForecastNonce((n) => n + 1)}
            disabled={forecastLoading}
          >
            <RefreshCw size={13} />
            {forecastLoading ? 'Running Forecast…' : 'Refresh Forecast'}
          </button>
        </div>

        <form className="toolbar no-print" onSubmit={handleApplyHorizons} style={{ marginBottom: 0 }} noValidate>
          <div className="field" style={{ minWidth: 230 }}>
            <label className="field-label" htmlFor="forecast-model-select">
              Forecasting Model
            </label>
            <select
              id="forecast-model-select"
              className="select"
              value={model}
              onChange={(e) => setModel(e.target.value)}
            >
              {FORECAST_MODELS.map((m) => (
                <option key={m.id} value={m.id}>
                  {m.label}
                </option>
              ))}
            </select>
          </div>

          <div className="field" style={{ minWidth: 220, flex: '1 1 220px', maxWidth: 340 }}>
            <label className="field-label" htmlFor="forecast-horizons-input">
              Horizons (unique integers 1..365)
            </label>
            <input
              id="forecast-horizons-input"
              type="text"
              className={`input mono ${horizonsError ? 'input-error' : ''}`}
              placeholder="e.g. 7,30"
              value={horizonsDraft}
              onChange={(e) => {
                setHorizonsDraft(e.target.value);
                if (horizonsError) setHorizonsError(null);
              }}
            />
            {horizonsError && <span className="field-error">{horizonsError}</span>}
          </div>

          <button type="submit" className="btn btn-primary">
            Apply Horizons
          </button>

          <div className="field">
            <span className="field-label">Quick Presets</span>
            <div className="btn-row">
              {QUICK_HORIZONS.map((preset) => (
                <button
                  key={preset}
                  type="button"
                  className={`btn btn-sm ${
                    committedHorizons === preset && !horizonsError ? 'btn-primary' : 'btn-secondary'
                  }`}
                  onClick={() => handleQuickHorizon(preset)}
                >
                  {preset}
                </button>
              ))}
            </div>
          </div>
        </form>

        {forecastLoading && !forecast && (
          <div className="kpi-grid" aria-busy="true">
            <div className="skeleton" style={{ height: 88 }} />
            <div className="skeleton" style={{ height: 88 }} />
          </div>
        )}

        {forecastError && (
          <ApiErrorAlert
            error={forecastError}
            title="Forecasting Service Unavailable or Input Rejected"
            onRetry={() => setForecastNonce((n) => n + 1)}
          />
        )}

        {forecast && (
          <div className="section-stack" style={{ gap: 14 }}>
            <div className="btn-row">
              {forecast.model?.version && (
                <Badge tone="indigo">Version: {forecast.model.version}</Badge>
              )}
              {forecast.model?.name && <Badge tone="neutral">Model: {forecast.model.name}</Badge>}
              {forecast.model?.usesOpenInvoices && (
                <Badge tone="teal">Invoice-Aware ML</Badge>
              )}
              {forecast.model?.openInvoiceCount != null && (
                <Badge tone="sky">Open Invoices: {forecast.model.openInvoiceCount}</Badge>
              )}
              {forecast.model?.activeMembers?.length > 0 && (
                <Badge tone="indigo">
                  Active Members: {forecast.model.activeMembers.join(', ')}
                </Badge>
              )}
              {forecast.model?.intervalMethod && (
                <Badge tone="sky">Interval: {forecast.model.intervalMethod}</Badge>
              )}
              {forecast.model?.minHistoryDays !== undefined && (
                <Badge tone="neutral">Min History: {forecast.model.minHistoryDays}d</Badge>
              )}
              {forecast.historyDays !== undefined && (
                <Badge tone="teal">History Days: {forecast.historyDays}d</Badge>
              )}
              {forecast.originDate && (
                <Badge tone="neutral">Origin: {forecast.originDate}</Badge>
              )}
            </div>

            {forecast.model?.description && (
              <p className="card-subtitle" style={{ margin: 0 }}>
                {forecast.model.description}
              </p>
            )}

            {ensembleWeightRows.length > 0 && (
              <div
                className="banner"
                style={{
                  background: 'var(--surface-muted)',
                  borderColor: 'var(--border-subtle)',
                  color: 'var(--navy-800)',
                  padding: '10px 14px',
                }}
                aria-label="Ensemble Horizon Weights"
              >
                <div className="banner-content">
                  <p className="banner-title" style={{ marginBottom: 4 }}>
                    Ensemble Horizon Weights (Past OOS MAE Inverse-Squared)
                  </p>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                    {ensembleWeightRows.map((row) => (
                      <div
                        key={row.horizon}
                        className="tabular-nums"
                        style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap', fontSize: 12.5 }}
                      >
                        <strong className="mono">+{row.horizon}d:</strong>
                        {row.members.map((m) => (
                          <Badge key={m.member} tone="neutral">
                            {m.member}: {m.percentText}
                          </Badge>
                        ))}
                      </div>
                    ))}
                  </div>
                </div>
              </div>
            )}

            <div className="kpi-grid">
              {sortedForecasts.map((item) => {
                const fcCurrency = forecast.currency || currency;
                return (
                  <div key={item.horizonDays} className="kpi-card">
                    <div
                      style={{
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'space-between',
                        gap: 8,
                      }}
                    >
                      <span className="kpi-label">+{item.horizonDays} Day Horizon</span>
                      <Badge tone="teal">{item.targetDate}</Badge>
                    </div>
                    <span className="kpi-value tabular-nums">
                      {formatMoneyDisplay(item.predictedClosingBalance, fcCurrency)}
                    </span>
                    <span className="kpi-meta tabular-nums">
                      80% CI: {formatMoneyDisplay(item.interval80?.lower, fcCurrency)} →{' '}
                      {formatMoneyDisplay(item.interval80?.upper, fcCurrency)}
                    </span>
                    <span className="kpi-meta tabular-nums">
                      95% CI: {formatMoneyDisplay(item.interval95?.lower, fcCurrency)} →{' '}
                      {formatMoneyDisplay(item.interval95?.upper, fcCurrency)}
                    </span>
                    <span className="kpi-meta">Residual count: {item.residualCount}</span>
                  </div>
                );
              })}
            </div>

            {forecast.disclaimer && (
              <DisclaimerBanner title="Conditional Forecast Notice" text={forecast.disclaimer} />
            )}
          </div>
        )}
      </section>

      {/* Daily Roll-Forward Table */}
      {cashFlowReport && (
        <section className="card" aria-label="Daily Cash-Flow Roll-Forward Table">
          <div className="card-header">
            <div>
              <h3 className="card-title">Daily Cash-Flow Roll-Forward Ledger</h3>
              <p className="card-subtitle">
                Deterministic daily opening, inflows, outflows, net movement, and closing balances
              </p>
            </div>
            <button
              type="button"
              className="btn btn-secondary btn-sm no-print"
              onClick={handleExportCsv}
              disabled={filteredDays.length === 0}
            >
              <Download size={14} />
              Export CSV ({filteredDays.length})
            </button>
          </div>

          <div className="toolbar no-print">
            <div className="field" style={{ minWidth: 220 }}>
              <label className="field-label" htmlFor="cashflow-date-search">
                Search Date (YYYY-MM-DD)
              </label>
              <div style={{ position: 'relative' }}>
                <Search
                  size={14}
                  style={{
                    position: 'absolute',
                    left: 10,
                    top: '50%',
                    transform: 'translateY(-50%)',
                    color: 'var(--slate-400)',
                    pointerEvents: 'none',
                  }}
                />
                <input
                  id="cashflow-date-search"
                  type="text"
                  className="input mono"
                  style={{ paddingLeft: 30 }}
                  placeholder="e.g. 2024-05"
                  value={dateSearch}
                  onChange={(e) => {
                    setDateSearch(e.target.value);
                    setCurrentPage(1);
                  }}
                />
              </div>
            </div>

            <label className="checkbox-label" style={{ paddingBottom: 8 }}>
              <input
                type="checkbox"
                checked={warningsOnly}
                onChange={(e) => {
                  setWarningsOnly(e.target.checked);
                  setCurrentPage(1);
                }}
              />
              Warnings Only
            </label>
          </div>

          {filteredDays.length === 0 ? (
            <EmptyState
              title="No matching roll-forward days"
              description="Adjust your date search or uncheck Warnings Only to view daily balances."
            />
          ) : (
            <>
              <div className="table-wrap">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>Date</th>
                      <th className="num">Opening Balance</th>
                      <th className="num">Inflow</th>
                      <th className="num">Outflow</th>
                      <th className="num">Net Movement</th>
                      <th className="num">Closing Balance</th>
                      <th>Status</th>
                    </tr>
                  </thead>
                  <tbody>
                    {paginatedDays.map((day) => {
                      const warning = warningsByDate.get(day.date);
                      const isShortfall = warning?.kind === 'POTENTIAL_CASH_SHORTFALL';
                      const isBelowBuffer = warning?.kind === 'BELOW_CONFIGURED_BUFFER';
                      const netCmp = compareMoneyStrings(day.netMovement, '0.00');
                      const closingCmp = compareMoneyStrings(day.closingBalance, '0.00');

                      return (
                        <tr
                          key={day.date}
                          className={
                            isShortfall ? 'row-danger' : isBelowBuffer ? 'row-warning' : undefined
                          }
                        >
                          <td className="mono">{day.date}</td>
                          <td className="num tabular-nums">
                            {formatMoneyDisplay(day.openingBalance, reportCurrency)}
                          </td>
                          <td className="num tabular-nums">
                            {formatMoneyDisplay(day.inflow, reportCurrency)}
                          </td>
                          <td className="num tabular-nums">
                            {formatMoneyDisplay(day.outflow, reportCurrency)}
                          </td>
                          <td
                            className="num tabular-nums"
                            style={{
                              color:
                                netCmp > 0
                                  ? 'var(--green-700)'
                                  : netCmp < 0
                                    ? 'var(--red-700)'
                                    : undefined,
                            }}
                          >
                            {formatMoneyDisplay(day.netMovement, reportCurrency, { showSign: true })}
                          </td>
                          <td
                            className="num tabular-nums"
                            style={{
                              fontWeight: 600,
                              color: closingCmp < 0 ? 'var(--red-700)' : undefined,
                            }}
                          >
                            {formatMoneyDisplay(day.closingBalance, reportCurrency)}
                          </td>
                          <td>
                            {warning ? (
                              <div className="toolbar-inline" style={{ gap: 6 }}>
                                <Badge tone={isShortfall ? 'red' : 'amber'}>{warning.kind}</Badge>
                                <span className="tabular-nums" style={{ fontSize: 12, color: 'var(--slate-600)' }}>
                                  Gap: {formatMoneyDisplay(warning.gapToThreshold, reportCurrency)}
                                </span>
                              </div>
                            ) : (
                              <Badge tone="green">OK</Badge>
                            )}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>

              <div className="pagination-bar no-print">
                <span>
                  Showing {(safePage - 1) * PAGE_SIZE + 1}–
                  {Math.min(safePage * PAGE_SIZE, filteredDays.length)} of {filteredDays.length} day(s)
                </span>
                <div className="btn-row">
                  <button
                    type="button"
                    className="btn btn-secondary btn-sm"
                    disabled={safePage <= 1}
                    onClick={() => setCurrentPage((p) => Math.max(1, p - 1))}
                  >
                    <ChevronLeft size={14} />
                    Previous
                  </button>
                  <span>
                    Page {safePage} of {totalPages}
                  </span>
                  <button
                    type="button"
                    className="btn btn-secondary btn-sm"
                    disabled={safePage >= totalPages}
                    onClick={() => setCurrentPage((p) => Math.min(totalPages, p + 1))}
                  >
                    Next
                    <ChevronRight size={14} />
                  </button>
                </div>
              </div>
            </>
          )}
        </section>
      )}
    </div>
  );
}
