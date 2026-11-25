import React, { useCallback, useMemo, useState } from 'react';
import {
  AlertTriangle,
  Building2,
  CheckSquare,
  Download,
  FileText,
  FlaskConical,
  Play,
  Printer,
} from 'lucide-react';
import {
  BANKPILOT_GOVERNANCE_STATEMENT,
  CASH_FLOW_DISCLAIMER,
  getCashFlow,
  getForecast,
  getFriendlyErrorMessage,
  getInvoiceSummary,
} from '../api/finsightApi';
import {
  compareMoneyStrings,
  formatMoneyDisplay,
  getFs1PresetForSme,
  isSyntheticSme,
  sumMoneyStrings,
  validateOpeningParams,
} from '../utils/money';
import {
  ApiErrorAlert,
  Badge,
  DisclaimerBanner,
  EmptyState,
  LoadingSkeleton,
  SyntheticProvenanceBanner,
} from './Common';

const EMPTY_ROW_DRAFT = {
  openingDate: '',
  openingAmount: '',
  endDate: '',
};

const ANALYST_CHECKLIST_ITEMS = [
  {
    id: 'opening_provenance',
    label: 'Verified external bank statement opening cash provenance',
  },
  {
    id: 'status_mismatches',
    label: 'Reviewed status-mismatch invoices',
  },
  {
    id: 'liquidity_warnings',
    label: 'Reviewed liquidity warning dates & threshold gaps',
  },
  {
    id: 'conformal_bounds',
    label: 'Evaluated 95% lower conformal bound sensitivity',
  },
  {
    id: 'data_classification',
    label: 'Confirmed synthetic/real data classification',
  },
];

function computeRowIndicators(analysis) {
  if (!analysis) {
    return null;
  }

  const { cashFlow, invoiceSummary, forecast } = analysis;
  const days = Array.isArray(cashFlow?.days) ? cashFlow.days : [];
  const warnings = Array.isArray(cashFlow?.warnings) ? cashFlow.warnings : [];

  let endingCash = null;
  let endingCashDate = cashFlow?.endDate || null;
  let minCash = null;
  if (days.length > 0) {
    const lastDay = days[days.length - 1];
    endingCash = lastDay.closingBalance;
    endingCashDate = lastDay.date || endingCashDate;

    let minDay = days[0];
    for (let i = 1; i < days.length; i += 1) {
      if (compareMoneyStrings(days[i].closingBalance, minDay.closingBalance) < 0) {
        minDay = days[i];
      }
    }
    minCash = { amount: minDay.closingBalance, date: minDay.date };
  } else if (cashFlow?.opening?.amount != null) {
    endingCash = cashFlow.opening.amount;
    minCash = { amount: cashFlow.opening.amount, date: cashFlow.opening.date };
  }

  const shortfallCount = warnings.filter((w) => w.kind === 'POTENTIAL_CASH_SHORTFALL').length;
  const bufferCount = warnings.filter((w) => w.kind === 'BELOW_CONFIGURED_BUFFER').length;

  let overdueReceivables = null;
  let overduePayables = null;
  let statusMismatchCount = null;
  if (invoiceSummary) {
    const buckets = Array.isArray(invoiceSummary.buckets) ? invoiceSummary.buckets : [];
    const inflowBuckets = buckets.filter((b) => b.direction === 'INFLOW');
    const outflowBuckets = buckets.filter((b) => b.direction === 'OUTFLOW');

    overdueReceivables = {
      amount: sumMoneyStrings(inflowBuckets.map((b) => b.overdueOutstanding || '0.00')),
      count: inflowBuckets.reduce((acc, b) => acc + Number(b.overdueCount || 0), 0),
    };
    overduePayables = {
      amount: sumMoneyStrings(outflowBuckets.map((b) => b.overdueOutstanding || '0.00')),
      count: outflowBuckets.reduce((acc, b) => acc + Number(b.overdueCount || 0), 0),
    };

    const invoices = Array.isArray(invoiceSummary.invoices) ? invoiceSummary.invoices : [];
    statusMismatchCount = invoices.filter((inv) => Boolean(inv.statusMismatch)).length;
  }

  const forecasts = Array.isArray(forecast?.forecasts) ? forecast.forecasts : [];
  const forecast7d = forecasts.find((f) => Number(f.horizonDays) === 7) || null;
  const forecast30d = forecasts.find((f) => Number(f.horizonDays) === 30) || null;

  return {
    endingCash,
    endingCashDate,
    minCash,
    warningsCount: cashFlow ? warnings.length : null,
    shortfallCount,
    bufferCount,
    overdueReceivables,
    overduePayables,
    statusMismatchCount,
    forecast7d,
    forecast30d,
  };
}

