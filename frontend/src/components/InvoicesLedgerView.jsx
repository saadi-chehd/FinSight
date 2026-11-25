import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  AlertTriangle,
  ArrowDownLeft,
  ArrowUpRight,
  ChevronLeft,
  ChevronRight,
  Download,
  FileSpreadsheet,
  FlaskConical,
  Receipt,
  Search,
} from 'lucide-react';
import { getInvoiceSummary, getTransactions } from '../api/finsightApi';
import {
  compareMoneyStrings,
  exportCsv,
  formatMoneyDisplay,
  isSyntheticSme,
  isValidIsoDate,
  subtractMoneyStrings,
  sumMoneyStrings,
} from '../utils/money';
import {
  ApiErrorAlert,
  Badge,
  DisclaimerBanner,
  EmptyState,
  LoadingSkeleton,
} from './Common';

const PAGE_SIZE = 25;
const BUCKET_DIRECTIONS = ['INFLOW', 'OUTFLOW'];
const BUCKET_STATUSES = ['UNPAID', 'PARTIALLY_PAID', 'PAID'];

const INITIAL_INVOICE_FILTERS = {
  direction: 'ALL',
  effectiveStatus: 'ALL',
  overdueOnly: false,
  mismatchOnly: false,
  search: '',
};

const INITIAL_LEDGER_FILTERS = {
  direction: 'ALL',
  transactionType: 'ALL',
  search: '',
};

function statusTone(status) {
  switch (status) {
    case 'PAID':
      return 'green';
    case 'PARTIALLY_PAID':
      return 'amber';
    case 'UNPAID':
      return 'red';
    case 'CANCELLED':
      return 'neutral';
    default:
      return 'neutral';
  }
}

function directionTone(direction) {
  return direction === 'INFLOW' ? 'teal' : 'indigo';
}

function computeDirectionTotals(buckets, direction) {
  const matching = (buckets || []).filter((b) => b.direction === direction);
  return {
    count: matching.reduce((acc, b) => acc + Number(b.count || 0), 0),
    faceAmount: sumMoneyStrings(matching.map((b) => b.faceAmount)),
    paidToDate: sumMoneyStrings(matching.map((b) => b.paidToDate)),
    outstanding: sumMoneyStrings(matching.map((b) => b.outstanding)),
    overdueCount: matching.reduce((acc, b) => acc + Number(b.overdueCount || 0), 0),
    overdueOutstanding: sumMoneyStrings(matching.map((b) => b.overdueOutstanding)),
  };
}

