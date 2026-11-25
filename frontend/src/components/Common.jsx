import React from 'react';
import { AlertTriangle, FlaskConical, Info, ShieldCheck } from 'lucide-react';
import { getFriendlyErrorMessage } from '../api/finsightApi';
import { isSyntheticSme } from '../utils/money';

export function SyntheticProvenanceBanner({ smeName }) {
  if (!isSyntheticSme(smeName)) {
    return null;
  }
  return (
    <div className="banner banner-synthetic" role="note" data-testid="synthetic-provenance-banner">
      <FlaskConical size={18} className="banner-icon" aria-hidden="true" />
      <div className="banner-content">
        <p className="banner-title">Synthetic Exercise Data Provenance (FS1)</p>
        <p className="banner-text">
          <strong>{smeName}</strong> uses deterministically generated synthetic dataset records (FS1) for
          software verification and demonstration — not real SME financial performance or audited bank balances.
        </p>
      </div>
    </div>
  );
}

export function DisclaimerBanner({ title = 'Conditional Analysis Notice', text, variant = 'disclaimer' }) {
  if (!text) return null;
  const Icon = variant === 'governance' ? ShieldCheck : Info;
  const cls = variant === 'governance' ? 'banner banner-governance' : 'banner banner-disclaimer';
  return (
    <div className={cls} role="note">
      <Icon size={18} className="banner-icon" aria-hidden="true" />
      <div className="banner-content">
        <p className="banner-title">{title}</p>
        <p className="banner-text">{text}</p>
      </div>
    </div>
  );
}

export function ApiErrorAlert({ error, title = 'Request Error', onRetry }) {
  if (!error) return null;
  return (
    <div className="banner banner-error" role="alert">
      <AlertTriangle size={18} className="banner-icon" aria-hidden="true" />
      <div className="banner-content">
        <p className="banner-title">{title}</p>
        <p className="banner-text">{getFriendlyErrorMessage(error)}</p>
      </div>
      {onRetry && (
        <button type="button" className="btn btn-secondary btn-sm" onClick={onRetry}>
          Retry
        </button>
      )}
    </div>
  );
}

export function Badge({ tone = 'neutral', children }) {
  return <span className={`badge badge-${tone}`}>{children}</span>;
}

export function LoadingSkeleton({ rows = 4 }) {
  return (
    <div className="card section-stack" aria-busy="true" aria-label="Loading content">
      <div className="skeleton" style={{ height: 22, width: '38%' }} />
      <div className="kpi-grid">
        {Array.from({ length: 4 }).map((_, idx) => (
          <div key={idx} className="skeleton" style={{ height: 76 }} />
        ))}
      </div>
      {Array.from({ length: rows }).map((_, idx) => (
        <div key={idx} className="skeleton" style={{ height: 18, width: `${92 - idx * 8}%` }} />
      ))}
    </div>
  );
}

export function EmptyState({ icon: Icon = Info, title, description, action }) {
  return (
    <div className="empty-state">
      <Icon size={28} strokeWidth={1.6} aria-hidden="true" />
      <h3>{title}</h3>
      <p>{description}</p>
      {action && <div style={{ marginTop: 14 }}>{action}</div>}
    </div>
  );
}
