import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  AlertTriangle,
  Download,
  GitBranch,
  Play,
  Plus,
  RefreshCw,
  Search,
  Trash2,
} from 'lucide-react';
import {
  createScenario,
  deleteScenario,
  getInvoiceSummary,
  listScenarios,
  simulateScenario,
} from '../api/finsightApi';
import {
  compareMoneyStrings,
  exportCsv,
  formatMoneyDisplay,
  isValidIsoDate,
  validateMoneyInput,
} from '../utils/money';
import {
  ApiErrorAlert,
  Badge,
  DisclaimerBanner,
  EmptyState,
  LoadingSkeleton,
} from './Common';
import { ScenarioComparisonChart } from './ScenarioComparisonChart';

const PAGE_SIZE = 25;

const KIND_BADGE_TONE = {
  BASELINE: 'neutral',
  LATE_PAYMENT: 'amber',
  NEW_CONTRACT: 'teal',
};

function makeEmptyLateRow(id) {
  return { id, invoiceId: '', delayDays: '14' };
}

function makeEmptyContractRow(id, defaultDate = '') {
  return { id, amount: '', expectedDate: defaultDate };
}

function formatScenarioInputsSummary(scenario, currency, invoiceMap) {
  const inputs = Array.isArray(scenario?.inputs) ? scenario.inputs : [];
  if (scenario?.kind === 'BASELINE' || inputs.length === 0) {
    return 'Baseline comparison (no shock adjustments)';
  }
  if (scenario.kind === 'LATE_PAYMENT') {
    return inputs
      .map((inp) => {
        const inv = invoiceMap.get(Number(inp.invoiceId));
        const refLabel = inv?.reference ? ` (${inv.reference})` : '';
        return `Invoice #${inp.invoiceId}${refLabel} +${inp.delayDays}d`;
      })
      .join(' · ');
  }
  if (scenario.kind === 'NEW_CONTRACT') {
    return inputs
      .map(
        (inp) =>
          `${formatMoneyDisplay(inp.amount, currency, { showSign: true })} on ${inp.expectedDate}`,
      )
      .join(' · ');
  }
  return `${inputs.length} input(s)`;
}

