import React, { useMemo, useRef, useState } from 'react';
import { Activity } from 'lucide-react';
import {
  formatCentsToRaw,
  formatMoneyDisplay,
  parseMoneyToCents,
} from '../utils/money';
import { Badge, EmptyState } from './Common';

const VIEW_WIDTH = 920;
const VIEW_HEIGHT = 340;
const PAD = { top: 24, right: 24, bottom: 38, left: 86 };
const PLOT_WIDTH = VIEW_WIDTH - PAD.left - PAD.right;
const PLOT_HEIGHT = VIEW_HEIGHT - PAD.top - PAD.bottom;

const ZOOM_OPTIONS = [
  { id: '90d', label: 'Last 90d', count: 90 },
  { id: '180d', label: 'Last 180d', count: 180 },
  { id: 'all', label: 'All Days', count: null },
];

function parseIsoDayMs(iso) {
  if (!iso) return 0;
  return Date.parse(`${iso}T00:00:00Z`);
}

function centsToPlotNumber(cents) {
  return Number(cents) / 100;
}

function formatAxisTickFromNumber(val, currency) {
  const roundedCents = BigInt(Math.round(val * 100));
  return formatMoneyDisplay(formatCentsToRaw(roundedCents), currency);
}

export function CashFlowChart({
  days = [],
  warnings = [],
  warningThreshold = '0.00',
  currency = 'USD',
  forecast = null,
}) {
  const [zoomWindow, setZoomWindow] = useState('all');
  const [hoverInfo, setHoverInfo] = useState(null);
  const svgRef = useRef(null);

  const warningsByDate = useMemo(() => {
    const map = new Map();
    for (const w of warnings || []) {
      if (w?.date) {
        map.set(w.date, w);
      }
    }
    return map;
  }, [warnings]);

  const visibleDays = useMemo(() => {
    if (!Array.isArray(days) || days.length === 0) return [];
    const opt = ZOOM_OPTIONS.find((o) => o.id === zoomWindow);
    if (!opt || !opt.count || days.length <= opt.count) {
      return days;
    }
    return days.slice(-opt.count);
  }, [days, zoomWindow]);

  const sortedForecasts = useMemo(() => {
    if (!forecast || !Array.isArray(forecast.forecasts) || forecast.forecasts.length === 0) {
      return [];
    }
    return [...forecast.forecasts].sort((a, b) => a.horizonDays - b.horizonDays);
  }, [forecast]);

  const chartModel = useMemo(() => {
    if (visibleDays.length === 0) return null;

    const histPoints = visibleDays.map((d) => ({
      kind: 'historical',
      date: d.date,
      xMs: parseIsoDayMs(d.date),
      cents: parseMoneyToCents(d.closingBalance),
      day: d,
      warning: warningsByDate.get(d.date) || null,
    }));

    const thresholdCents = parseMoneyToCents(warningThreshold || '0.00');

    const forecastPoints = sortedForecasts.map((f) => ({
      kind: 'forecast',
      date: f.targetDate,
      xMs: parseIsoDayMs(f.targetDate),
      cents: parseMoneyToCents(f.predictedClosingBalance),
      low80Cents: parseMoneyToCents(f.interval80.lower),
      high80Cents: parseMoneyToCents(f.interval80.upper),
      low95Cents: parseMoneyToCents(f.interval95.lower),
      high95Cents: parseMoneyToCents(f.interval95.upper),
      forecast: f,
    }));

    const originDate = forecast?.originDate || histPoints[histPoints.length - 1].date;
    const originMs = parseIsoDayMs(originDate);
    const matchingOriginHist =
      histPoints.find((p) => p.date === originDate) || histPoints[histPoints.length - 1];
    const originBalanceCents = matchingOriginHist.cents;

    let minMs = histPoints[0].xMs;
    let maxMs =
      forecastPoints.length > 0
        ? Math.max(histPoints[histPoints.length - 1].xMs, forecastPoints[forecastPoints.length - 1].xMs)
        : histPoints[histPoints.length - 1].xMs;

    if (maxMs <= minMs) {
      minMs -= 86400000;
      maxMs += 86400000;
    }

    let minCents = thresholdCents;
    let maxCents = thresholdCents;

    for (const p of histPoints) {
      if (p.cents < minCents) minCents = p.cents;
      if (p.cents > maxCents) maxCents = p.cents;
    }
    for (const fp of forecastPoints) {
      if (fp.low95Cents < minCents) minCents = fp.low95Cents;
      if (fp.high95Cents > maxCents) maxCents = fp.high95Cents;
      if (fp.cents < minCents) minCents = fp.cents;
      if (fp.cents > maxCents) maxCents = fp.cents;
    }

    const rawMin = centsToPlotNumber(minCents);
    const rawMax = centsToPlotNumber(maxCents);
    const span = rawMax - rawMin;
    const padVal = span > 0 ? span * 0.1 : Math.max(Math.abs(rawMax) * 0.1, 100);
    const yMin = rawMin - padVal;
    const yMax = rawMax + padVal;

    const xScale = (ms) => PAD.left + ((ms - minMs) / (maxMs - minMs)) * PLOT_WIDTH;
    const yScale = (cents) => {
      const val = centsToPlotNumber(cents);
      return PAD.top + PLOT_HEIGHT - ((val - yMin) / (yMax - yMin)) * PLOT_HEIGHT;
    };

    const plottedHist = histPoints.map((p) => ({
      ...p,
      x: xScale(p.xMs),
      y: yScale(p.cents),
    }));

    const plottedForecast = forecastPoints.map((fp) => ({
      ...fp,
      x: xScale(fp.xMs),
      y: yScale(fp.cents),
      yLow80: yScale(fp.low80Cents),
      yHigh80: yScale(fp.high80Cents),
      yLow95: yScale(fp.low95Cents),
      yHigh95: yScale(fp.high95Cents),
    }));

    const histPath = plottedHist
      .map((p, idx) => `${idx === 0 ? 'M' : 'L'} ${p.x.toFixed(2)} ${p.y.toFixed(2)}`)
      .join(' ');

    const originX = xScale(originMs);
    const originY = yScale(originBalanceCents);

    let forecastLinePath = '';
    let band80Points = '';
    let band95Points = '';

    if (plottedForecast.length > 0) {
      const anchorPt = { x: originX, y: originY };
      const lineNodes = [anchorPt, ...plottedForecast];
      forecastLinePath = lineNodes
        .map((p, idx) => `${idx === 0 ? 'M' : 'L'} ${p.x.toFixed(2)} ${p.y.toFixed(2)}`)
        .join(' ');

      const upper95 = [anchorPt, ...plottedForecast.map((p) => ({ x: p.x, y: p.yHigh95 }))];
      const lower95 = [...plottedForecast.map((p) => ({ x: p.x, y: p.yLow95 })).reverse(), anchorPt];
      band95Points = [...upper95, ...lower95].map((p) => `${p.x.toFixed(2)},${p.y.toFixed(2)}`).join(' ');

      const upper80 = [anchorPt, ...plottedForecast.map((p) => ({ x: p.x, y: p.yHigh80 }))];
      const lower80 = [...plottedForecast.map((p) => ({ x: p.x, y: p.yLow80 })).reverse(), anchorPt];
      band80Points = [...upper80, ...lower80].map((p) => `${p.x.toFixed(2)},${p.y.toFixed(2)}`).join(' ');
    }

    const yTicks = Array.from({ length: 5 }, (_, i) => {
      const ratio = i / 4;
      const val = yMin + (yMax - yMin) * (1 - ratio);
      const y = PAD.top + ratio * PLOT_HEIGHT;
      return {
        y,
        label: formatAxisTickFromNumber(val, currency),
      };
    });

    const xTickCount = 6;
    const xTicks = Array.from({ length: xTickCount }, (_, i) => {
      const ms = minMs + ((maxMs - minMs) * i) / (xTickCount - 1);
      const iso = new Date(Math.round(ms)).toISOString().slice(0, 10);
      return {
        x: xScale(ms),
        label: iso,
      };
    });

    const thresholdY = yScale(thresholdCents);
    const showZeroLine = yMin <= 0 && yMax >= 0 && thresholdCents !== 0n;
    const zeroY = showZeroLine ? yScale(0n) : null;

    const interactivePoints = [...plottedHist, ...plottedForecast].sort((a, b) => a.x - b.x);

    return {
      plottedHist,
      plottedForecast,
      histPath,
      forecastLinePath,
      band80Points,
      band95Points,
      originDate,
      originX,
      originY,
      thresholdY,
      zeroY,
      yTicks,
      xTicks,
      interactivePoints,
    };
  }, [visibleDays, warningsByDate, warningThreshold, sortedForecasts, forecast, currency]);

  const handlePointerMove = (e) => {
    if (!chartModel || !svgRef.current || chartModel.interactivePoints.length === 0) return;
    const rect = svgRef.current.getBoundingClientRect();
    if (!rect.width || !rect.height) return;

    const svgX = ((e.clientX - rect.left) / rect.width) * VIEW_WIDTH;
    let nearest = chartModel.interactivePoints[0];
    let bestDist = Math.abs(nearest.x - svgX);

    for (let i = 1; i < chartModel.interactivePoints.length; i += 1) {
      const pt = chartModel.interactivePoints[i];
      const dist = Math.abs(pt.x - svgX);
      if (dist < bestDist) {
        bestDist = dist;
        nearest = pt;
      }
    }

    const leftPx = (nearest.x / VIEW_WIDTH) * rect.width;
    const topPx = (nearest.y / VIEW_HEIGHT) * rect.height;
    const pctX = (nearest.x / VIEW_WIDTH) * 100;
    const rawLeft = pctX > 62 ? leftPx - 245 : leftPx + 14;
    const tooltipLeft = Math.max(8, Math.min(rawLeft, Math.max(8, rect.width - 260)));
    const tooltipTop = Math.max(8, Math.min(topPx - 24, Math.max(8, rect.height - 130)));

    setHoverInfo({
      point: nearest,
      tooltipLeft,
      tooltipTop,
    });
  };

  const clearHover = () => setHoverInfo(null);

  if (!days || days.length === 0) {
    return (
      <div className="card">
        <EmptyState
          icon={Activity}
          title="No daily cash-flow data to chart"
          description="No roll-forward days were returned for the selected date range."
        />
      </div>
    );
  }

  return (
    <div className="card">
      <div className="card-header">
        <div>
          <h3 className="card-title">
            <Activity size={17} color="var(--accent-primary)" />
            Daily Closing Cash Balance &amp; Conditional Forecast Fan
          </h3>
          <p className="card-subtitle">
            Historical roll-forward closing balances ({visibleDays[0]?.date} →{' '}
            {visibleDays[visibleDays.length - 1]?.date})
            {sortedForecasts.length > 0
              ? ` with ${sortedForecasts.length} conformal forecast horizon(s) from ${chartModel?.originDate}`
              : ''}
          </p>
        </div>

        <div className="btn-row no-print" role="group" aria-label="Historical chart window zoom">
          {ZOOM_OPTIONS.map((opt) => (
            <button
              key={opt.id}
              type="button"
              className={`btn btn-sm ${zoomWindow === opt.id ? 'btn-primary' : 'btn-secondary'}`}
              onClick={() => {
                setZoomWindow(opt.id);
                setHoverInfo(null);
              }}
            >
              {opt.label}
            </button>
          ))}
        </div>
      </div>

      {chartModel && (
        <div className="chart-container">
          <svg
            ref={svgRef}
            className="chart-svg"
            viewBox={`0 0 ${VIEW_WIDTH} ${VIEW_HEIGHT}`}
            role="img"
            aria-label="Cash flow closing balance and conditional forecast chart"
          >
            {/* Horizontal Y-Axis Gridlines */}
            {chartModel.yTicks.map((tick, idx) => (
              <g key={idx}>
                <line
                  x1={PAD.left}
                  x2={VIEW_WIDTH - PAD.right}
                  y1={tick.y}
                  y2={tick.y}
                  stroke="#e2e8f0"
                  strokeWidth="1"
                />
                <text
                  x={PAD.left - 8}
                  y={tick.y + 4}
                  textAnchor="end"
                  fontSize="11"
                  fill="#64748b"
                  fontFamily="var(--font-mono)"
                >
                  {tick.label}
                </text>
              </g>
            ))}

            {/* X-Axis Ticks */}
            {chartModel.xTicks.map((tick, idx) => (
              <g key={idx}>
                <line
                  x1={tick.x}
                  x2={tick.x}
                  y1={PAD.top + PLOT_HEIGHT}
                  y2={PAD.top + PLOT_HEIGHT + 5}
                  stroke="#cbd5e1"
                  strokeWidth="1"
                />
                <text
                  x={tick.x}
                  y={VIEW_HEIGHT - 12}
                  textAnchor={idx === 0 ? 'start' : idx === chartModel.xTicks.length - 1 ? 'end' : 'middle'}
                  fontSize="11"
                  fill="#64748b"
                  fontFamily="var(--font-mono)"
                >
                  {tick.label}
                </text>
              </g>
            ))}

            {/* Optional Zero Reference Line when distinct from threshold */}
            {chartModel.zeroY !== null && (
              <line
                x1={PAD.left}
                x2={VIEW_WIDTH - PAD.right}
                y1={chartModel.zeroY}
                y2={chartModel.zeroY}
                stroke="#94a3b8"
                strokeWidth="1"
                strokeDasharray="2 3"
              />
            )}

            {/* Warning Threshold Buffer Line */}
            <line
              x1={PAD.left}
              x2={VIEW_WIDTH - PAD.right}
              y1={chartModel.thresholdY}
              y2={chartModel.thresholdY}
              stroke="#d97706"
              strokeWidth="1.5"
              strokeDasharray="6 4"
            />
            <text
              x={PAD.left + 6}
              y={Math.max(PAD.top + 12, chartModel.thresholdY - 6)}
              fontSize="11"
              fontWeight="600"
              fill="#b45309"
            >
              Warning Buffer: {formatMoneyDisplay(warningThreshold, currency)}
            </text>

            {/* Conformal Forecast Bands (95% outer, 80% inner) */}
            {chartModel.band95Points && (
              <polygon points={chartModel.band95Points} fill="rgba(59, 130, 246, 0.14)" />
            )}
            {chartModel.band80Points && (
              <polygon points={chartModel.band80Points} fill="rgba(37, 99, 235, 0.24)" />
            )}

            {/* Origin Date Vertical Divider */}
            {chartModel.plottedForecast.length > 0 && (
              <g>
                <line
                  x1={chartModel.originX}
                  x2={chartModel.originX}
                  y1={PAD.top}
                  y2={PAD.top + PLOT_HEIGHT}
                  stroke="#334155"
                  strokeWidth="1.25"
                  strokeDasharray="4 4"
                />
                <text
                  x={Math.min(VIEW_WIDTH - PAD.right - 96, chartModel.originX + 6)}
                  y={PAD.top + 12}
                  fontSize="11"
                  fontWeight="600"
                  fill="#334155"
                >
                  Origin: {chartModel.originDate}
                </text>
              </g>
            )}

            {/* Historical Closing Balance Line */}
            {chartModel.plottedHist.length > 1 ? (
              <path
                d={chartModel.histPath}
                fill="none"
                stroke="#2563eb"
                strokeWidth="2.25"
                strokeLinejoin="round"
                strokeLinecap="round"
              />
            ) : (
              <circle
                cx={chartModel.plottedHist[0].x}
                cy={chartModel.plottedHist[0].y}
                r="4.5"
                fill="#2563eb"
              />
            )}

            {/* Warning Day Highlights */}
            {chartModel.plottedHist
              .filter((p) => p.warning)
              .map((p) => {
                const isShortfall = p.warning.kind === 'POTENTIAL_CASH_SHORTFALL';
                return (
                  <circle
                    key={p.date}
                    cx={p.x}
                    cy={p.y}
                    r="4.5"
                    fill={isShortfall ? '#dc2626' : '#d97706'}
                    stroke="#ffffff"
                    strokeWidth="1.5"
                  />
                );
              })}

            {/* Forecast Predicted Closing Balance Line & Markers */}
            {chartModel.forecastLinePath && (
              <path
                d={chartModel.forecastLinePath}
                fill="none"
                stroke="#0d9488"
                strokeWidth="2.25"
                strokeDasharray="6 4"
              />
            )}
            {chartModel.plottedForecast.map((fp) => (
              <circle
                key={fp.date}
                cx={fp.x}
                cy={fp.y}
                r="4.5"
                fill="#0d9488"
                stroke="#ffffff"
                strokeWidth="1.5"
              />
            ))}

            {/* Hover Crosshair */}
            {hoverInfo && (
              <g pointerEvents="none">
                <line
                  x1={hoverInfo.point.x}
                  x2={hoverInfo.point.x}
                  y1={PAD.top}
                  y2={PAD.top + PLOT_HEIGHT}
                  stroke="#0f172a"
                  strokeOpacity="0.35"
                  strokeWidth="1"
                />
                <circle
                  cx={hoverInfo.point.x}
                  cy={hoverInfo.point.y}
                  r="5.5"
                  fill={hoverInfo.point.kind === 'forecast' ? '#0d9488' : '#2563eb'}
                  stroke="#ffffff"
                  strokeWidth="2"
                />
              </g>
            )}

            {/* Interactive Pointer Overlay */}
            <rect
              x={PAD.left}
              y={PAD.top}
              width={PLOT_WIDTH}
              height={PLOT_HEIGHT}
              fill="transparent"
              style={{ cursor: 'crosshair' }}
              onMouseMove={handlePointerMove}
              onPointerMove={handlePointerMove}
              onMouseLeave={clearHover}
              onPointerLeave={clearHover}
            />
          </svg>

          {/* Floating Tooltip */}
          {hoverInfo && (
            <div
              className="chart-tooltip"
              style={{
                left: hoverInfo.tooltipLeft,
                top: hoverInfo.tooltipTop,
              }}
            >
              <div className="chart-tooltip-date mono">
                {hoverInfo.point.date}{' '}
                {hoverInfo.point.kind === 'forecast'
                  ? `(+${hoverInfo.point.forecast.horizonDays}d Forecast)`
                  : '(Historical)'}
              </div>

              {hoverInfo.point.kind === 'historical' ? (
                <div>
                  <div>
                    Closing:{' '}
                    <strong className="tabular-nums">
                      {formatMoneyDisplay(hoverInfo.point.day.closingBalance, currency)}
                    </strong>
                  </div>
                  <div className="tabular-nums">
                    Inflow: {formatMoneyDisplay(hoverInfo.point.day.inflow, currency)} | Outflow:{' '}
                    {formatMoneyDisplay(hoverInfo.point.day.outflow, currency)}
                  </div>
                  <div className="tabular-nums">
                    Net:{' '}
                    {formatMoneyDisplay(hoverInfo.point.day.netMovement, currency, {
                      showSign: true,
                    })}
                  </div>
                  {hoverInfo.point.warning && (
                    <div style={{ marginTop: 5 }}>
                      <Badge
                        tone={
                          hoverInfo.point.warning.kind === 'POTENTIAL_CASH_SHORTFALL' ? 'red' : 'amber'
                        }
                      >
                        {hoverInfo.point.warning.kind}
                      </Badge>
                      <div style={{ marginTop: 2 }} className="tabular-nums">
                        Gap: {formatMoneyDisplay(hoverInfo.point.warning.gapToThreshold, currency)}
                      </div>
                    </div>
                  )}
                </div>
              ) : (
                <div>
                  <div>
                    Predicted:{' '}
                    <strong className="tabular-nums">
                      {formatMoneyDisplay(hoverInfo.point.forecast.predictedClosingBalance, currency)}
                    </strong>
                  </div>
                  <div className="tabular-nums">
                    80% CI: {formatMoneyDisplay(hoverInfo.point.forecast.interval80.lower, currency)} →{' '}
                    {formatMoneyDisplay(hoverInfo.point.forecast.interval80.upper, currency)}
                  </div>
                  <div className="tabular-nums">
                    95% CI: {formatMoneyDisplay(hoverInfo.point.forecast.interval95.lower, currency)} →{' '}
                    {formatMoneyDisplay(hoverInfo.point.forecast.interval95.upper, currency)}
                  </div>
                  <div style={{ opacity: 0.85, marginTop: 2 }}>
                    Residuals: {hoverInfo.point.forecast.residualCount}
                  </div>
                </div>
              )}
            </div>
          )}

          {/* Legend */}
          <div className="chart-legend">
            <span className="legend-item">
              <span className="legend-swatch" style={{ background: '#2563eb' }} />
              Historical Closing Balance
            </span>
            <span className="legend-item">
              <span
                className="legend-swatch"
                style={{
                  background: 'repeating-linear-gradient(90deg, #d97706 0 6px, transparent 6px 10px)',
                }}
              />
              Warning Buffer ({formatMoneyDisplay(warningThreshold, currency)})
            </span>
            <span className="legend-item">
              <span
                className="legend-swatch"
                style={{ width: 9, height: 9, borderRadius: '50%', background: '#dc2626' }}
              />
              Shortfall (&lt; 0.00)
            </span>
            <span className="legend-item">
              <span
                className="legend-swatch"
                style={{ width: 9, height: 9, borderRadius: '50%', background: '#d97706' }}
              />
              Below Buffer
            </span>
            {sortedForecasts.length > 0 && (
              <>
                <span className="legend-item">
                  <span
                    className="legend-swatch"
                    style={{
                      background: 'repeating-linear-gradient(90deg, #0d9488 0 6px, transparent 6px 10px)',
                    }}
                  />
                  Predicted Balance
                </span>
                <span className="legend-item">
                  <span className="legend-band" style={{ background: 'rgba(37, 99, 235, 0.24)' }} />
                  80% Conformal Band
                </span>
                <span className="legend-item">
                  <span className="legend-band" style={{ background: 'rgba(59, 130, 246, 0.14)' }} />
                  95% Conformal Band
                </span>
              </>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
