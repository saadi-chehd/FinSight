import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Activity,
  Building2,
  FileSpreadsheet,
  FlaskConical,
  GitBranch,
  Landmark,
  RefreshCw,
  SlidersHorizontal,
  TrendingUp,
} from 'lucide-react';
import { getBackendHealth, getDatabaseHealth, listSmes } from './api/finsightApi';
import { ApiErrorAlert, Badge, EmptyState, SyntheticProvenanceBanner } from './components/Common';
import { CashFlowForecastView } from './components/CashFlowForecastView';
import { InvoicesLedgerView } from './components/InvoicesLedgerView';
import { ScenarioSimulatorView } from './components/ScenarioSimulatorView';
import { BankPilotView } from './components/BankPilotView';
import { getFs1PresetForSme, SUPPORTED_CURRENCIES, validateOpeningParams } from './utils/money';

const EMPTY_DRAFT = (currency = 'USD') => ({
  openingDate: '',
  openingAmount: '',
  currency: currency || 'USD',
  endDate: '',
});

export function App() {
  const [workspace, setWorkspace] = useState('finsight');
  const [activeTab, setActiveTab] = useState('cashflow');

  const [health, setHealth] = useState({
    backend: 'checking',
    database: 'checking',
  });

  const [smes, setSmes] = useState([]);
  const [smesLoading, setSmesLoading] = useState(true);
  const [smesError, setSmesError] = useState(null);
  const [selectedSmeId, setSelectedSmeId] = useState('');

  // Per-SME explicit opening cash drafts and committed parameters (never defaulted to 0.00)
  const [draftsBySme, setDraftsBySme] = useState({});
  const [committedBySme, setCommittedBySme] = useState({});
  const [validationErrors, setValidationErrors] = useState({});

  const refreshHealth = useCallback(async (signal) => {
    setHealth({ backend: 'checking', database: 'checking' });
    const [beRes, dbRes] = await Promise.allSettled([
      getBackendHealth(signal),
      getDatabaseHealth(signal),
    ]);
    if (signal?.aborted) return;
    setHealth({
      backend: beRes.status === 'fulfilled' && beRes.value?.status === 'UP' ? 'up' : 'down',
      database: dbRes.status === 'fulfilled' && dbRes.value?.status === 'UP' ? 'up' : 'down',
    });
  }, []);

  const loadSmes = useCallback(async (signal) => {
    setSmesLoading(true);
    setSmesError(null);
    try {
      const data = await listSmes(signal);
      if (signal?.aborted) return;
      const list = Array.isArray(data) ? data : [];
      setSmes(list);
      if (list.length > 0) {
        setSelectedSmeId((prev) => (prev && list.some((s) => String(s.id) === String(prev)) ? prev : String(list[0].id)));
      }
    } catch (err) {
      if (err?.name !== 'AbortError') {
        setSmesError(err);
      }
    } finally {
      if (!signal?.aborted) {
        setSmesLoading(false);
      }
    }
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    refreshHealth(controller.signal);
    loadSmes(controller.signal);
    return () => controller.abort();
  }, [refreshHealth, loadSmes]);

  const selectedSme = useMemo(
    () => smes.find((s) => String(s.id) === String(selectedSmeId)) || null,
    [smes, selectedSmeId],
  );

  const currentDraft = useMemo(() => {
    if (!selectedSme) return EMPTY_DRAFT('USD');
    return draftsBySme[selectedSme.id] || EMPTY_DRAFT(selectedSme.currency);
  }, [selectedSme, draftsBySme]);

  const committedParams = useMemo(() => {
    if (!selectedSme) return null;
    return committedBySme[selectedSme.id] || null;
  }, [selectedSme, committedBySme]);

  const fs1Preset = useMemo(() => getFs1PresetForSme(selectedSme), [selectedSme]);

  const updateDraftField = (field, value) => {
    if (!selectedSme) return;
    setDraftsBySme((prev) => ({
      ...prev,
      [selectedSme.id]: {
        ...(prev[selectedSme.id] || EMPTY_DRAFT(selectedSme.currency)),
        currency: selectedSme.currency,
        [field]: value,
      },
    }));
    setValidationErrors((prev) => {
      if (!prev[field]) return prev;
      const next = { ...prev };
      delete next[field];
      return next;
    });
  };

  const handleApplyOpening = (e) => {
    e.preventDefault();
    if (!selectedSme) return;
    const candidate = {
      ...currentDraft,
      currency: selectedSme.currency,
    };
    const check = validateOpeningParams(candidate);
    if (!check.valid) {
      setValidationErrors(check.errors);
      return;
    }
    setValidationErrors({});
    setDraftsBySme((prev) => ({
      ...prev,
      [selectedSme.id]: check.normalized,
    }));
    setCommittedBySme((prev) => ({
      ...prev,
      [selectedSme.id]: check.normalized,
    }));
  };

  const handleApplyFs1Preset = () => {
    if (!selectedSme || !fs1Preset) return;
    const presetParams = {
      openingDate: fs1Preset.openingDate,
      openingAmount: fs1Preset.openingAmount,
      currency: selectedSme.currency,
      endDate: fs1Preset.endDate,
    };
    setValidationErrors({});
    setDraftsBySme((prev) => ({
      ...prev,
      [selectedSme.id]: presetParams,
    }));
    setCommittedBySme((prev) => ({
      ...prev,
      [selectedSme.id]: presetParams,
    }));
  };

  const handleClearOpening = () => {
    if (!selectedSme) return;
    setValidationErrors({});
    setDraftsBySme((prev) => ({
      ...prev,
      [selectedSme.id]: EMPTY_DRAFT(selectedSme.currency),
    }));
    setCommittedBySme((prev) => {
      const next = { ...prev };
      delete next[selectedSme.id];
      return next;
    });
  };

  return (
    <div className="app-shell" data-workspace={workspace}>
      <header className="topbar">
        <div className="topbar-inner">
          <div className="brand-group">
            <div className="brand-mark" aria-hidden="true">
              <Landmark size={19} />
            </div>
            <div>
              <h1 className="brand-title">FinSight + BankPilot</h1>
              <p className="brand-subtitle">
                Deterministic Liquidity Analytics, Scenario Simulation &amp; Governed SME Review
              </p>
            </div>
          </div>

          <nav className="mode-switcher" aria-label="Workspace Mode">
            <button
              type="button"
              className={`mode-btn ${workspace === 'finsight' ? 'active-finsight' : ''}`}
              onClick={() => setWorkspace('finsight')}
            >
              <TrendingUp size={15} />
              FinSight SME Workspace
            </button>
            <button
              type="button"
              className={`mode-btn ${workspace === 'bankpilot' ? 'active-bankpilot' : ''}`}
              onClick={() => setWorkspace('bankpilot')}
            >
              <Building2 size={15} />
              BankPilot Analyst Review
            </button>
          </nav>

          <div className="health-cluster" aria-label="System Health">
            <span className="health-pill" title="Java Spring Backend (/api/health)">
              <span className={`health-dot ${health.backend}`} />
              Backend: {health.backend.toUpperCase()}
            </span>
            <span className="health-pill" title="PostgreSQL Database (/api/health/database)">
              <span className={`health-dot ${health.database}`} />
              Database: {health.database.toUpperCase()}
            </span>
            <button
              type="button"
              className="icon-btn-dark"
              onClick={() => {
                refreshHealth();
                loadSmes();
              }}
              title="Refresh system health and SMEs"
              aria-label="Refresh system health and SMEs"
            >
              <RefreshCw size={14} />
            </button>
          </div>
        </div>
      </header>

      <main className="main-container">
        {smesError && (
          <ApiErrorAlert
            error={smesError}
            title="Unable to load SME registry"
            onRetry={() => loadSmes()}
          />
        )}

        {workspace === 'finsight' ? (
          <>
            <section className="control-card no-print" aria-label="SME and Explicit Opening Cash Controls">
              <div className="control-header">
                <div className="control-title-group">
                  <SlidersHorizontal size={16} color="var(--accent-primary)" />
                  <h2 className="control-title">SME Context &amp; Explicit Opening Cash Input</h2>
                  {selectedSme && <Badge tone="indigo">Currency: {selectedSme.currency}</Badge>}
                  {committedParams ? (
                    <Badge tone="green">
                      Active: {committedParams.openingAmount} {committedParams.currency} ({committedParams.openingDate} →{' '}
                      {committedParams.endDate})
                    </Badge>
                  ) : (
                    <Badge tone="amber">Opening cash required (never defaulted to 0.00)</Badge>
                  )}
                </div>

                {selectedSme && fs1Preset && (
                  <button
                    type="button"
                    className="btn btn-preset btn-sm"
                    onClick={handleApplyFs1Preset}
                    title="Populate documented synthetic FS1 opening inputs (FS1-20261018-A: 5000.00, B: 12000.00, C: 8000.00)"
                  >
                    <FlaskConical size={14} />
                    Load FS1 Synthetic Preset ({fs1Preset.key}: {fs1Preset.openingAmount})
                  </button>
                )}
              </div>

              <form onSubmit={handleApplyOpening} noValidate>
                <div className="control-grid">
                  <div className="field">
                    <label className="field-label" htmlFor="sme-select">
                      Selected SME
                    </label>
                    <select
                      id="sme-select"
                      className="select"
                      value={selectedSmeId}
                      disabled={smesLoading || smes.length === 0}
                      onChange={(e) => {
                        setSelectedSmeId(e.target.value);
                        setValidationErrors({});
                      }}
                    >
                      {smes.length === 0 ? (
                        <option value="">{smesLoading ? 'Loading SMEs…' : 'No SMEs available'}</option>
                      ) : (
                        smes.map((sme) => (
                          <option key={sme.id} value={sme.id}>
                            #{sme.id} — {sme.name} ({sme.currency})
                          </option>
                        ))
                      )}
                    </select>
                  </div>

                  <div className="field">
                    <label className="field-label" htmlFor="opening-date">
                      Opening Date
                    </label>
                    <input
                      id="opening-date"
                      type="date"
                      className={`input ${validationErrors.openingDate ? 'input-error' : ''}`}
                      value={currentDraft.openingDate}
                      onChange={(e) => updateDraftField('openingDate', e.target.value)}
                    />
                    {validationErrors.openingDate && (
                      <span className="field-error">{validationErrors.openingDate}</span>
                    )}
                  </div>

                  <div className="field">
                    <label className="field-label" htmlFor="opening-amount">
                      Opening Balance (Exact 2 Decimals)
                    </label>
                    <input
                      id="opening-amount"
                      type="text"
                      inputMode="decimal"
                      placeholder="e.g. 5000.00"
                      className={`input tabular-nums ${validationErrors.openingAmount ? 'input-error' : ''}`}
                      value={currentDraft.openingAmount}
                      onChange={(e) => updateDraftField('openingAmount', e.target.value)}
                    />
                    {validationErrors.openingAmount && (
                      <span className="field-error">{validationErrors.openingAmount}</span>
                    )}
                  </div>

                  <div className="field">
                    <label className="field-label" htmlFor="opening-currency">
                      Currency
                    </label>
                    <select
                      id="opening-currency"
                      className="select"
                      value={selectedSme?.currency || currentDraft.currency}
                      disabled
                      title="Currency must match the selected SME currency"
                    >
                      {SUPPORTED_CURRENCIES.map((c) => (
                        <option key={c} value={c}>
                          {c}
                        </option>
                      ))}
                    </select>
                  </div>

                  <div className="field">
                    <label className="field-label" htmlFor="end-date">
                      End / Cutoff Date
                    </label>
                    <input
                      id="end-date"
                      type="date"
                      className={`input ${validationErrors.endDate ? 'input-error' : ''}`}
                      value={currentDraft.endDate}
                      onChange={(e) => updateDraftField('endDate', e.target.value)}
                    />
                    {validationErrors.endDate && <span className="field-error">{validationErrors.endDate}</span>}
                  </div>

                  <div className="btn-row">
                    <button type="submit" className="btn btn-primary" disabled={!selectedSme}>
                      Apply Opening Cash
                    </button>
                    {committedParams && (
                      <button type="button" className="btn btn-secondary" onClick={handleClearOpening}>
                        Clear
                      </button>
                    )}
                  </div>
                </div>
              </form>
            </section>

            <SyntheticProvenanceBanner smeName={selectedSme?.name} />

            <div className="tabs-bar no-print" role="tablist" aria-label="FinSight Views">
              <button
                type="button"
                role="tab"
                aria-selected={activeTab === 'cashflow'}
                className={`tab-btn ${activeTab === 'cashflow' ? 'active' : ''}`}
                onClick={() => setActiveTab('cashflow')}
              >
                <Activity size={15} />
                Cash Flow &amp; Forecast
              </button>
              <button
                type="button"
                role="tab"
                aria-selected={activeTab === 'invoices'}
                className={`tab-btn ${activeTab === 'invoices' ? 'active' : ''}`}
                onClick={() => setActiveTab('invoices')}
              >
                <FileSpreadsheet size={15} />
                Invoices &amp; Ledger
              </button>
              <button
                type="button"
                role="tab"
                aria-selected={activeTab === 'scenarios'}
                className={`tab-btn ${activeTab === 'scenarios' ? 'active' : ''}`}
                onClick={() => setActiveTab('scenarios')}
              >
                <GitBranch size={15} />
                Scenario Simulator
              </button>
            </div>

            {!selectedSme && !smesLoading ? (
              <EmptyState
                title="No SME records found"
                description="Load SME data into PostgreSQL (for example via the manual FS1 synthetic data CLI) and click refresh in the top bar."
              />
            ) : (
              <>
                {activeTab === 'cashflow' && <CashFlowForecastView sme={selectedSme} params={committedParams} />}
                {activeTab === 'invoices' && (
                  <InvoicesLedgerView
                    sme={selectedSme}
                    defaultAsOfDate={committedParams?.endDate || ''}
                    defaultFrom={committedParams?.openingDate || ''}
                    defaultTo={committedParams?.endDate || ''}
                  />
                )}
                {activeTab === 'scenarios' && <ScenarioSimulatorView sme={selectedSme} params={committedParams} />}
              </>
            )}
          </>
        ) : (
          <BankPilotView smes={smes} smesLoading={smesLoading} smesError={smesError} />
        )}
      </main>
    </div>
  );
}

export default App;