export function ScenarioSimulatorView({ sme, params }) {
  const [scenarios, setScenarios] = useState([]);
  const [scenariosLoading, setScenariosLoading] = useState(false);
  const [scenariosError, setScenariosError] = useState(null);

  const [invoiceAsOfDate, setInvoiceAsOfDate] = useState(params?.endDate || '2025-12-31');
  const [invoiceSummary, setInvoiceSummary] = useState(null);
  const [invoicesLoading, setInvoicesLoading] = useState(false);
  const [invoicesError, setInvoicesError] = useState(null);
  const [invoiceSearch, setInvoiceSearch] = useState('');
  const [openInvoicesOnly, setOpenInvoicesOnly] = useState(false);

  // Create Scenario Form State
  const nextRowIdRef = useRef(2);
  const [name, setName] = useState('');
  const [kind, setKind] = useState('LATE_PAYMENT');
  const [lateRows, setLateRows] = useState(() => [makeEmptyLateRow(1)]);
  const [contractRows, setContractRows] = useState(() => [
    makeEmptyContractRow(1, params?.openingDate || ''),
  ]);
  const [formErrors, setFormErrors] = useState({});
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState(null);

  // Saved Scenarios Actions
  const [selectedScenarioId, setSelectedScenarioId] = useState(null);
  const [confirmDeleteId, setConfirmDeleteId] = useState(null);
  const [deletingId, setDeletingId] = useState(null);
  const [deleteError, setDeleteError] = useState(null);

  // Simulation Results State
  const [simulatingId, setSimulatingId] = useState(null);
  const [simResult, setSimResult] = useState(null);
  const [simError, setSimError] = useState(null);
  const [showAllDays, setShowAllDays] = useState(false);
  const [page, setPage] = useState(1);

  const simAbortRef = useRef(null);

  const fetchScenarios = useCallback(
    async (signal) => {
      if (!sme?.id) return;
      setScenariosLoading(true);
      setScenariosError(null);
      try {
        const data = await listScenarios(sme.id, signal);
        if (signal?.aborted) return;
        setScenarios(Array.isArray(data) ? data : []);
      } catch (err) {
        if (err?.name !== 'AbortError') {
          setScenariosError(err);
        }
      } finally {
        if (!signal?.aborted) {
          setScenariosLoading(false);
        }
      }
    },
    [sme?.id],
  );

  const fetchInvoices = useCallback(
    async (asOfDate, signal) => {
      if (!sme?.id || !isValidIsoDate(asOfDate)) return;
      setInvoicesLoading(true);
      setInvoicesError(null);
      try {
        const data = await getInvoiceSummary(sme.id, { asOfDate }, signal);
        if (signal?.aborted) return;
        setInvoiceSummary(data);
      } catch (err) {
        if (err?.name !== 'AbortError') {
          setInvoicesError(err);
        }
      } finally {
        if (!signal?.aborted) {
          setInvoicesLoading(false);
        }
      }
    },
    [sme?.id],
  );

  // Sync invoiceAsOfDate default when params.endDate changes
  useEffect(() => {
    if (params?.endDate && isValidIsoDate(params.endDate)) {
      setInvoiceAsOfDate(params.endDate);
    }
  }, [params?.endDate]);

  // Reset per-SME state and load scenarios
  useEffect(() => {
    setSelectedScenarioId(null);
    setConfirmDeleteId(null);
    setDeleteError(null);
    setCreateError(null);
    setFormErrors({});
    setName('');
    setLateRows([makeEmptyLateRow(nextRowIdRef.current++)]);
    setContractRows([makeEmptyContractRow(nextRowIdRef.current++, params?.openingDate || '')]);
    setSimResult(null);
    setSimError(null);
    setShowAllDays(false);
    setPage(1);

    if (!sme?.id) {
      setScenarios([]);
      setInvoiceSummary(null);
      return undefined;
    }

    const controller = new AbortController();
    fetchScenarios(controller.signal);
    return () => controller.abort();
  }, [sme?.id, fetchScenarios]);

  // Clear stale simulation results if explicit opening params change
  useEffect(() => {
    setSimResult(null);
    setSimError(null);
    setPage(1);
    if (simAbortRef.current) {
      simAbortRef.current.abort();
      simAbortRef.current = null;
      setSimulatingId(null);
    }
  }, [params?.openingDate, params?.openingAmount, params?.currency, params?.endDate]);

  // Load invoices when SME or invoiceAsOfDate changes
  useEffect(() => {
    if (!sme?.id || !isValidIsoDate(invoiceAsOfDate)) return undefined;
    const controller = new AbortController();
    fetchInvoices(invoiceAsOfDate, controller.signal);
    return () => controller.abort();
  }, [sme?.id, invoiceAsOfDate, fetchInvoices]);

  const invoices = useMemo(() => {
    const raw = Array.isArray(invoiceSummary?.invoices) ? invoiceSummary.invoices : [];
    return [...raw].sort((a, b) => {
      const aOpen = compareMoneyStrings(a.outstanding || '0.00', '0.00') > 0 ? 0 : 1;
      const bOpen = compareMoneyStrings(b.outstanding || '0.00', '0.00') > 0 ? 0 : 1;
      if (aOpen !== bOpen) return aOpen - bOpen;
      return Number(a.id) - Number(b.id);
    });
  }, [invoiceSummary]);

  const invoiceMap = useMemo(() => {
    const map = new Map();
    for (const inv of invoices) {
      map.set(Number(inv.id), inv);
    }
    return map;
  }, [invoices]);

  const filteredInvoices = useMemo(() => {
    const q = invoiceSearch.trim().toLowerCase();
    return invoices.filter((inv) => {
      if (openInvoicesOnly && compareMoneyStrings(inv.outstanding || '0.00', '0.00') <= 0) {
        return false;
      }
      if (!q) return true;
      return (
        String(inv.id).includes(q) ||
        String(inv.reference || '')
          .toLowerCase()
          .includes(q) ||
        String(inv.direction || '')
          .toLowerCase()
          .includes(q) ||
        String(inv.effectiveStatus || '')
          .toLowerCase()
          .includes(q) ||
        String(inv.dueDate || '').includes(q)
      );
    });
  }, [invoices, invoiceSearch, openInvoicesOnly]);

  const addLateRow = () => {
    setLateRows((prev) => [...prev, makeEmptyLateRow(nextRowIdRef.current++)]);
  };

  const removeLateRow = (rowId) => {
    setLateRows((prev) => (prev.length > 1 ? prev.filter((r) => r.id !== rowId) : prev));
    setFormErrors({});
  };

  const updateLateRow = (rowId, field, value) => {
    setLateRows((prev) => prev.map((r) => (r.id === rowId ? { ...r, [field]: value } : r)));
    setFormErrors((prev) => ({ ...prev, rows: undefined, inputs: undefined }));
  };

  const addContractRow = () => {
    setContractRows((prev) => [
      ...prev,
      makeEmptyContractRow(nextRowIdRef.current++, params?.openingDate || ''),
    ]);
  };

  const removeContractRow = (rowId) => {
    setContractRows((prev) => (prev.length > 1 ? prev.filter((r) => r.id !== rowId) : prev));
    setFormErrors({});
  };

  const updateContractRow = (rowId, field, value) => {
    setContractRows((prev) => prev.map((r) => (r.id === rowId ? { ...r, [field]: value } : r)));
    setFormErrors((prev) => ({ ...prev, rows: undefined, inputs: undefined }));
  };

  const handleCreateSubmit = async (e) => {
    e.preventDefault();
    if (!sme?.id) return;

    const errors = {};
    const trimmedName = name.trim();
    if (!trimmedName) {
      errors.name = 'Scenario name is required.';
    }

    let normalizedInputs = [];

    if (kind === 'BASELINE') {
      normalizedInputs = [];
    } else if (kind === 'LATE_PAYMENT') {
      if (lateRows.length === 0) {
        errors.inputs = 'At least one invoice delay adjustment is required.';
      } else {
        const rowErrors = {};
        const seenInvoiceIds = new Set();
        const parsedRows = [];

        lateRows.forEach((row, idx) => {
          const rErr = {};
          const invRaw = String(row.invoiceId ?? '').trim();
          const invNum = /^\d+$/.test(invRaw) ? Number(invRaw) : NaN;
          if (!Number.isSafeInteger(invNum) || invNum <= 0) {
            rErr.invoiceId = 'Select an invoice or enter a positive integer ID.';
          } else if (seenInvoiceIds.has(invNum)) {
            rErr.invoiceId = `Duplicate invoice #${invNum} in scenario.`;
          } else {
            seenInvoiceIds.add(invNum);
          }

          const delayRaw = String(row.delayDays ?? '').trim();
          const delayNum = /^\d+$/.test(delayRaw) ? Number(delayRaw) : NaN;
          if (!Number.isInteger(delayNum) || delayNum < 1 || delayNum > 3650) {
            rErr.delayDays = 'Delay days must be an integer between 1 and 3650.';
          }

          if (Object.keys(rErr).length > 0) {
            rowErrors[idx] = rErr;
          } else {
            parsedRows.push({
              invoiceId: invNum,
              delayDays: delayNum,
              amount: null,
              expectedDate: null,
            });
          }
        });

        if (Object.keys(rowErrors).length > 0) {
          errors.rows = rowErrors;
        } else {
          normalizedInputs = parsedRows;
        }
      }
    } else if (kind === 'NEW_CONTRACT') {
      if (contractRows.length === 0) {
        errors.inputs = 'At least one new contract inflow is required.';
      } else {
        const rowErrors = {};
        const parsedRows = [];

        contractRows.forEach((row, idx) => {
          const rErr = {};
          const moneyCheck = validateMoneyInput(row.amount, {
            allowNegative: false,
            allowZero: false,
          });
          if (!moneyCheck.valid) {
            rErr.amount = moneyCheck.error;
          }

          const dateTrimmed = String(row.expectedDate ?? '').trim();
          if (!isValidIsoDate(dateTrimmed)) {
            rErr.expectedDate = 'Valid expected date (YYYY-MM-DD) is required.';
          }

          if (Object.keys(rErr).length > 0) {
            rowErrors[idx] = rErr;
          } else {
            parsedRows.push({
              invoiceId: null,
              delayDays: null,
              amount: moneyCheck.normalized,
              expectedDate: dateTrimmed,
            });
          }
        });

        if (Object.keys(rowErrors).length > 0) {
          errors.rows = rowErrors;
        } else {
          normalizedInputs = parsedRows;
        }
      }
    }

    if (Object.keys(errors).length > 0) {
      setFormErrors(errors);
      return;
    }

    setFormErrors({});
    setCreateError(null);
    setCreating(true);
    try {
      const created = await createScenario(sme.id, {
        name: trimmedName,
        kind,
        inputs: normalizedInputs,
      });
      await fetchScenarios();
      setName('');
      setLateRows([makeEmptyLateRow(nextRowIdRef.current++)]);
      setContractRows([makeEmptyContractRow(nextRowIdRef.current++, params?.openingDate || '')]);
      if (created?.id) {
        setSelectedScenarioId(created.id);
      }
    } catch (err) {
      setCreateError(err);
    } finally {
      setCreating(false);
    }
  };

  const handleDeleteScenario = async (scenarioId) => {
    if (!sme?.id) return;
    setDeletingId(scenarioId);
    setDeleteError(null);
    try {
      await deleteScenario(sme.id, scenarioId);
      setConfirmDeleteId(null);
      if (selectedScenarioId === scenarioId) {
        setSelectedScenarioId(null);
      }
      if (simResult?.scenario?.id === scenarioId) {
        setSimResult(null);
      }
      await fetchScenarios();
    } catch (err) {
      setDeleteError(err);
    } finally {
      setDeletingId(null);
    }
  };

  const handleSimulate = async (scenario) => {
    if (!sme?.id || !params || !scenario?.id) return;
    if (simAbortRef.current) {
      simAbortRef.current.abort();
    }
    const controller = new AbortController();
    simAbortRef.current = controller;

    setSelectedScenarioId(scenario.id);
    setSimulatingId(scenario.id);
    setSimError(null);

    try {
      const result = await simulateScenario(
        sme.id,
        scenario.id,
        {
          openingDate: params.openingDate,
          openingAmount: params.openingAmount,
          currency: params.currency,
          endDate: params.endDate,
        },
        controller.signal,
      );
      if (controller.signal.aborted) return;
      setSimResult(result);
      setShowAllDays(false);
      setPage(1);
    } catch (err) {
      if (err?.name !== 'AbortError') {
        setSimError(err);
      }
    } finally {
      if (!controller.signal.aborted) {
        setSimulatingId(null);
      }
    }
  };

  const allDays = useMemo(
    () => (Array.isArray(simResult?.days) ? simResult.days : []),
    [simResult],
  );

  const changedDays = useMemo(
    () => allDays.filter((d) => compareMoneyStrings(d.closingBalanceDelta, '0.00') !== 0),
    [allDays],
  );

  const displayedDays = showAllDays ? allDays : changedDays;
  const totalPages = Math.max(1, Math.ceil(displayedDays.length / PAGE_SIZE));
  const safePage = Math.min(page, totalPages);
  const paginatedDays = useMemo(() => {
    const start = (safePage - 1) * PAGE_SIZE;
    return displayedDays.slice(start, start + PAGE_SIZE);
  }, [displayedDays, safePage]);

  const handleExportCsv = () => {
    if (!simResult || displayedDays.length === 0) return;
    const headers = [
      'Date',
      'Baseline Inflow',
      'Scenario Inflow',
      'Baseline Outflow',
      'Scenario Outflow',
      'Baseline Closing Balance',
      'Scenario Closing Balance',
      'Closing Balance Delta',
    ];
    const rows = displayedDays.map((d) => [
      d.date,
      d.baselineInflow,
      d.scenarioInflow,
      d.baselineOutflow,
      d.scenarioOutflow,
      d.baselineClosingBalance,
      d.scenarioClosingBalance,
      d.closingBalanceDelta,
    ]);
    exportCsv(
      `finsight-scenario-${sme.id}-${simResult.scenario?.id || 'sim'}-${simResult.opening?.date || ''}-${simResult.endDate || ''}.csv`,
      headers,
      rows,
    );
  };

  if (!sme) {
    return (
      <EmptyState
        icon={GitBranch}
        title="Select an SME to run scenarios"
        description="Choose an SME in the control panel above to inspect saved scenarios, create what-if assumptions, and compare cash-flow outcomes."
      />
    );
  }

  const currency = simResult?.opening?.currency || params?.currency || sme.currency || 'USD';
  const minDeltaCmp = simResult
    ? compareMoneyStrings(simResult.minClosingBalanceDelta, '0.00')
    : 0;
  const baseWarningsCount = Array.isArray(simResult?.baselineWarnings)
    ? simResult.baselineWarnings.length
    : 0;
  const scenWarningsCount = Array.isArray(simResult?.scenarioWarnings)
    ? simResult.scenarioWarnings.length
    : 0;
  const warningDiff = scenWarningsCount - baseWarningsCount;

  return (
    <div className="scenario-split">
      {/* Left Column: Create Scenario Form + Saved Scenarios List */}
      <div className="section-stack">
        <section className="card" aria-label="Create Scenario">
          <div className="card-header">
            <div>
              <h2 className="card-title">
                <Plus size={16} color="var(--accent-primary)" />
                Create Scenario
              </h2>
              <p className="card-subtitle">
                Define deterministic what-if assumptions for {sme.name} ({sme.currency}).
              </p>
            </div>
          </div>

          {createError && (
            <div style={{ marginBottom: 12 }}>
              <ApiErrorAlert error={createError} title="Unable to create scenario" />
            </div>
          )}

          <form onSubmit={handleCreateSubmit} className="section-stack" noValidate>
            <div className="field">
              <label className="field-label" htmlFor="scenario-name">
                Scenario Name
              </label>
              <input
                id="scenario-name"
                type="text"
                className={`input ${formErrors.name ? 'input-error' : ''}`}
                placeholder="e.g. Key receivable delayed 14 days"
                value={name}
                onChange={(e) => {
                  setName(e.target.value);
                  if (formErrors.name) {
                    setFormErrors((prev) => ({ ...prev, name: undefined }));
                  }
                }}
              />
              {formErrors.name && <span className="field-error">{formErrors.name}</span>}
            </div>

            <div className="field">
              <label className="field-label" htmlFor="scenario-kind">
                Scenario Kind
              </label>
              <select
                id="scenario-kind"
                className="select"
                value={kind}
                onChange={(e) => {
                  setKind(e.target.value);
                  setFormErrors({});
                  setCreateError(null);
                }}
              >
                <option value="LATE_PAYMENT">LATE_PAYMENT — Delay existing SME invoice(s)</option>
                <option value="NEW_CONTRACT">NEW_CONTRACT — Add expected cash inflow(s)</option>
                <option value="BASELINE">BASELINE — Compare baseline with no shocks</option>
              </select>
            </div>

            {kind === 'BASELINE' && (
              <DisclaimerBanner
                title="Baseline Scenario"
                text="BASELINE uses an empty input list (inputs: []) and compares the ledger roll-forward with zero shock adjustments."
              />
            )}

            {kind === 'LATE_PAYMENT' && (
              <div className="section-stack" style={{ gap: 12 }}>
                <div
                  style={{
                    padding: '10px 12px',
                    borderRadius: 'var(--radius-sm)',
                    border: '1px solid var(--border-subtle)',
                    background: 'var(--surface-muted)',
                  }}
                >
                  <div className="toolbar" style={{ marginBottom: 8 }}>
                    <div className="field" style={{ flex: '1 1 130px' }}>
                      <label className="field-label" htmlFor="scenario-invoice-asof">
                        Invoice Picker As-Of Date
                      </label>
                      <input
                        id="scenario-invoice-asof"
                        type="date"
                        className="input"
                        value={invoiceAsOfDate}
                        onChange={(e) => setInvoiceAsOfDate(e.target.value)}
                      />
                    </div>
                    <div className="field" style={{ flex: '1.5 1 160px' }}>
                      <label className="field-label" htmlFor="scenario-invoice-search">
                        Filter Loaded Invoices
                      </label>
                      <div style={{ position: 'relative' }}>
                        <Search
                          size={14}
                          color="var(--slate-400)"
                          style={{ position: 'absolute', left: 10, top: 10, pointerEvents: 'none' }}
                        />
                        <input
                          id="scenario-invoice-search"
                          type="text"
                          className="input"
                          style={{ paddingLeft: 30 }}
                          placeholder="ID, ref, status, due date…"
                          value={invoiceSearch}
                          onChange={(e) => setInvoiceSearch(e.target.value)}
                        />
                      </div>
                    </div>
                  </div>
                  <div className="toolbar-inline" style={{ justifyContent: 'space-between' }}>
                    <label className="checkbox-label">
                      <input
                        type="checkbox"
                        checked={openInvoicesOnly}
                        onChange={(e) => setOpenInvoicesOnly(e.target.checked)}
                      />
                      Open / unpaid invoices only
                    </label>
                    <span className="field-hint">
                      {invoicesLoading
                        ? 'Loading invoices…'
                        : `${filteredInvoices.length} of ${invoices.length} invoice(s)`}
                    </span>
                  </div>
                  {invoicesError && (
                    <div style={{ marginTop: 8 }}>
                      <ApiErrorAlert
                        error={invoicesError}
                        title="Could not load invoice picker"
                        onRetry={() => fetchInvoices(invoiceAsOfDate)}
                      />
                    </div>
                  )}
                </div>

                {formErrors.inputs && <span className="field-error">{formErrors.inputs}</span>}

                {lateRows.map((row, idx) => {
                  const rowErr = formErrors.rows?.[idx] || {};
                  return (
                    <div key={row.id} className="scenario-item">
                      <div
                        style={{
                          display: 'flex',
                          alignItems: 'center',
                          justifyContent: 'space-between',
                          gap: 8,
                        }}
                      >
                        <strong style={{ fontSize: 12.5, color: 'var(--navy-800)' }}>
                          Delay Adjustment #{idx + 1}
                        </strong>
                        {lateRows.length > 1 && (
                          <button
                            type="button"
                            className="btn btn-danger btn-sm"
                            onClick={() => removeLateRow(row.id)}
                          >
                            Remove
                          </button>
                        )}
                      </div>

                      <div className="field">
                        <label
                          className="field-label"
                          htmlFor={`late-invoice-select-${row.id}`}
                        >
                          Select SME Invoice
                        </label>
                        <select
                          id={`late-invoice-select-${row.id}`}
                          className="select"
                          value={
                            filteredInvoices.some((inv) => String(inv.id) === String(row.invoiceId))
                              ? String(row.invoiceId)
                              : ''
                          }
                          onChange={(e) => updateLateRow(row.id, 'invoiceId', e.target.value)}
                        >
                          <option value="">— Choose from loaded SME invoices —</option>
                          {filteredInvoices.map((inv) => (
                            <option key={inv.id} value={inv.id}>
                              #{inv.id} · {inv.reference} · {inv.direction} · Due {inv.dueDate} ·
                              Outst. {formatMoneyDisplay(inv.outstanding, sme.currency)} (
                              {inv.effectiveStatus})
                            </option>
                          ))}
                        </select>
                      </div>

                      <div className="grid-2" style={{ gap: 10 }}>
                        <div className="field">
                          <label
                            className="field-label"
                            htmlFor={`late-invoice-id-${row.id}`}
                          >
                            Invoice ID (or manual)
                          </label>
                          <input
                            id={`late-invoice-id-${row.id}`}
                            type="number"
                            min="1"
                            step="1"
                            placeholder="e.g. 10"
                            className={`input tabular-nums ${rowErr.invoiceId ? 'input-error' : ''}`}
                            value={row.invoiceId}
                            onChange={(e) => updateLateRow(row.id, 'invoiceId', e.target.value)}
                          />
                          {rowErr.invoiceId && (
                            <span className="field-error">{rowErr.invoiceId}</span>
                          )}
                        </div>

                        <div className="field">
                          <label
                            className="field-label"
                            htmlFor={`late-delay-days-${row.id}`}
                          >
                            Delay Days (1–3650)
                          </label>
                          <input
                            id={`late-delay-days-${row.id}`}
                            type="number"
                            min="1"
                            max="3650"
                            step="1"
                            placeholder="14"
                            className={`input tabular-nums ${rowErr.delayDays ? 'input-error' : ''}`}
                            value={row.delayDays}
                            onChange={(e) => updateLateRow(row.id, 'delayDays', e.target.value)}
                          />
                          {rowErr.delayDays && (
                            <span className="field-error">{rowErr.delayDays}</span>
                          )}
                        </div>
                      </div>
                    </div>
                  );
                })}

                <button
                  type="button"
                  className="btn btn-secondary btn-sm"
                  onClick={addLateRow}
                  style={{ alignSelf: 'flex-start' }}
                >
                  <Plus size={14} />
                  Add Invoice Delay Row
                </button>
              </div>
            )}

            {kind === 'NEW_CONTRACT' && (
              <div className="section-stack" style={{ gap: 12 }}>
                {formErrors.inputs && <span className="field-error">{formErrors.inputs}</span>}

                {contractRows.map((row, idx) => {
                  const rowErr = formErrors.rows?.[idx] || {};
                  return (
                    <div key={row.id} className="scenario-item">
                      <div
                        style={{
                          display: 'flex',
                          alignItems: 'center',
                          justifyContent: 'space-between',
                          gap: 8,
                        }}
                      >
                        <strong style={{ fontSize: 12.5, color: 'var(--navy-800)' }}>
                          New Contract Inflow #{idx + 1}
                        </strong>
                        {contractRows.length > 1 && (
                          <button
                            type="button"
                            className="btn btn-danger btn-sm"
                            onClick={() => removeContractRow(row.id)}
                          >
                            Remove
                          </button>
                        )}
                      </div>

                      <div className="grid-2" style={{ gap: 10 }}>
                        <div className="field">
                          <label
                            className="field-label"
                            htmlFor={`contract-amount-${row.id}`}
                          >
                            Positive Amount ({sme.currency})
                          </label>
                          <input
                            id={`contract-amount-${row.id}`}
                            type="text"
                            inputMode="decimal"
                            placeholder="e.g. 2500.00"
                            className={`input tabular-nums ${rowErr.amount ? 'input-error' : ''}`}
                            value={row.amount}
                            onChange={(e) => updateContractRow(row.id, 'amount', e.target.value)}
                          />
                          {rowErr.amount && <span className="field-error">{rowErr.amount}</span>}
                        </div>

                        <div className="field">
                          <label
                            className="field-label"
                            htmlFor={`contract-date-${row.id}`}
                          >
                            Expected Date
                          </label>
                          <input
                            id={`contract-date-${row.id}`}
                            type="date"
                            className={`input ${rowErr.expectedDate ? 'input-error' : ''}`}
                            value={row.expectedDate}
                            onChange={(e) =>
                              updateContractRow(row.id, 'expectedDate', e.target.value)
                            }
                          />
                          {rowErr.expectedDate && (
                            <span className="field-error">{rowErr.expectedDate}</span>
                          )}
                        </div>
                      </div>
                    </div>
                  );
                })}

                <button
                  type="button"
                  className="btn btn-secondary btn-sm"
                  onClick={addContractRow}
                  style={{ alignSelf: 'flex-start' }}
                >
                  <Plus size={14} />
                  Add Contract Inflow Row
                </button>
              </div>
            )}

            <button type="submit" className="btn btn-primary" disabled={creating}>
              <Plus size={15} />
              {creating ? 'Creating Scenario…' : 'Save Scenario'}
            </button>
          </form>
        </section>

        <section className="card" aria-label="Saved Scenarios">
          <div className="card-header">
            <div>
              <h2 className="card-title">
                <GitBranch size={16} color="var(--accent-primary)" />
                Saved Scenarios ({scenarios.length})
              </h2>
              <p className="card-subtitle">
                Run non-mutating simulations against explicit opening cash parameters.
              </p>
            </div>
            <button
              type="button"
              className="btn btn-secondary btn-sm"
              onClick={() => fetchScenarios()}
              disabled={scenariosLoading}
            >
              <RefreshCw size={13} />
              Refresh
            </button>
          </div>

          {scenariosError && (
            <div style={{ marginBottom: 12 }}>
              <ApiErrorAlert
                error={scenariosError}
                title="Unable to load saved scenarios"
                onRetry={() => fetchScenarios()}
              />
            </div>
          )}

          {deleteError && (
            <div style={{ marginBottom: 12 }}>
              <ApiErrorAlert error={deleteError} title="Unable to delete scenario" />
            </div>
          )}

          {scenariosLoading && scenarios.length === 0 ? (
            <LoadingSkeleton rows={3} />
          ) : scenarios.length === 0 ? (
            <EmptyState
              icon={GitBranch}
              title="No saved scenarios yet"
              description="Create a BASELINE, LATE_PAYMENT, or NEW_CONTRACT scenario above to begin comparing outcomes."
            />
          ) : (
            <div className="section-stack" style={{ gap: 10 }}>
              {scenarios.map((scenario) => {
                const isActive =
                  selectedScenarioId === scenario.id || simResult?.scenario?.id === scenario.id;
                const isSimulating = simulatingId === scenario.id;
                const isConfirmingDelete = confirmDeleteId === scenario.id;
                const isDeleting = deletingId === scenario.id;

                return (
                  <div
                    key={scenario.id}
                    className={`scenario-item ${isActive ? 'active' : ''}`}
                  >
                    <div
                      style={{
                        display: 'flex',
                        alignItems: 'flex-start',
                        justifyContent: 'space-between',
                        gap: 8,
                      }}
                    >
                      <div style={{ minWidth: 0 }}>
                        <div style={{ fontWeight: 700, color: 'var(--navy-900)', fontSize: 13.5 }}>
                          {scenario.name}
                        </div>
                        <div className="field-hint" style={{ marginTop: 2 }}>
                          ID #{scenario.id}
                        </div>
                      </div>
                      <Badge tone={KIND_BADGE_TONE[scenario.kind] || 'neutral'}>
                        {scenario.kind}
                      </Badge>
                    </div>

                    <div
                      style={{
                        fontSize: 12.5,
                        color: 'var(--slate-600)',
                        lineHeight: 1.4,
                      }}
                    >
                      {formatScenarioInputsSummary(scenario, sme.currency, invoiceMap)}
                    </div>

                    <div className="btn-row" style={{ justifyContent: 'space-between', marginTop: 2 }}>
                      <button
                        type="button"
                        className="btn btn-primary btn-sm"
                        disabled={!params || Boolean(simulatingId)}
                        title={
                          !params
                            ? 'Apply explicit opening cash parameters above before simulating'
                            : `Simulate ${scenario.name}`
                        }
                        onClick={() => handleSimulate(scenario)}
                      >
                        <Play size={13} />
                        {isSimulating ? 'Simulating…' : 'Simulate'}
                      </button>

                      {isConfirmingDelete ? (
                        <div className="btn-row">
                          <button
                            type="button"
                            className="btn btn-danger btn-sm"
                            disabled={isDeleting}
                            onClick={() => handleDeleteScenario(scenario.id)}
                          >
                            {isDeleting ? 'Deleting…' : 'Confirm Delete'}
                          </button>
                          <button
                            type="button"
                            className="btn btn-secondary btn-sm"
                            disabled={isDeleting}
                            onClick={() => setConfirmDeleteId(null)}
                          >
                            Cancel
                          </button>
                        </div>
                      ) : (
                        <button
                          type="button"
                          className="btn btn-danger btn-sm"
                          disabled={Boolean(deletingId)}
                          onClick={() => {
                            setDeleteError(null);
                            setConfirmDeleteId(scenario.id);
                          }}
                        >
                          <Trash2 size={13} />
                          Delete
                        </button>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </section>
      </div>

      {/* Right Column: Baseline vs. Scenario Simulation Results */}
      <div className="section-stack">
        {!params && (
          <div className="banner banner-warning" role="note">
            <AlertTriangle size={18} className="banner-icon" aria-hidden="true" />
            <div className="banner-content">
              <p className="banner-title">Explicit Opening Cash Required to Simulate</p>
              <p className="banner-text">
                Apply explicit opening cash parameters (<code>openingDate</code>,{' '}
                <code>openingAmount</code>, <code>currency</code>, and <code>endDate</code>) in the
                control panel above before clicking <strong>Simulate</strong>.
              </p>
            </div>
          </div>
        )}

        {simError && (
          <ApiErrorAlert
            error={simError}
            title="Simulation Failed"
            onRetry={
              selectedScenarioId && params
                ? () => {
                    const target = scenarios.find((s) => s.id === selectedScenarioId);
                    if (target) handleSimulate(target);
                  }
                : undefined
            }
          />
        )}

        {simulatingId ? (
          <LoadingSkeleton rows={6} />
        ) : !simResult ? (
          <div className="card">
            <EmptyState
              icon={Play}
              title={
                params
                  ? 'Select a scenario and click Simulate'
                  : 'Apply opening cash above to run simulations'
              }
              description={
                params
                  ? `Simulations compare baseline cash flow (${params.openingDate} → ${params.endDate}, opening ${formatMoneyDisplay(params.openingAmount, params.currency)}) against your chosen what-if assumptions without modifying ledger records.`
                  : 'You can create and organize saved scenarios on the left right away. Once opening cash is applied above, click Simulate on any scenario to inspect daily deltas and chart comparisons.'
              }
            />
          </div>
        ) : (
          <>
            <DisclaimerBanner
              title="Conditional Simulation Notice (Read-Only, Non-Mutating)"
              text={simResult.disclaimer}
            />

            {/* Impact Summary KPI Cards */}
            <section className="kpi-grid" aria-label="Simulation Impact Summary">
              <div className="kpi-card">
                <span className="kpi-label">Baseline Min Closing</span>
                <span className="kpi-value tabular-nums">
                  {formatMoneyDisplay(simResult.baselineMinClosingBalance?.amount, currency)}
                </span>
                <span className="kpi-meta tabular-nums">
                  Date: {simResult.baselineMinClosingBalance?.date || '—'}
                </span>
              </div>

              <div className="kpi-card">
                <span className="kpi-label">Scenario Min Closing</span>
                <span className="kpi-value tabular-nums">
                  {formatMoneyDisplay(simResult.scenarioMinClosingBalance?.amount, currency)}
                </span>
                <span className="kpi-meta tabular-nums">
                  Date: {simResult.scenarioMinClosingBalance?.date || '—'}
                </span>
              </div>

              <div className="kpi-card">
                <span className="kpi-label">Min Closing Delta</span>
                <span
                  className={`kpi-value tabular-nums ${
                    minDeltaCmp > 0 ? 'positive' : minDeltaCmp < 0 ? 'negative' : ''
                  }`}
                >
                  {formatMoneyDisplay(simResult.minClosingBalanceDelta, currency, {
                    showSign: true,
                  })}
                </span>
                <span className="kpi-meta">
                  {minDeltaCmp > 0
                    ? 'Improves minimum balance'
                    : minDeltaCmp < 0
                      ? 'Reduces minimum balance'
                      : 'No change in minimum balance'}
                </span>
              </div>

              <div className="kpi-card">
                <span className="kpi-label">Liquidity Warnings</span>
                <span
                  className={`kpi-value tabular-nums ${
                    warningDiff > 0 ? 'negative' : warningDiff < 0 ? 'positive' : ''
                  }`}
                >
                  {baseWarningsCount} → {scenWarningsCount}
                </span>
                <span className="kpi-meta tabular-nums">
                  Threshold: {formatMoneyDisplay(simResult.warningThreshold, currency)} (
                  {warningDiff > 0
                    ? `+${warningDiff} warning(s)`
                    : warningDiff < 0
                      ? `${warningDiff} warning(s)`
                      : 'no change'}
                  )
                </span>
              </div>
            </section>

            {/* Scenario Comparison Chart */}
            <section className="card" aria-label="Baseline vs Scenario Chart">
              <div className="card-header">
                <div>
                  <h3 className="card-title">
                    Baseline vs. {simResult.scenario?.name || 'Scenario'}
                  </h3>
                  <p className="card-subtitle tabular-nums">
                    Window: {simResult.opening?.date} → {simResult.endDate} · Opening:{' '}
                    {formatMoneyDisplay(simResult.opening?.amount, currency)}
                  </p>
                </div>
                <Badge tone={KIND_BADGE_TONE[simResult.scenario?.kind] || 'neutral'}>
                  {simResult.scenario?.kind}
                </Badge>
              </div>

              <ScenarioComparisonChart
                days={allDays}
                warningThreshold={simResult.warningThreshold}
                currency={currency}
                scenarioName={simResult.scenario?.name || 'Scenario'}
              />
            </section>

            {/* Simulation Notes */}
            <section className="card" aria-label="Simulation Notes">
              <div className="card-header" style={{ marginBottom: 8 }}>
                <h3 className="card-title">Simulation Notes</h3>
              </div>
              {Array.isArray(simResult.notes) && simResult.notes.length > 0 ? (
                <ul style={{ margin: 0, paddingLeft: 18, color: 'var(--navy-800)' }}>
                  {simResult.notes.map((note, idx) => (
                    <li key={idx} style={{ marginBottom: 4 }}>
                      {note}
                    </li>
                  ))}
                </ul>
              ) : (
                <p className="field-hint" style={{ margin: 0 }}>
                  No additional simulation notes returned.
                </p>
              )}
            </section>

            {/* Daily Delta Table */}
            <section className="card" aria-label="Daily Delta Comparison Table">
              <div className="card-header">
                <div>
                  <h3 className="card-title">Daily Balance Comparison</h3>
                  <p className="card-subtitle tabular-nums">
                    {changedDays.length} of {allDays.length} day(s) have a non-zero closing balance
                    delta.
                  </p>
                </div>

                <div className="btn-row">
                  <button
                    type="button"
                    className={`btn btn-sm ${!showAllDays ? 'btn-primary' : 'btn-secondary'}`}
                    onClick={() => {
                      setShowAllDays(false);
                      setPage(1);
                    }}
                  >
                    Changed Days Only ({changedDays.length})
                  </button>
                  <button
                    type="button"
                    className={`btn btn-sm ${showAllDays ? 'btn-primary' : 'btn-secondary'}`}
                    onClick={() => {
                      setShowAllDays(true);
                      setPage(1);
                    }}
                  >
                    All Simulated Days ({allDays.length})
                  </button>
                  <button
                    type="button"
                    className="btn btn-secondary btn-sm"
                    disabled={displayedDays.length === 0}
                    onClick={handleExportCsv}
                  >
                    <Download size={13} />
                    Export CSV
                  </button>
                </div>
              </div>

              {displayedDays.length === 0 ? (
                <EmptyState
                  title="No changed days in this window"
                  description="Every simulated day has closingBalanceDelta = 0.00. Switch to 'All Simulated Days' to inspect the full roll-forward schedule."
                />
              ) : (
                <>
                  <div className="table-wrap">
                    <table className="data-table tabular-nums">
                      <thead>
                        <tr>
                          <th>Date</th>
                          <th className="num">Base Inflow</th>
                          <th className="num">Scen. Inflow</th>
                          <th className="num">Base Outflow</th>
                          <th className="num">Scen. Outflow</th>
                          <th className="num">Base Closing</th>
                          <th className="num">Scen. Closing</th>
                          <th className="num">Closing Delta</th>
                        </tr>
                      </thead>
                      <tbody>
                        {paginatedDays.map((day) => {
                          const deltaCmp = compareMoneyStrings(day.closingBalanceDelta, '0.00');
                          return (
                            <tr
                              key={day.date}
                              className={
                                deltaCmp < 0 ? 'row-warning' : deltaCmp > 0 ? 'row-selected' : ''
                              }
                            >
                              <td className="mono">{day.date}</td>
                              <td className="num">
                                {formatMoneyDisplay(day.baselineInflow, currency)}
                              </td>
                              <td className="num">
                                {formatMoneyDisplay(day.scenarioInflow, currency)}
                              </td>
                              <td className="num">
                                {formatMoneyDisplay(day.baselineOutflow, currency)}
                              </td>
                              <td className="num">
                                {formatMoneyDisplay(day.scenarioOutflow, currency)}
                              </td>
                              <td className="num">
                                {formatMoneyDisplay(day.baselineClosingBalance, currency)}
                              </td>
                              <td className="num" style={{ fontWeight: 600 }}>
                                {formatMoneyDisplay(day.scenarioClosingBalance, currency)}
                              </td>
                              <td
                                className="num"
                                style={{
                                  fontWeight: 700,
                                  color:
                                    deltaCmp > 0
                                      ? 'var(--green-700)'
                                      : deltaCmp < 0
                                        ? 'var(--red-700)'
                                        : 'var(--slate-500)',
                                }}
                              >
                                {formatMoneyDisplay(day.closingBalanceDelta, currency, {
                                  showSign: true,
                                })}
                              </td>
                            </tr>
                          );
                        })}
                      </tbody>
                    </table>
                  </div>

                  {totalPages > 1 && (
                    <div className="pagination-bar">
                      <span>
                        Showing {(safePage - 1) * PAGE_SIZE + 1}–
                        {Math.min(safePage * PAGE_SIZE, displayedDays.length)} of{' '}
                        {displayedDays.length} row(s)
                      </span>
                      <div className="btn-row">
                        <button
                          type="button"
                          className="btn btn-secondary btn-sm"
                          disabled={safePage <= 1}
                          onClick={() => setPage((p) => Math.max(1, p - 1))}
                        >
                          Previous
                        </button>
                        <span className="tabular-nums">
                          Page {safePage} of {totalPages}
                        </span>
                        <button
                          type="button"
                          className="btn btn-secondary btn-sm"
                          disabled={safePage >= totalPages}
                          onClick={() => setPage((p) => Math.min(totalPages, p + 1))}
                        >
                          Next
                        </button>
                      </div>
                    </div>
                  )}
                </>
              )}
            </section>
          </>
        )}
      </div>
    </div>
  );
}