function buildMarkdownBrief({ sme, draft, analysis, indicators, checklist, notes }) {
  const synthetic = isSyntheticSme(sme);
  const currency = sme.currency;
  const activeParams = analysis?.params || draft;
  const cashFlow = analysis?.cashFlow || null;
  const forecast = analysis?.forecast || null;
  const forecastError = analysis?.forecastError || null;

  const modelLabel = forecast?.model
    ? `${forecast.model.name} (${forecast.model.version || 'CM1'})`
    : 'drift_calendar (CM1 — forecast unavailable)';
  const intervalMethod =
    forecast?.model?.intervalMethod || 'split_conformal_past_rolling_residuals';
  const historyDays = forecast?.historyDays ?? cashFlow?.days?.length ?? 'N/A';

  const lines = [
    `# BankPilot Human-Governed Financial Review Brief`,
    '',
    `> **Governance Boundary:** ${BANKPILOT_GOVERNANCE_STATEMENT}`,
    '',
  ];

  if (synthetic) {
    lines.push(
      `> **Synthetic Data Provenance (FS1):** ${sme.name} uses deterministically generated synthetic dataset records for verification and demonstration — not real SME performance or audited bank balances.`,
      '',
    );
  }

  lines.push(
    `## 1. Calculation Provenance`,
    '',
    `| Field | Value |`,
    `| --- | --- |`,
    `| SME ID | ${sme.id} |`,
    `| SME Name | ${sme.name} |`,
    `| Synthetic Data Flag | ${synthetic ? 'YES (FS1 Synthetic)' : 'NO'} |`,
    `| Currency | ${currency} |`,
    `| Opening Date | ${activeParams?.openingDate || 'Not set'} |`,
    `| Explicit Opening Amount | ${activeParams?.openingAmount ? formatMoneyDisplay(activeParams.openingAmount, currency) : 'Not set'} |`,
    `| End / Cutoff Date | ${activeParams?.endDate || 'Not set'} |`,
    `| Warning Threshold | ${cashFlow?.warningThreshold != null ? formatMoneyDisplay(cashFlow.warningThreshold, currency) : 'N/A'} |`,
    `| Forecast Model & Version | ${modelLabel} |`,
    `| Interval Method | ${intervalMethod} |`,
    `| History Days | ${historyDays} |`,
    '',
    `## 2. Audited Indicators`,
    '',
  );

  if (!analysis || !indicators) {
    lines.push(`_No portfolio analysis has been executed for this SME yet._`, '');
  } else {
    const f7Text = indicators.forecast7d
      ? `${formatMoneyDisplay(indicators.forecast7d.predictedClosingBalance, currency)} (95% lower: ${formatMoneyDisplay(indicators.forecast7d.interval95?.lower, currency)})`
      : forecastError
        ? `Unavailable (${getFriendlyErrorMessage(forecastError)})`
        : 'Unavailable';
    const f30Text = indicators.forecast30d
      ? `${formatMoneyDisplay(indicators.forecast30d.predictedClosingBalance, currency)} (95% lower: ${formatMoneyDisplay(indicators.forecast30d.interval95?.lower, currency)})`
      : forecastError
        ? `Unavailable (${getFriendlyErrorMessage(forecastError)})`
        : 'Unavailable';

    lines.push(
      `- **Ending Cash:** ${indicators.endingCash != null ? formatMoneyDisplay(indicators.endingCash, currency) : 'N/A'}`,
      `- **Minimum Cash:** ${indicators.minCash ? `${formatMoneyDisplay(indicators.minCash.amount, currency)} on ${indicators.minCash.date}` : 'N/A'}`,
      `- **Liquidity Warnings:** ${indicators.warningsCount ?? 'N/A'}`,
      `- **Overdue Receivables (INFLOW):** ${indicators.overdueReceivables ? `${formatMoneyDisplay(indicators.overdueReceivables.amount, currency)} (${indicators.overdueReceivables.count} overdue)` : 'N/A'}`,
      `- **Overdue Payables (OUTFLOW):** ${indicators.overduePayables ? `${formatMoneyDisplay(indicators.overduePayables.amount, currency)} (${indicators.overduePayables.count} overdue)` : 'N/A'}`,
      `- **Invoice Status Mismatches:** ${indicators.statusMismatchCount ?? 'N/A'}`,
      `- **7d Forecast:** ${f7Text}`,
      `- **30d Forecast:** ${f30Text}`,
      '',
    );
  }

  lines.push(`## 3. Human Analyst Review Checklist`, '');
  for (const item of ANALYST_CHECKLIST_ITEMS) {
    const checked = Boolean(checklist?.[item.id]);
    lines.push(`- [${checked ? 'x' : ' '}] ${item.label}`);
  }

  lines.push(
    '',
    `## 4. Analyst Notes`,
    '',
    notes?.trim() ? notes.trim() : '_No analyst notes recorded._',
    '',
    `## 5. Conditional Analysis Disclaimers`,
    '',
    `- **Cash-Flow Roll-Forward:** ${CASH_FLOW_DISCLAIMER}`,
  );

  if (forecast?.disclaimer) {
    lines.push(`- **Forecasting:** ${forecast.disclaimer}`);
  }

  lines.push('');
  return lines.join('\n');
}