function DirectionSummaryCard({ title, direction, totals, currency }) {
  const isInflow = direction === 'INFLOW';
  const hasOverdue = totals.overdueCount > 0 || compareMoneyStrings(totals.overdueOutstanding, '0.00') > 0;

  return (
    <div className="card" style={{ padding: '16px' }}>
      <div className="card-header" style={{ marginBottom: 12 }}>
        <div>
          <h4 className="card-title" style={{ fontSize: 14.5 }}>
            {isInflow ? <ArrowDownLeft size={16} color="var(--teal-600)" /> : <ArrowUpRight size={16} color="var(--indigo-600)" />}
            {title}
          </h4>
          <p className="card-subtitle">
            {isInflow ? 'Expected customer receipts (never netted against payables)' : 'Supplier obligations (never netted against receivables)'}
          </p>
        </div>
        <Badge tone={directionTone(direction)}>{direction}</Badge>
      </div>

      <div className="kpi-grid" style={{ gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', gap: 10 }}>
        <div className="kpi-card" style={{ padding: '10px 12px' }}>
          <span className="kpi-label">Total Invoices</span>
          <span className="kpi-value tabular-nums" style={{ fontSize: 17 }}>{totals.count}</span>
          <span className="kpi-meta">Overdue: {totals.overdueCount}</span>
        </div>

        <div className="kpi-card" style={{ padding: '10px 12px' }}>
          <span className="kpi-label">Face Amount</span>
          <span className="kpi-value tabular-nums" style={{ fontSize: 17 }}>
            {formatMoneyDisplay(totals.faceAmount, currency)}
          </span>
          <span className="kpi-meta">Paid: {formatMoneyDisplay(totals.paidToDate, currency)}</span>
        </div>

        <div className="kpi-card" style={{ padding: '10px 12px' }}>
          <span className="kpi-label">Outstanding</span>
          <span className="kpi-value tabular-nums" style={{ fontSize: 17 }}>
            {formatMoneyDisplay(totals.outstanding, currency)}
          </span>
        </div>

        <div className="kpi-card" style={{ padding: '10px 12px' }}>
          <span className="kpi-label">Overdue Outstanding</span>
          <span className={`kpi-value tabular-nums ${hasOverdue ? 'negative' : ''}`} style={{ fontSize: 17 }}>
            {formatMoneyDisplay(totals.overdueOutstanding, currency)}
          </span>
        </div>
      </div>
    </div>
  );
}

function PaginationBar({ page, totalPages, totalItems, itemLabel, onPageChange }) {
  if (totalItems === 0) return null;
  const start = (page - 1) * PAGE_SIZE + 1;
  const end = Math.min(page * PAGE_SIZE, totalItems);

  return (
    <div className="pagination-bar">
      <span className="tabular-nums">
        Showing {start}–{end} of {totalItems} {itemLabel} (Page {page} of {totalPages})
      </span>
      <div className="btn-row">
        <button
          type="button"
          className="btn btn-secondary btn-sm"
          onClick={() => onPageChange(Math.max(1, page - 1))}
          disabled={page <= 1}
        >
          <ChevronLeft size={14} />
          Previous
        </button>
        <button
          type="button"
          className="btn btn-secondary btn-sm"
          onClick={() => onPageChange(Math.min(totalPages, page + 1))}
          disabled={page >= totalPages}
        >
          Next
          <ChevronRight size={14} />
        </button>
      </div>
    </div>
  );
}

export function InvoicesLedgerView({ sme, defaultAsOfDate = '', defaultFrom = '', defaultTo = '' }) {
  // Section A state: Invoice Settlement & Overdue Aging
  const [asOfDate, setAsOfDate] = useState(() => defaultAsOfDate || '');
  const [asOfValidationError, setAsOfValidationError] = useState('');
  const [summary, setSummary] = useState(null);
  const [summaryLoading, setSummaryLoading] = useState(false);
  const [summaryError, setSummaryError] = useState(null);
  const [invoiceFilters, setInvoiceFilters] = useState(INITIAL_INVOICE_FILTERS);
  const [invoicePage, setInvoicePage] = useState(1);

  // Section B state: Recorded Transactions Ledger
  const [fromDate, setFromDate] = useState(() => defaultFrom || '');
  const [toDate, setToDate] = useState(() => defaultTo || '');
  const [ledgerValidationError, setLedgerValidationError] = useState('');
  const [ledger, setLedger] = useState(null);
  const [ledgerLoading, setLedgerLoading] = useState(false);
  const [ledgerError, setLedgerError] = useState(null);
  const [ledgerFilters, setLedgerFilters] = useState(INITIAL_LEDGER_FILTERS);
  const [ledgerPage, setLedgerPage] = useState(1);

  const summaryAbortRef = useRef(null);
  const ledgerAbortRef = useRef(null);
  const prevSmeIdRef = useRef(sme?.id ?? null);
  const prevDefaultsRef = useRef({
    asOfDate: defaultAsOfDate,
    from: defaultFrom,
    to: defaultTo,
  });

  const loadSummary = useCallback(
    async (targetAsOfDate) => {
      if (!sme?.id) return;
      const trimmed = String(targetAsOfDate ?? '').trim();
      if (!isValidIsoDate(trimmed)) {
        setAsOfValidationError('Enter a valid as-of date (YYYY-MM-DD) before loading invoice summary.');
        return;
      }

      if (summaryAbortRef.current) {
        summaryAbortRef.current.abort();
      }
      const controller = new AbortController();
      summaryAbortRef.current = controller;

      setAsOfValidationError('');
      setSummaryLoading(true);
      setSummaryError(null);
      try {
        const data = await getInvoiceSummary(sme.id, { asOfDate: trimmed }, controller.signal);
        if (controller.signal.aborted) return;
        setSummary(data);
        setInvoicePage(1);
      } catch (err) {
        if (err?.name !== 'AbortError') {
          setSummaryError(err);
        }
      } finally {
        if (!controller.signal.aborted) {
          setSummaryLoading(false);
        }
      }
    },
    [sme?.id],
  );

  const loadLedger = useCallback(
    async (targetFrom, targetTo) => {
      if (!sme?.id) return;
      const cleanFrom = String(targetFrom ?? '').trim();
      const cleanTo = String(targetTo ?? '').trim();

      if (cleanFrom && !isValidIsoDate(cleanFrom)) {
        setLedgerValidationError('From date must be a valid YYYY-MM-DD date.');
        return;
      }
      if (cleanTo && !isValidIsoDate(cleanTo)) {
        setLedgerValidationError('To date must be a valid YYYY-MM-DD date.');
        return;
      }
      if (cleanFrom && cleanTo && cleanTo < cleanFrom) {
        setLedgerValidationError('To date must be on or after From date.');
        return;
      }

      if (ledgerAbortRef.current) {
        ledgerAbortRef.current.abort();
      }
      const controller = new AbortController();
      ledgerAbortRef.current = controller;

      setLedgerValidationError('');
      setLedgerLoading(true);
      setLedgerError(null);
      try {
        const data = await getTransactions(
          sme.id,
          { from: cleanFrom || undefined, to: cleanTo || undefined },
          controller.signal,
        );
        if (controller.signal.aborted) return;
        setLedger(data);
        setLedgerPage(1);
      } catch (err) {
        if (err?.name !== 'AbortError') {
          setLedgerError(err);
        }
      } finally {
        if (!controller.signal.aborted) {
          setLedgerLoading(false);
        }
      }
    },
    [sme?.id],
  );

  // Cleanup on unmount
  useEffect(() => {
    return () => {
      if (summaryAbortRef.current) summaryAbortRef.current.abort();
      if (ledgerAbortRef.current) ledgerAbortRef.current.abort();
    };
  }, []);

  // Reset state on SME switch & sync defaults when newly supplied
  useEffect(() => {
    const currentSmeId = sme?.id ?? null;
    const smeChanged = prevSmeIdRef.current !== currentSmeId;

    if (smeChanged) {
      prevSmeIdRef.current = currentSmeId;
      if (summaryAbortRef.current) summaryAbortRef.current.abort();
      if (ledgerAbortRef.current) ledgerAbortRef.current.abort();

      setSummary(null);
      setSummaryError(null);
      setAsOfValidationError('');
      setInvoiceFilters(INITIAL_INVOICE_FILTERS);
      setInvoicePage(1);

      setLedger(null);
      setLedgerError(null);
      setLedgerValidationError('');
      setLedgerFilters(INITIAL_LEDGER_FILTERS);
      setLedgerPage(1);

      setAsOfDate(defaultAsOfDate || '');
      setFromDate(defaultFrom || '');
      setToDate(defaultTo || '');
    } else {
      if (defaultAsOfDate && defaultAsOfDate !== prevDefaultsRef.current.asOfDate) {
        setAsOfDate((prev) => (!prev || prev === prevDefaultsRef.current.asOfDate ? defaultAsOfDate : prev));
      }
      if (defaultFrom && defaultFrom !== prevDefaultsRef.current.from) {
        setFromDate((prev) => (!prev || prev === prevDefaultsRef.current.from ? defaultFrom : prev));
      }
      if (defaultTo && defaultTo !== prevDefaultsRef.current.to) {
        setToDate((prev) => (!prev || prev === prevDefaultsRef.current.to ? defaultTo : prev));
      }
    }

    prevDefaultsRef.current = {
      asOfDate: defaultAsOfDate,
      from: defaultFrom,
      to: defaultTo,
    };
  }, [sme?.id, defaultAsOfDate, defaultFrom, defaultTo]);

  // Auto-load when explicit defaults are supplied from committed opening window
  useEffect(() => {
    if (!sme?.id) return;
    if (isValidIsoDate(defaultAsOfDate)) {
      loadSummary(defaultAsOfDate);
    }
  }, [sme?.id, defaultAsOfDate, loadSummary]);

  useEffect(() => {
    if (!sme?.id) return;
    if (isValidIsoDate(defaultFrom) && isValidIsoDate(defaultTo) && defaultTo >= defaultFrom) {
      loadLedger(defaultFrom, defaultTo);
    }
  }, [sme?.id, defaultFrom, defaultTo, loadLedger]);

  // Section A derived data
  const summaryCurrency = summary?.currency || sme?.currency || 'USD';

  const inflowTotals = useMemo(
    () => computeDirectionTotals(summary?.buckets, 'INFLOW'),
    [summary?.buckets],
  );
  const outflowTotals = useMemo(
    () => computeDirectionTotals(summary?.buckets, 'OUTFLOW'),
    [summary?.buckets],
  );

  const bucketRows = useMemo(() => {
    const map = new Map();
    for (const b of summary?.buckets || []) {
      map.set(`${b.direction}::${b.status}`, b);
    }
    const rows = [];
    for (const direction of BUCKET_DIRECTIONS) {
      for (const status of BUCKET_STATUSES) {
        const existing = map.get(`${direction}::${status}`);
        rows.push(
          existing || {
            direction,
            status,
            count: 0,
            faceAmount: '0.00',
            paidToDate: '0.00',
            outstanding: '0.00',
            overdueCount: 0,
            overdueOutstanding: '0.00',
          },
        );
      }
    }
    return rows;
  }, [summary?.buckets]);

  const filteredInvoices = useMemo(() => {
    const list = summary?.invoices || [];
    const q = invoiceFilters.search.trim().toLowerCase();
    return list.filter((inv) => {
      if (invoiceFilters.direction !== 'ALL' && inv.direction !== invoiceFilters.direction) {
        return false;
      }
      if (invoiceFilters.effectiveStatus !== 'ALL' && inv.effectiveStatus !== invoiceFilters.effectiveStatus) {
        return false;
      }
      if (invoiceFilters.overdueOnly && !inv.overdue) {
        return false;
      }
      if (invoiceFilters.mismatchOnly && !inv.statusMismatch) {
        return false;
      }
      if (q && !(inv.reference || '').toLowerCase().includes(q)) {
        return false;
      }
      return true;
    });
  }, [summary?.invoices, invoiceFilters]);

  const invoiceTotalPages = Math.max(1, Math.ceil(filteredInvoices.length / PAGE_SIZE));
  const safeInvoicePage = Math.min(invoicePage, invoiceTotalPages);
  const pagedInvoices = useMemo(() => {
    const start = (safeInvoicePage - 1) * PAGE_SIZE;
    return filteredInvoices.slice(start, start + PAGE_SIZE);
  }, [filteredInvoices, safeInvoicePage]);

  // Section B derived data
  const ledgerCurrency = ledger?.currency || sme?.currency || 'USD';

  const transactionTypes = useMemo(() => {
    const types = new Set();
    for (const tx of ledger?.transactions || []) {
      if (tx.transactionType) {
        types.add(tx.transactionType);
      }
    }
    return Array.from(types).sort();
  }, [ledger?.transactions]);

  const filteredTransactions = useMemo(() => {
    const list = ledger?.transactions || [];
    const q = ledgerFilters.search.trim().toLowerCase();
    return list.filter((tx) => {
      if (ledgerFilters.direction !== 'ALL' && tx.direction !== ledgerFilters.direction) {
        return false;
      }
      if (ledgerFilters.transactionType !== 'ALL' && tx.transactionType !== ledgerFilters.transactionType) {
        return false;
      }
      if (q) {
        const haystacks = [
          tx.description,
          tx.transactionType,
          tx.id != null ? String(tx.id) : '',
          tx.invoiceId != null ? String(tx.invoiceId) : '',
        ];
        if (!haystacks.some((val) => String(val ?? '').toLowerCase().includes(q))) {
          return false;
        }
      }
      return true;
    });
  }, [ledger?.transactions, ledgerFilters]);

  const ledgerKpis = useMemo(() => {
    const inflows = sumMoneyStrings(
      filteredTransactions.filter((t) => t.direction === 'INFLOW').map((t) => t.amount),
    );
    const outflows = sumMoneyStrings(
      filteredTransactions.filter((t) => t.direction === 'OUTFLOW').map((t) => t.amount),
    );
    const net = subtractMoneyStrings(inflows, outflows);
    return {
      count: filteredTransactions.length,
      inflows,
      outflows,
      net,
      netSign: compareMoneyStrings(net, '0.00'),
    };
  }, [filteredTransactions]);

  const ledgerTotalPages = Math.max(1, Math.ceil(filteredTransactions.length / PAGE_SIZE));
  const safeLedgerPage = Math.min(ledgerPage, ledgerTotalPages);
  const pagedTransactions = useMemo(() => {
    const start = (safeLedgerPage - 1) * PAGE_SIZE;
    return filteredTransactions.slice(start, start + PAGE_SIZE);
  }, [filteredTransactions, safeLedgerPage]);

  if (!sme) {
    return (
      <EmptyState
        icon={FileSpreadsheet}
        title="Select an SME to inspect invoices and ledger"
        description="Choose an SME from the context selector above to evaluate invoice settlement aging as of an explicit cutoff date and audit recorded ledger transactions."
      />
    );
  }

  const syntheticSme = isSyntheticSme(sme);

  const handleSummarySubmit = (e) => {
    e.preventDefault();
    loadSummary(asOfDate);
  };

  const handleUseFs1End = () => {
    const fs1End = '2025-12-31';
    setAsOfDate(fs1End);
    loadSummary(fs1End);
  };

  const handleExportInvoicesCsv = () => {
    if (!summary) return;
    const headers = [
      'id',
      'reference',
      'direction',
      'dueDate',
      'recordedStatus',
      'effectiveStatus',
      'statusMismatch',
      'overdue',
      'faceAmount',
      'paidToDate',
      'outstanding',
      'overpayment',
    ];
    const rows = filteredInvoices.map((inv) => [
      inv.id,
      inv.reference,
      inv.direction,
      inv.dueDate,
      inv.recordedStatus,
      inv.effectiveStatus,
      inv.statusMismatch ? 'true' : 'false',
      inv.overdue ? 'true' : 'false',
      inv.faceAmount,
      inv.paidToDate,
      inv.outstanding,
      inv.overpayment,
    ]);
    exportCsv(`sme-${sme.id}-invoices-${summary.asOfDate}.csv`, headers, rows);
  };

  const handleLedgerSubmit = (e) => {
    e.preventDefault();
    loadLedger(fromDate, toDate);
  };

  const handleClearLedgerDates = () => {
    setFromDate('');
    setToDate('');
    setLedgerValidationError('');
  };

  const handleExportLedgerCsv = () => {
    if (!ledger) return;
    const headers = [
      'id',
      'bookedDate',
      'direction',
      'transactionType',
      'invoiceId',
      'currency',
      'amount',
      'description',
    ];
    const rows = filteredTransactions.map((tx) => [
      tx.id,
      tx.bookedDate,
      tx.direction,
      tx.transactionType,
      tx.invoiceId ?? '',
      tx.currency || ledgerCurrency,
      tx.amount,
      tx.description ?? '',
    ]);
    const suffix = [ledger.from || 'start', ledger.to || 'end'].join('_to_');
    exportCsv(`sme-${sme.id}-transactions-${suffix}.csv`, headers, rows);
  };

  return (
    <div className="section-stack">
      {/* Section A: Invoice Settlement & Overdue Aging */}
      <section className="card section-stack" aria-label="Invoice Settlement and Overdue Aging">
        <div className="card-header" style={{ marginBottom: 0 }}>
          <div>
            <h3 className="card-title">
              <FileSpreadsheet size={18} color="var(--accent-primary)" />
              Invoice Settlement &amp; Overdue Aging
            </h3>
            <p className="card-subtitle">
              Recomputes invoice settlement status and overdue aging strictly as of an explicit cutoff date.
            </p>
          </div>
          {summary && (
            <div className="btn-row">
              <Badge tone="indigo">As Of: {summary.asOfDate}</Badge>
              <Badge tone="neutral">Currency: {summaryCurrency}</Badge>
            </div>
          )}
        </div>

        <form onSubmit={handleSummarySubmit} className="toolbar" noValidate>
          <div className="field" style={{ minWidth: 200 }}>
            <label className="field-label" htmlFor="invoice-as-of-date">
              As-Of Date (YYYY-MM-DD)
            </label>
            <input
              id="invoice-as-of-date"
              type="date"
              className={`input ${asOfValidationError ? 'input-error' : ''}`}
              value={asOfDate}
              onChange={(e) => {
                setAsOfDate(e.target.value);
                if (asOfValidationError) setAsOfValidationError('');
              }}
            />
            {asOfValidationError && <span className="field-error">{asOfValidationError}</span>}
          </div>

          <div className="btn-row">
            <button type="submit" className="btn btn-primary" disabled={summaryLoading}>
              Load Summary
            </button>
            {syntheticSme && (
              <button
                type="button"
                className="btn btn-preset"
                onClick={handleUseFs1End}
                disabled={summaryLoading}
              >
                <FlaskConical size={14} />
                Use 2025-12-31 (FS1 End)
              </button>
            )}
          </div>
        </form>

        {summaryError && (
          <ApiErrorAlert
            error={summaryError}
            title="Failed to load invoice summary"
            onRetry={() => loadSummary(asOfDate)}
          />
        )}

        {summaryLoading ? (
          <LoadingSkeleton rows={5} />
        ) : !summary ? (
          <EmptyState
            icon={FileSpreadsheet}
            title="Explicit As-Of Date Required"
            description="Enter an explicit YYYY-MM-DD cutoff date and click Load Summary to compute invoice settlement status and overdue aging. FinSight never fabricates aging results without an explicit as-of date."
          />
        ) : (
          <>
            <DisclaimerBanner
              title="Gross Reporting Policy"
              text="Receivables (INFLOW) and Payables (OUTFLOW) are never silently netted. Overdue counts and balances reflect invoices past dueDate with outstanding > 0.00 as of the selected cutoff date."
            />

            {/* Side-by-side Receivables and Payables Summary Cards */}
            <div className="grid-2">
              <DirectionSummaryCard
                title="Receivables Summary"
                direction="INFLOW"
                totals={inflowTotals}
                currency={summaryCurrency}
              />
              <DirectionSummaryCard
                title="Payables Summary"
                direction="OUTFLOW"
                totals={outflowTotals}
                currency={summaryCurrency}
              />
            </div>

            {/* 6-Bucket Breakdown Table */}
            <div>
              <div className="card-header" style={{ marginBottom: 10 }}>
                <div>
                  <h4 className="card-title" style={{ fontSize: 14.5 }}>
                    6-Bucket Settlement Breakdown
                  </h4>
                  <p className="card-subtitle">
                    INFLOW and OUTFLOW grouped by effective status (UNPAID, PARTIALLY_PAID, PAID).
                  </p>
                </div>
              </div>
              <div className="table-wrap">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>Direction</th>
                      <th>Effective Status</th>
                      <th className="num">Count</th>
                      <th className="num">Face Amount</th>
                      <th className="num">Paid To Date</th>
                      <th className="num">Outstanding</th>
                      <th className="num">Overdue Count</th>
                      <th className="num">Overdue Outstanding</th>
                    </tr>
                  </thead>
                  <tbody>
                    {bucketRows.map((b) => {
                      const hasOverdue =
                        Number(b.overdueCount || 0) > 0 ||
                        compareMoneyStrings(b.overdueOutstanding || '0.00', '0.00') > 0;
                      return (
                        <tr key={`${b.direction}-${b.status}`}>
                          <td>
                            <Badge tone={directionTone(b.direction)}>{b.direction}</Badge>
                          </td>
                          <td>
                            <Badge tone={statusTone(b.status)}>{b.status}</Badge>
                          </td>
                          <td className="num tabular-nums">{b.count}</td>
                          <td className="num tabular-nums">
                            {formatMoneyDisplay(b.faceAmount, summaryCurrency)}
                          </td>
                          <td className="num tabular-nums">
                            {formatMoneyDisplay(b.paidToDate, summaryCurrency)}
                          </td>
                          <td className="num tabular-nums">
                            {formatMoneyDisplay(b.outstanding, summaryCurrency)}
                          </td>
                          <td className="num tabular-nums">{b.overdueCount}</td>
                          <td
                            className="num tabular-nums"
                            style={hasOverdue ? { color: 'var(--red-700)', fontWeight: 600 } : undefined}
                          >
                            {formatMoneyDisplay(b.overdueOutstanding, summaryCurrency)}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            </div>

            {/* Filterable & Paginated Invoice Position Table */}
            <div>
              <div className="card-header" style={{ marginBottom: 10 }}>
                <div>
                  <h4 className="card-title" style={{ fontSize: 14.5 }}>
                    Invoice Positions ({filteredInvoices.length}
                    {filteredInvoices.length !== (summary.invoices?.length || 0)
                      ? ` of ${summary.invoices?.length || 0}`
                      : ''}
                    )
                  </h4>
                  <p className="card-subtitle">
                    Compares recorded database status against computed effective status as of {summary.asOfDate}.
                  </p>
                </div>
                <button
                  type="button"
                  className="btn btn-secondary btn-sm"
                  onClick={handleExportInvoicesCsv}
                  disabled={filteredInvoices.length === 0}
                >
                  <Download size={14} />
                  Export CSV
                </button>
              </div>

              <div className="toolbar">
                <div className="field" style={{ minWidth: 140 }}>
                  <label className="field-label" htmlFor="inv-filter-direction">
                    Direction
                  </label>
                  <select
                    id="inv-filter-direction"
                    className="select"
                    value={invoiceFilters.direction}
                    onChange={(e) => {
                      setInvoiceFilters((prev) => ({ ...prev, direction: e.target.value }));
                      setInvoicePage(1);
                    }}
                  >
                    <option value="ALL">ALL</option>
                    <option value="INFLOW">INFLOW</option>
                    <option value="OUTFLOW">OUTFLOW</option>
                  </select>
                </div>

                <div className="field" style={{ minWidth: 165 }}>
                  <label className="field-label" htmlFor="inv-filter-status">
                    Effective Status
                  </label>
                  <select
                    id="inv-filter-status"
                    className="select"
                    value={invoiceFilters.effectiveStatus}
                    onChange={(e) => {
                      setInvoiceFilters((prev) => ({ ...prev, effectiveStatus: e.target.value }));
                      setInvoicePage(1);
                    }}
                  >
                    <option value="ALL">ALL</option>
                    <option value="UNPAID">UNPAID</option>
                    <option value="PARTIALLY_PAID">PARTIALLY_PAID</option>
                    <option value="PAID">PAID</option>
                    <option value="CANCELLED">CANCELLED</option>
                  </select>
                </div>

                <div className="field" style={{ minWidth: 200, flex: '1 1 200px', maxWidth: 280 }}>
                  <label className="field-label" htmlFor="inv-filter-search">
                    Reference Search
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
                      id="inv-filter-search"
                      type="text"
                      className="input"
                      style={{ paddingLeft: 30 }}
                      placeholder="Search reference (e.g. INV-001)"
                      value={invoiceFilters.search}
                      onChange={(e) => {
                        setInvoiceFilters((prev) => ({ ...prev, search: e.target.value }));
                        setInvoicePage(1);
                      }}
                    />
                  </div>
                </div>

                <div className="toolbar-inline" style={{ paddingBottom: 6 }}>
                  <label className="checkbox-label">
                    <input
                      type="checkbox"
                      checked={invoiceFilters.overdueOnly}
                      onChange={(e) => {
                        setInvoiceFilters((prev) => ({ ...prev, overdueOnly: e.target.checked }));
                        setInvoicePage(1);
                      }}
                    />
                    Overdue Only
                  </label>
                  <label className="checkbox-label">
                    <input
                      type="checkbox"
                      checked={invoiceFilters.mismatchOnly}
                      onChange={(e) => {
                        setInvoiceFilters((prev) => ({ ...prev, mismatchOnly: e.target.checked }));
                        setInvoicePage(1);
                      }}
                    />
                    Status Mismatch Only
                  </label>
                </div>
              </div>

              {filteredInvoices.length === 0 ? (
                <EmptyState
                  icon={FileSpreadsheet}
                  title="No matching invoices"
                  description="No invoices match the current filter criteria for this as-of date."
                />
              ) : (
                <>
                  <div className="table-wrap">
                    <table className="data-table">
                      <thead>
                        <tr>
                          <th>Reference</th>
                          <th>Direction</th>
                          <th>Due Date</th>
                          <th>Recorded Status</th>
                          <th>Effective Status</th>
                          <th>Flags</th>
                          <th className="num">Face Amount</th>
                          <th className="num">Paid To Date</th>
                          <th className="num">Outstanding</th>
                          <th className="num">Overpayment</th>
                        </tr>
                      </thead>
                      <tbody>
                        {pagedInvoices.map((inv) => {
                          const hasOverpayment =
                            compareMoneyStrings(inv.overpayment || '0.00', '0.00') > 0;
                          const rowClass = inv.overdue
                            ? 'row-danger'
                            : inv.statusMismatch
                              ? 'row-warning'
                              : '';
                          return (
                            <tr key={inv.id} className={rowClass}>
                              <td className="mono" style={{ fontWeight: 600 }}>
                                {inv.reference}
                              </td>
                              <td>
                                <Badge tone={directionTone(inv.direction)}>{inv.direction}</Badge>
                              </td>
                              <td className="tabular-nums">{inv.dueDate}</td>
                              <td>
                                <Badge tone={statusTone(inv.recordedStatus)}>{inv.recordedStatus}</Badge>
                              </td>
                              <td>
                                <Badge tone={statusTone(inv.effectiveStatus)}>{inv.effectiveStatus}</Badge>
                              </td>
                              <td>
                                <div className="btn-row" style={{ gap: 4 }}>
                                  {inv.statusMismatch && (
                                    <Badge tone="amber">
                                      <AlertTriangle size={11} />
                                      Status Mismatch
                                    </Badge>
                                  )}
                                  {inv.overdue && <Badge tone="red">Overdue</Badge>}
                                  {!inv.statusMismatch && !inv.overdue && (
                                    <span style={{ color: 'var(--slate-400)' }}>—</span>
                                  )}
                                </div>
                              </td>
                              <td className="num tabular-nums">
                                {formatMoneyDisplay(inv.faceAmount, summaryCurrency)}
                              </td>
                              <td className="num tabular-nums">
                                {formatMoneyDisplay(inv.paidToDate, summaryCurrency)}
                              </td>
                              <td className="num tabular-nums">
                                {formatMoneyDisplay(inv.outstanding, summaryCurrency)}
                              </td>
                              <td className="num tabular-nums">
                                {hasOverpayment ? (
                                  <Badge tone="amber">
                                    {formatMoneyDisplay(inv.overpayment, summaryCurrency)}
                                  </Badge>
                                ) : (
                                  formatMoneyDisplay(inv.overpayment, summaryCurrency)
                                )}
                              </td>
                            </tr>
                          );
                        })}
                      </tbody>
                    </table>
                  </div>

                  <PaginationBar
                    page={safeInvoicePage}
                    totalPages={invoiceTotalPages}
                    totalItems={filteredInvoices.length}
                    itemLabel="invoices"
                    onPageChange={setInvoicePage}
                  />
                </>
              )}
            </div>
          </>
        )}
      </section>

      {/* Section B: Recorded Transactions Ledger */}
      <section className="card section-stack" aria-label="Recorded Transactions Ledger">
        <div className="card-header" style={{ marginBottom: 0 }}>
          <div>
            <h3 className="card-title">
              <Receipt size={18} color="var(--teal-600)" />
              Recorded Transactions Ledger
            </h3>
            <p className="card-subtitle">
              Immutable ledger cash movements optionally bounded by booked date (from / to inclusive).
            </p>
          </div>
          {ledger && (
            <div className="btn-row">
              <Badge tone="teal">
                Window: {ledger.from || 'Earliest'} → {ledger.to || 'Latest'}
              </Badge>
              <Badge tone="neutral">Currency: {ledgerCurrency}</Badge>
            </div>
          )}
        </div>

        <form onSubmit={handleLedgerSubmit} className="toolbar" noValidate>
          <div className="field" style={{ minWidth: 170 }}>
            <label className="field-label" htmlFor="ledger-from-date">
              From Date (Optional)
            </label>
            <input
              id="ledger-from-date"
              type="date"
              className={`input ${ledgerValidationError ? 'input-error' : ''}`}
              value={fromDate}
              onChange={(e) => {
                setFromDate(e.target.value);
                if (ledgerValidationError) setLedgerValidationError('');
              }}
            />
          </div>

          <div className="field" style={{ minWidth: 170 }}>
            <label className="field-label" htmlFor="ledger-to-date">
              To Date (Optional)
            </label>
            <input
              id="ledger-to-date"
              type="date"
              className={`input ${ledgerValidationError ? 'input-error' : ''}`}
              value={toDate}
              onChange={(e) => {
                setToDate(e.target.value);
                if (ledgerValidationError) setLedgerValidationError('');
              }}
            />
          </div>

          <div className="btn-row">
            <button type="submit" className="btn btn-primary" disabled={ledgerLoading}>
              Load Ledger
            </button>
            {(fromDate || toDate) && (
              <button
                type="button"
                className="btn btn-secondary"
                onClick={handleClearLedgerDates}
                disabled={ledgerLoading}
              >
                Clear Dates
              </button>
            )}
          </div>
        </form>
        {ledgerValidationError && (
          <div className="field-error" style={{ marginTop: -10 }}>
            {ledgerValidationError}
          </div>
        )}

        {ledgerError && (
          <ApiErrorAlert
            error={ledgerError}
            title="Failed to load transactions ledger"
            onRetry={() => loadLedger(fromDate, toDate)}
          />
        )}

        {ledgerLoading ? (
          <LoadingSkeleton rows={5} />
        ) : !ledger ? (
          <EmptyState
            icon={Receipt}
            title="Load Recorded Transactions"
            description="Optionally specify From / To dates (or leave both blank for full history) and click Load Ledger to inspect recorded inflows and outflows."
          />
        ) : (
          <>
            {/* Client-side Filters */}
            <div className="toolbar" style={{ marginBottom: 0 }}>
              <div className="field" style={{ minWidth: 140 }}>
                <label className="field-label" htmlFor="ledger-filter-direction">
                  Direction
                </label>
                <select
                  id="ledger-filter-direction"
                  className="select"
                  value={ledgerFilters.direction}
                  onChange={(e) => {
                    setLedgerFilters((prev) => ({ ...prev, direction: e.target.value }));
                    setLedgerPage(1);
                  }}
                >
                  <option value="ALL">ALL</option>
                  <option value="INFLOW">INFLOW</option>
                  <option value="OUTFLOW">OUTFLOW</option>
                </select>
              </div>

              <div className="field" style={{ minWidth: 200 }}>
                <label className="field-label" htmlFor="ledger-filter-type">
                  Transaction Type
                </label>
                <select
                  id="ledger-filter-type"
                  className="select"
                  value={ledgerFilters.transactionType}
                  onChange={(e) => {
                    setLedgerFilters((prev) => ({ ...prev, transactionType: e.target.value }));
                    setLedgerPage(1);
                  }}
                >
                  <option value="ALL">ALL</option>
                  {transactionTypes.map((type) => (
                    <option key={type} value={type}>
                      {type}
                    </option>
                  ))}
                </select>
              </div>

              <div className="field" style={{ minWidth: 220, flex: '1 1 240px', maxWidth: 340 }}>
                <label className="field-label" htmlFor="ledger-filter-search">
                  Search Description / Type / ID / Invoice
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
                    id="ledger-filter-search"
                    type="text"
                    className="input"
                    style={{ paddingLeft: 30 }}
                    placeholder="Filter description, type, ID, invoiceId..."
                    value={ledgerFilters.search}
                    onChange={(e) => {
                      setLedgerFilters((prev) => ({ ...prev, search: e.target.value }));
                      setLedgerPage(1);
                    }}
                  />
                </div>
              </div>

              <div style={{ marginLeft: 'auto' }}>
                <button
                  type="button"
                  className="btn btn-secondary btn-sm"
                  onClick={handleExportLedgerCsv}
                  disabled={filteredTransactions.length === 0}
                >
                  <Download size={14} />
                  Export CSV
                </button>
              </div>
            </div>

            {/* Exact BigInt KPIs over filtered transactions */}
            <div className="kpi-grid">
              <div className="kpi-card">
                <span className="kpi-label">Filtered Count</span>
                <span className="kpi-value tabular-nums">{ledgerKpis.count}</span>
                <span className="kpi-meta">
                  Of {ledger.transactions?.length || 0} loaded
                </span>
              </div>

              <div className="kpi-card">
                <span className="kpi-label">Filtered Inflows</span>
                <span className="kpi-value positive tabular-nums">
                  {formatMoneyDisplay(ledgerKpis.inflows, ledgerCurrency)}
                </span>
                <span className="kpi-meta">Exact BigInt sum</span>
              </div>

              <div className="kpi-card">
                <span className="kpi-label">Filtered Outflows</span>
                <span className="kpi-value negative tabular-nums">
                  {formatMoneyDisplay(ledgerKpis.outflows, ledgerCurrency)}
                </span>
                <span className="kpi-meta">Exact BigInt sum</span>
              </div>

              <div className="kpi-card">
                <span className="kpi-label">Filtered Net</span>
                <span
                  className={`kpi-value tabular-nums ${
                    ledgerKpis.netSign > 0 ? 'positive' : ledgerKpis.netSign < 0 ? 'negative' : ''
                  }`}
                >
                  {formatMoneyDisplay(ledgerKpis.net, ledgerCurrency, { showSign: true })}
                </span>
                <span className="kpi-meta">Inflows − Outflows</span>
              </div>
            </div>

            {/* Paginated Transactions Table */}
            {filteredTransactions.length === 0 ? (
              <EmptyState
                icon={Receipt}
                title="No matching transactions"
                description="No recorded transactions match the active filters in this window."
              />
            ) : (
              <div>
                <div className="table-wrap">
                  <table className="data-table">
                    <thead>
                      <tr>
                        <th>ID</th>
                        <th>Booked Date</th>
                        <th>Direction</th>
                        <th>Transaction Type</th>
                        <th>Linked Invoice</th>
                        <th className="num">Amount</th>
                        <th>Description</th>
                      </tr>
                    </thead>
                    <tbody>
                      {pagedTransactions.map((tx) => (
                        <tr key={tx.id}>
                          <td className="mono tabular-nums">{tx.id}</td>
                          <td className="tabular-nums">{tx.bookedDate}</td>
                          <td>
                            <Badge tone={directionTone(tx.direction)}>{tx.direction}</Badge>
                          </td>
                          <td className="mono" style={{ fontSize: 12.5 }}>
                            {tx.transactionType}
                          </td>
                          <td>
                            {tx.invoiceId != null ? (
                              <Badge tone="sky">#{tx.invoiceId}</Badge>
                            ) : (
                              <Badge tone="neutral">Unlinked</Badge>
                            )}
                          </td>
                          <td className="num tabular-nums" style={{ fontWeight: 600 }}>
                            {formatMoneyDisplay(tx.amount, tx.currency || ledgerCurrency)}
                          </td>
                          <td>{tx.description || '—'}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>

                <PaginationBar
                  page={safeLedgerPage}
                  totalPages={ledgerTotalPages}
                  totalItems={filteredTransactions.length}
                  itemLabel="transactions"
                  onPageChange={setLedgerPage}
                />
              </div>
            )}
          </>
        )}
      </section>
    </div>
  );
}