export function BankPilotView({ smes = [], smesLoading = false, smesError = null }) {
  const [draftsBySme, setDraftsBySme] = useState({});
  const [errorsBySme, setErrorsBySme] = useState({});
  const [loadingBySme, setLoadingBySme] = useState({});
  const [analysisBySme, setAnalysisBySme] = useState({});
  const [portfolioMessage, setPortfolioMessage] = useState(null);
  const [portfolioBusy, setPortfolioBusy] = useState(false);

  const [dossierSmeId, setDossierSmeId] = useState(null);
  const [checklistBySme, setChecklistBySme] = useState({});
  const [notesBySme, setNotesBySme] = useState({});
  const [exportMenuOpen, setExportMenuOpen] = useState(false);

  const hasFs1Presets = useMemo(
    () => smes.some((sme) => Boolean(getFs1PresetForSme(sme))),
    [smes],
  );

  const getDraftForSme = useCallback(
    (smeId) => draftsBySme[smeId] || EMPTY_ROW_DRAFT,
    [draftsBySme],
  );

  const updateRowDraft = (smeId, field, value) => {
    setPortfolioMessage(null);
    setDraftsBySme((prev) => ({
      ...prev,
      [smeId]: {
        ...(prev[smeId] || EMPTY_ROW_DRAFT),
        [field]: value,
      },
    }));
    setErrorsBySme((prev) => {
      if (!prev[smeId]?.[field]) return prev;
      const nextRowErrors = { ...prev[smeId] };
      delete nextRowErrors[field];
      return {
        ...prev,
        [smeId]: nextRowErrors,
      };
    });
  };

  const handlePopulateFs1Presets = () => {
    setPortfolioMessage(null);
    setDraftsBySme((prev) => {
      const next = { ...prev };
      for (const sme of smes) {
        const preset = getFs1PresetForSme(sme);
        if (preset) {
          next[sme.id] = {
            openingDate: preset.openingDate,
            openingAmount: preset.openingAmount,
            endDate: preset.endDate,
          };
        }
      }
      return next;
    });
    setErrorsBySme((prev) => {
      const next = { ...prev };
      for (const sme of smes) {
        if (getFs1PresetForSme(sme)) {
          delete next[sme.id];
        }
      }
      return next;
    });
  };

  const runSmeAnalysis = useCallback(async (sme, normalizedParams) => {
    setLoadingBySme((prev) => ({ ...prev, [sme.id]: true }));
    try {
      const [cfRes, invRes, fcRes] = await Promise.allSettled([
        getCashFlow(sme.id, normalizedParams),
        getInvoiceSummary(sme.id, { asOfDate: normalizedParams.endDate }),
        getForecast(sme.id, {
          ...normalizedParams,
          horizons: '7,30',
          model: 'drift_calendar',
        }),
      ]);

      setAnalysisBySme((prev) => ({
        ...prev,
        [sme.id]: {
          params: normalizedParams,
          cashFlow: cfRes.status === 'fulfilled' ? cfRes.value : null,
          cashFlowError: cfRes.status === 'rejected' ? cfRes.reason : null,
          invoiceSummary: invRes.status === 'fulfilled' ? invRes.value : null,
          invoiceError: invRes.status === 'rejected' ? invRes.reason : null,
          forecast: fcRes.status === 'fulfilled' ? fcRes.value : null,
          forecastError: fcRes.status === 'rejected' ? fcRes.reason : null,
          analyzedAt: new Date().toISOString(),
        },
      }));
    } finally {
      setLoadingBySme((prev) => ({ ...prev, [sme.id]: false }));
    }
  }, []);

  const handleAnalyzeRow = async (sme) => {
    setPortfolioMessage(null);
    const draft = getDraftForSme(sme.id);
    const check = validateOpeningParams({
      openingDate: draft.openingDate,
      openingAmount: draft.openingAmount,
      currency: sme.currency,
      endDate: draft.endDate,
    });

    if (!check.valid) {
      setErrorsBySme((prev) => ({ ...prev, [sme.id]: check.errors }));
      return;
    }

    setErrorsBySme((prev) => {
      const next = { ...prev };
      delete next[sme.id];
      return next;
    });
    setDraftsBySme((prev) => ({
      ...prev,
      [sme.id]: {
        openingDate: check.normalized.openingDate,
        openingAmount: check.normalized.openingAmount,
        endDate: check.normalized.endDate,
      },
    }));

    await runSmeAnalysis(sme, check.normalized);
  };

  const handleAnalyzePortfolio = async () => {
    setPortfolioMessage(null);
    const editedSmes = smes.filter((sme) => {
      const d = getDraftForSme(sme.id);
      return Boolean(
        String(d.openingDate).trim() ||
          String(d.openingAmount).trim() ||
          String(d.endDate).trim(),
      );
    });

    const targetSmes = editedSmes.length > 0 ? editedSmes : smes;
    if (editedSmes.length === 0) {
      setPortfolioMessage(
        'Enter explicit opening date, opening balance, and end date for at least one SME (or populate FS1 presets) before analyzing.',
      );
    }

    const nextErrors = { ...errorsBySme };
    const validPairs = [];

    for (const sme of targetSmes) {
      const draft = getDraftForSme(sme.id);
      const check = validateOpeningParams({
        openingDate: draft.openingDate,
        openingAmount: draft.openingAmount,
        currency: sme.currency,
        endDate: draft.endDate,
      });
      if (!check.valid) {
        nextErrors[sme.id] = check.errors;
      } else {
        delete nextErrors[sme.id];
        validPairs.push({ sme, normalized: check.normalized });
      }
    }

    setErrorsBySme(nextErrors);

    if (validPairs.length === 0) {
      return;
    }

    setDraftsBySme((prev) => {
      const next = { ...prev };
      for (const { sme, normalized } of validPairs) {
        next[sme.id] = {
          openingDate: normalized.openingDate,
          openingAmount: normalized.openingAmount,
          endDate: normalized.endDate,
        };
      }
      return next;
    });

    setPortfolioBusy(true);
    try {
      await Promise.all(
        validPairs.map(({ sme, normalized }) => runSmeAnalysis(sme, normalized)),
      );
    } finally {
      setPortfolioBusy(false);
    }
  };

  const dossierSme = useMemo(
    () => smes.find((s) => String(s.id) === String(dossierSmeId)) || null,
    [smes, dossierSmeId],
  );
  const dossierAnalysis = dossierSme ? analysisBySme[dossierSme.id] || null : null;
  const dossierIndicators = useMemo(
    () => computeRowIndicators(dossierAnalysis),
    [dossierAnalysis],
  );
  const dossierDraft = dossierSme ? getDraftForSme(dossierSme.id) : EMPTY_ROW_DRAFT;
  const dossierChecklist = dossierSme ? checklistBySme[dossierSme.id] || {} : {};
  const dossierNotes = dossierSme ? notesBySme[dossierSme.id] || '' : '';

  const toggleChecklistItem = (smeId, itemId) => {
    setChecklistBySme((prev) => ({
      ...prev,
      [smeId]: {
        ...(prev[smeId] || {}),
        [itemId]: !prev[smeId]?.[itemId],
      },
    }));
  };

  const updateAnalystNotes = (smeId, value) => {
    setNotesBySme((prev) => ({
      ...prev,
      [smeId]: value,
    }));
  };

  const handleDownloadMarkdownBrief = () => {
    if (!dossierSme) return;
    const markdown = buildMarkdownBrief({
      sme: dossierSme,
      draft: dossierDraft,
      analysis: dossierAnalysis,
      indicators: dossierIndicators,
      checklist: dossierChecklist,
      notes: dossierNotes,
    });
    const blob = new Blob([markdown], { type: 'text/markdown;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.setAttribute('download', `bankpilot-review-sme-${dossierSme.id}.md`);
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
  };

  const openDossierFor = (smeId) => {
    setDossierSmeId(smeId);
    setExportMenuOpen(false);
  };

  return (
    <div className="section-stack">
      <DisclaimerBanner
        variant="governance"
        title="Human-Governed Financial Review Boundary"
        text={BANKPILOT_GOVERNANCE_STATEMENT}
      />

      {smesError && smes.length === 0 && (
        <ApiErrorAlert error={smesError} title="Unable to load SME registry" />
      )}

      {smesLoading && smes.length === 0 ? (
        <LoadingSkeleton rows={5} />
      ) : smes.length === 0 ? (
        <EmptyState
          icon={Building2}
          title="No SMEs available for BankPilot review"
          description="Load SME records into PostgreSQL and refresh to inspect multi-SME liquidity indicators and human-governed review dossiers."
        />
      ) : (
        <section className="card" aria-label="Multi-SME Portfolio Comparison">
          <div className="card-header">
            <div>
              <h2 className="card-title">
                <Building2 size={17} color="var(--teal-700)" />
                Multi-SME Portfolio Comparison
              </h2>
              <p className="card-subtitle">
                Each SME requires analyst-confirmed opening balance, opening date, and cutoff date (never defaulted to 0.00).
              </p>
            </div>

            <div className="btn-row no-print">
              <button
                type="button"
                className="btn btn-preset btn-sm"
                onClick={handlePopulateFs1Presets}
                disabled={!hasFs1Presets}
                title="Populate documented synthetic FS1 opening inputs (FS1-20261018-A: 5000.00, B: 12000.00, C: 8000.00)"
              >
                <FlaskConical size={14} />
                Populate FS1 Synthetic Presets (Demo Only)
              </button>
              <button
                type="button"
                className="btn btn-primary btn-sm"
                onClick={handleAnalyzePortfolio}
                disabled={portfolioBusy}
              >
                <Play size={14} />
                {portfolioBusy ? 'Analyzing Portfolio…' : 'Analyze Portfolio'}
              </button>
            </div>
          </div>

          {portfolioMessage && (
            <div className="banner banner-warning" role="alert" style={{ marginBottom: 14 }}>
              <AlertTriangle size={17} className="banner-icon" aria-hidden="true" />
              <div className="banner-content">
                <p className="banner-text">{portfolioMessage}</p>
              </div>
            </div>
          )}

          <div className="table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>SME &amp; Currency</th>
                  <th>Opening Date</th>
                  <th>Opening Amount</th>
                  <th>End / Cutoff Date</th>
                  <th className="num">Ending Cash</th>
                  <th className="num">Minimum Cash</th>
                  <th className="num">Liquidity Warnings</th>
                  <th className="num">Overdue Receivables</th>
                  <th className="num">Overdue Payables</th>
                  <th className="num">Status Mismatches</th>
                  <th>Forecast (7d / 30d &amp; 95% Lower)</th>
                  <th className="no-print">Actions</th>
                </tr>
              </thead>
              <tbody>
                {smes.map((sme) => {
                  const draft = getDraftForSme(sme.id);
                  const rowErrors = errorsBySme[sme.id] || {};
                  const isRowLoading = Boolean(loadingBySme[sme.id]);
                  const analysis = analysisBySme[sme.id] || null;
                  const indicators = computeRowIndicators(analysis);
                  const synthetic = isSyntheticSme(sme);
                  const isSelected = String(dossierSmeId) === String(sme.id);
                  const hasShortfall = (indicators?.shortfallCount || 0) > 0;
                  const hasWarnings = (indicators?.warningsCount || 0) > 0;

                  const rowClasses = [
                    isSelected ? 'row-selected' : hasShortfall ? 'row-danger' : hasWarnings ? 'row-warning' : '',
                  ]
                    .filter(Boolean)
                    .join(' ');

                  return (
                    <tr
                      key={sme.id}
                      className={rowClasses}
                      onClick={(e) => {
                        if (e.target.closest('input, button, select, textarea, a, label')) {
                          return;
                        }
                        openDossierFor(sme.id);
                      }}
                    >
                      <td>
                        <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                          <span style={{ fontWeight: 700, color: 'var(--navy-900)' }}>{sme.name}</span>
                          <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
                            <span className="mono" style={{ fontSize: 11.5, color: 'var(--slate-500)' }}>
                              #{sme.id}
                            </span>
                            <Badge tone="indigo">{sme.currency}</Badge>
                            {synthetic && <Badge tone="amber">Synthetic</Badge>}
                          </div>
                        </div>
                      </td>

                      <td>
                        <div className="field" style={{ minWidth: 135 }}>
                          <input
                            type="date"
                            aria-label={`Opening date for ${sme.name}`}
                            className={`input ${rowErrors.openingDate ? 'input-error' : ''}`}
                            value={draft.openingDate}
                            onChange={(e) => updateRowDraft(sme.id, 'openingDate', e.target.value)}
                          />
                          {rowErrors.openingDate && (
                            <span className="field-error">{rowErrors.openingDate}</span>
                          )}
                        </div>
                      </td>

                      <td>
                        <div className="field" style={{ minWidth: 130 }}>
                          <input
                            type="text"
                            inputMode="decimal"
                            placeholder="Explicit amount"
                            aria-label={`Opening amount for ${sme.name}`}
                            className={`input tabular-nums ${rowErrors.openingAmount ? 'input-error' : ''}`}
                            value={draft.openingAmount}
                            onChange={(e) => updateRowDraft(sme.id, 'openingAmount', e.target.value)}
                          />
                          {rowErrors.openingAmount && (
                            <span className="field-error">{rowErrors.openingAmount}</span>
                          )}
                        </div>
                      </td>

                      <td>
                        <div className="field" style={{ minWidth: 135 }}>
                          <input
                            type="date"
                            aria-label={`End date for ${sme.name}`}
                            className={`input ${rowErrors.endDate ? 'input-error' : ''}`}
                            value={draft.endDate}
                            onChange={(e) => updateRowDraft(sme.id, 'endDate', e.target.value)}
                          />
                          {rowErrors.endDate && <span className="field-error">{rowErrors.endDate}</span>}
                        </div>
                      </td>

                      <td className="num tabular-nums">
                        {isRowLoading ? (
                          <span className="field-hint">Loading…</span>
                        ) : analysis?.cashFlowError ? (
                          <Badge tone="red">CF Error</Badge>
                        ) : indicators?.endingCash != null ? (
                          <div>
                            <div style={{ fontWeight: 700 }}>
                              {formatMoneyDisplay(indicators.endingCash, sme.currency)}
                            </div>
                            <div className="field-hint">{indicators.endingCashDate}</div>
                          </div>
                        ) : (
                          '—'
                        )}
                      </td>

                      <td className="num tabular-nums">
                        {isRowLoading ? (
                          '…'
                        ) : indicators?.minCash ? (
                          <div>
                            <div style={{ fontWeight: 600 }}>
                              {formatMoneyDisplay(indicators.minCash.amount, sme.currency)}
                            </div>
                            <div className="field-hint">{indicators.minCash.date}</div>
                          </div>
                        ) : (
                          '—'
                        )}
                      </td>

                      <td className="num tabular-nums">
                        {isRowLoading ? (
                          '…'
                        ) : indicators?.warningsCount != null ? (
                          <Badge
                            tone={
                              indicators.shortfallCount > 0
                                ? 'red'
                                : indicators.warningsCount > 0
                                  ? 'amber'
                                  : 'green'
                            }
                          >
                            {indicators.warningsCount}
                          </Badge>
                        ) : (
                          '—'
                        )}
                      </td>

                      <td className="num tabular-nums">
                        {isRowLoading ? (
                          '…'
                        ) : analysis?.invoiceError ? (
                          <Badge tone="red">Inv Error</Badge>
                        ) : indicators?.overdueReceivables ? (
                          <div>
                            <div style={{ fontWeight: 600 }}>
                              {formatMoneyDisplay(indicators.overdueReceivables.amount, sme.currency)}
                            </div>
                            <div className="field-hint">
                              {indicators.overdueReceivables.count} overdue
                            </div>
                          </div>
                        ) : (
                          '—'
                        )}
                      </td>

                      <td className="num tabular-nums">
                        {isRowLoading ? (
                          '…'
                        ) : analysis?.invoiceError ? (
                          <Badge tone="red">Inv Error</Badge>
                        ) : indicators?.overduePayables ? (
                          <div>
                            <div style={{ fontWeight: 600 }}>
                              {formatMoneyDisplay(indicators.overduePayables.amount, sme.currency)}
                            </div>
                            <div className="field-hint">
                              {indicators.overduePayables.count} overdue
                            </div>
                          </div>
                        ) : (
                          '—'
                        )}
                      </td>

                      <td className="num tabular-nums">
                        {isRowLoading ? (
                          '…'
                        ) : indicators?.statusMismatchCount != null ? (
                          <Badge tone={indicators.statusMismatchCount > 0 ? 'amber' : 'neutral'}>
                            {indicators.statusMismatchCount}
                          </Badge>
                        ) : (
                          '—'
                        )}
                      </td>

                      <td className="tabular-nums">
                        {isRowLoading ? (
                          <span className="field-hint">Forecasting…</span>
                        ) : analysis?.forecastError ? (
                          <div style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
                            <Badge tone="amber">Forecast unavailable</Badge>
                            <span className="field-hint">
                              {analysis.forecastError.status
                                ? `HTTP ${analysis.forecastError.status}`
                                : 'Unavailable'}
                            </span>
                          </div>
                        ) : indicators?.forecast7d || indicators?.forecast30d ? (
                          <div style={{ fontSize: 12, lineHeight: 1.45 }}>
                            {indicators.forecast7d && (
                              <div>
                                <strong>7d:</strong>{' '}
                                {formatMoneyDisplay(
                                  indicators.forecast7d.predictedClosingBalance,
                                  sme.currency,
                                )}{' '}
                                <span className="field-hint">
                                  (95% L:{' '}
                                  {formatMoneyDisplay(
                                    indicators.forecast7d.interval95?.lower,
                                    sme.currency,
                                  )}
                                  )
                                </span>
                              </div>
                            )}
                            {indicators.forecast30d && (
                              <div>
                                <strong>30d:</strong>{' '}
                                {formatMoneyDisplay(
                                  indicators.forecast30d.predictedClosingBalance,
                                  sme.currency,
                                )}{' '}
                                <span className="field-hint">
                                  (95% L:{' '}
                                  {formatMoneyDisplay(
                                    indicators.forecast30d.interval95?.lower,
                                    sme.currency,
                                  )}
                                  )
                                </span>
                              </div>
                            )}
                          </div>
                        ) : (
                          '—'
                        )}
                      </td>

                      <td className="no-print">
                        <div className="btn-row" style={{ flexWrap: 'nowrap' }}>
                          <button
                            type="button"
                            className="btn btn-secondary btn-sm"
                            onClick={() => handleAnalyzeRow(sme)}
                            disabled={isRowLoading}
                          >
                            Analyze
                          </button>
                          <button
                            type="button"
                            className={`btn btn-sm ${isSelected ? 'btn-primary' : 'btn-secondary'}`}
                            onClick={() => openDossierFor(sme.id)}
                          >
                            Open Dossier
                          </button>
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </section>
      )}

      {dossierSme && (
        <section className="card dossier-card section-stack" aria-label="Human-Governed Review Dossier">
          <div className="card-header" style={{ marginBottom: 0 }}>
            <div>
              <h2 className="card-title">
                <FileText size={17} color="var(--teal-700)" />
                Human-Governed Review Dossier — {dossierSme.name}
              </h2>
              <p className="card-subtitle">
                Audited provenance, conditional disclaimers, and structured analyst sign-off checklist.
              </p>
            </div>

            <div className="btn-row no-print">
              <button
                type="button"
                className="btn btn-primary btn-sm"
                onClick={() => setExportMenuOpen((prev) => !prev)}
                aria-expanded={exportMenuOpen}
              >
                <Printer size={14} />
                Print / Export Review Brief
              </button>
              {exportMenuOpen && (
                <>
                  <button
                    type="button"
                    className="btn btn-secondary btn-sm"
                    onClick={() => window.print()}
                  >
                    <Printer size={14} />
                    Print Brief
                  </button>
                  <button
                    type="button"
                    className="btn btn-secondary btn-sm"
                    onClick={handleDownloadMarkdownBrief}
                  >
                    <Download size={14} />
                    Download Markdown Brief (.md)
                  </button>
                </>
              )}
              <button
                type="button"
                className="btn btn-secondary btn-sm"
                onClick={() => setDossierSmeId(null)}
              >
                Close Dossier
              </button>
            </div>
          </div>

          <SyntheticProvenanceBanner smeName={dossierSme.name} />

          {dossierAnalysis?.cashFlowError && (
            <ApiErrorAlert
              error={dossierAnalysis.cashFlowError}
              title="Cash-Flow Report Error"
            />
          )}
          {dossierAnalysis?.invoiceError && (
            <ApiErrorAlert
              error={dossierAnalysis.invoiceError}
              title="Invoice Summary Error"
            />
          )}
          {dossierAnalysis?.forecastError && (
            <ApiErrorAlert
              error={dossierAnalysis.forecastError}
              title="Forecasting Service Unavailable / Rejected"
            />
          )}

          <div>
            <h3 className="card-title" style={{ fontSize: 14, marginBottom: 10 }}>
              Calculation Provenance
            </h3>
            <div className="table-wrap">
              <table className="data-table">
                <tbody>
                  <tr>
                    <th>SME ID</th>
                    <td className="mono">{dossierSme.id}</td>
                    <th>SME Name</th>
                    <td>{dossierSme.name}</td>
                  </tr>
                  <tr>
                    <th>Synthetic Data Flag</th>
                    <td>
                      {isSyntheticSme(dossierSme) ? (
                        <Badge tone="amber">YES (FS1 Synthetic Exercise Data)</Badge>
                      ) : (
                        <Badge tone="neutral">NO (Standard SME)</Badge>
                      )}
                    </td>
                    <th>Locked Currency</th>
                    <td>
                      <Badge tone="indigo">{dossierSme.currency}</Badge>
                    </td>
                  </tr>
                  <tr>
                    <th>Opening Date</th>
                    <td className="mono">
                      {dossierAnalysis?.params?.openingDate || dossierDraft.openingDate || '—'}
                    </td>
                    <th>Explicit Opening Amount</th>
                    <td className="tabular-nums">
                      {dossierAnalysis?.params?.openingAmount
                        ? formatMoneyDisplay(
                            dossierAnalysis.params.openingAmount,
                            dossierSme.currency,
                          )
                        : dossierDraft.openingAmount
                          ? formatMoneyDisplay(dossierDraft.openingAmount, dossierSme.currency)
                          : '— (Not yet confirmed)'}
                    </td>
                  </tr>
                  <tr>
                    <th>End / Cutoff Date</th>
                    <td className="mono">
                      {dossierAnalysis?.params?.endDate || dossierDraft.endDate || '—'}
                    </td>
                    <th>Warning Threshold</th>
                    <td className="tabular-nums">
                      {dossierAnalysis?.cashFlow?.warningThreshold != null
                        ? formatMoneyDisplay(
                            dossierAnalysis.cashFlow.warningThreshold,
                            dossierSme.currency,
                          )
                        : '—'}
                    </td>
                  </tr>
                  <tr>
                    <th>Forecast Model &amp; Version</th>
                    <td className="mono">
                      {dossierAnalysis?.forecast?.model
                        ? `${dossierAnalysis.forecast.model.name} (${dossierAnalysis.forecast.model.version || 'CM1'})`
                        : 'drift_calendar (CM1)'}
                    </td>
                    <th>Interval Method</th>
                    <td className="mono">
                      {dossierAnalysis?.forecast?.model?.intervalMethod ||
                        'split_conformal_past_rolling_residuals'}
                    </td>
                  </tr>
                  <tr>
                    <th>History Days</th>
                    <td className="tabular-nums">
                      {dossierAnalysis?.forecast?.historyDays ??
                        dossierAnalysis?.cashFlow?.days?.length ??
                        '—'}
                    </td>
                    <th>Audited Liquidity Warnings</th>
                    <td className="tabular-nums">
                      {dossierIndicators?.warningsCount != null
                        ? `${dossierIndicators.warningsCount} (${dossierIndicators.shortfallCount} shortfall, ${dossierIndicators.bufferCount} buffer)`
                        : '—'}
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>

          <DisclaimerBanner
            title="Deterministic Cash-Flow Disclaimer"
            text={CASH_FLOW_DISCLAIMER}
          />

          {dossierAnalysis?.forecast?.disclaimer && (
            <DisclaimerBanner
              title="Conditional Forecasting Disclaimer"
              text={dossierAnalysis.forecast.disclaimer}
            />
          )}

          <div className="grid-2">
            <div>
              <h3 className="card-title" style={{ fontSize: 14, marginBottom: 10 }}>
                <CheckSquare size={15} color="var(--teal-700)" />
                Structured Analyst Review Checklist
              </h3>
              <div className="section-stack" style={{ gap: 8 }}>
                {ANALYST_CHECKLIST_ITEMS.map((item) => {
                  const checked = Boolean(dossierChecklist[item.id]);
                  return (
                    <label key={item.id} className="checklist-item checkbox-label">
                      <input
                        type="checkbox"
                        checked={checked}
                        onChange={() => toggleChecklistItem(dossierSme.id, item.id)}
                      />
                      <span>{item.label}</span>
                    </label>
                  );
                })}
              </div>
            </div>

            <div className="field">
              <label className="card-title" htmlFor="bankpilot-analyst-notes" style={{ fontSize: 14, marginBottom: 4 }}>
                Analyst Review Notes
              </label>
              <textarea
                id="bankpilot-analyst-notes"
                className="textarea"
                rows={8}
                placeholder="Document manual statement verification, invoice mismatch follow-up, or conformal interval sensitivity notes..."
                value={dossierNotes}
                onChange={(e) => updateAnalystNotes(dossierSme.id, e.target.value)}
              />
              <span className="field-hint">
                Human-authored notes are included in the printed brief and exported Markdown dossier.
              </span>
            </div>
          </div>
        </section>
      )}
    </div>
  );
}
