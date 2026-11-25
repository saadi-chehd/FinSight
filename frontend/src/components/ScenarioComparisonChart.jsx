import React, { useMemo, useRef, useState } from 'react';
import { compareMoneyStrings, formatMoneyDisplay } from '../utils/money';

const SVG_WIDTH = 860;
const SVG_HEIGHT = 320;
const MARGIN = { top: 24, right: 24, bottom: 42, left: 82 };
const PLOT_WIDTH = SVG_WIDTH - MARGIN.left - MARGIN.right;
const PLOT_HEIGHT = SVG_HEIGHT - MARGIN.top - MARGIN.bottom;

function toPlotNumber(moneyStr) {
  const num = Number(moneyStr);
  return Number.isFinite(num) ? num : 0;
}

function formatAxisTick(value, currency) {
  const abs = Math.abs(value);
  const sign = value < 0 ? '-' : '';
  const sym =
    currency === 'USD'
      ? '$'
      : currency === 'EUR'
        ? '€'
        : currency === 'GBP'
          ? '£'
          : currency === 'TRY'
            ? '₺'
            : `${currency} `;
  if (abs >= 1_000_000) {
    return `${sign}${sym}${(abs / 1_000_000).toFixed(1)}M`;
  }
  if (abs >= 10_000) {
    return `${sign}${sym}${(abs / 1_000).toFixed(1)}k`;
  }
  return `${sign}${sym}${Math.round(abs).toLocaleString('en-US')}`;
}

function buildPolylinePoints(items, getX, getY) {
  return items.map((item, idx) => `${getX(idx).toFixed(2)},${getY(item).toFixed(2)}`).join(' ');
}

export function ScenarioComparisonChart({
  days = [],
  warningThreshold = '0.00',
  currency = 'USD',
  scenarioName = 'Scenario',
}) {
  const svgRef = useRef(null);
  const [hoverIdx, setHoverIdx] = useState(null);

  const chartModel = useMemo(() => {
    if (!Array.isArray(days) || days.length === 0) {
      return null;
    }

    const thresholdVal = toPlotNumber(warningThreshold);
    const values = [thresholdVal];
    for (const d of days) {
      values.push(toPlotNumber(d.baselineClosingBalance));
      values.push(toPlotNumber(d.scenarioClosingBalance));
    }

    let minVal = Math.min(...values);
    let maxVal = Math.max(...values);
    if (minVal === maxVal) {
      const pad = Math.max(Math.abs(minVal) * 0.1, 100);
      minVal -= pad;
      maxVal += pad;
    } else {
      const span = maxVal - minVal;
      minVal -= span * 0.08;
      maxVal += span * 0.08;
    }

    const getX = (idx) => {
      if (days.length === 1) {
        return MARGIN.left + PLOT_WIDTH / 2;
      }
      return MARGIN.left + (idx / (days.length - 1)) * PLOT_WIDTH;
    };

    const getY = (numericVal) => {
      const ratio = (numericVal - minVal) / (maxVal - minVal);
      return MARGIN.top + PLOT_HEIGHT - ratio * PLOT_HEIGHT;
    };

    const yTicks = Array.from({ length: 5 }, (_, idx) => {
      const frac = idx / 4;
      const val = minVal + frac * (maxVal - minVal);
      return {
        value: val,
        y: getY(val),
        label: formatAxisTick(val, currency),
      };
    });

    const xTickIndices = [];
    if (days.length === 1) {
      xTickIndices.push(0);
    } else {
      const tickCount = Math.min(6, days.length);
      for (let i = 0; i < tickCount; i += 1) {
        const idx = Math.round((i / (tickCount - 1)) * (days.length - 1));
        if (!xTickIndices.includes(idx)) {
          xTickIndices.push(idx);
        }
      }
    }

    const baselinePoints = buildPolylinePoints(
      days,
      getX,
      (d) => getY(toPlotNumber(d.baselineClosingBalance)),
    );
    const scenarioPoints = buildPolylinePoints(
      days,
      getX,
      (d) => getY(toPlotNumber(d.scenarioClosingBalance)),
    );

    return {
      thresholdVal,
      thresholdY: getY(thresholdVal),
      getX,
      getY,
      yTicks,
      xTickIndices,
      baselinePoints,
      scenarioPoints,
    };
  }, [days, warningThreshold, currency]);

  if (!chartModel) {
    return (
      <div className="empty-state">
        <p>No simulated daily records available to plot.</p>
      </div>
    );
  }

  const handlePointerMove = (e) => {
    if (!svgRef.current || days.length === 0) return;
    const rect = svgRef.current.getBoundingClientRect();
    if (rect.width <= 0) return;
    const relX = ((e.clientX - rect.left) / rect.width) * SVG_WIDTH;
    const clampedPlotX = Math.max(MARGIN.left, Math.min(MARGIN.left + PLOT_WIDTH, relX));
    if (days.length === 1) {
      setHoverIdx(0);
      return;
    }
    const idx = Math.round(((clampedPlotX - MARGIN.left) / PLOT_WIDTH) * (days.length - 1));
    setHoverIdx(Math.max(0, Math.min(days.length - 1, idx)));
  };

  const hoveredDay = hoverIdx !== null ? days[hoverIdx] : null;
  const hoverX = hoverIdx !== null ? chartModel.getX(hoverIdx) : 0;
  const hoverBaseY = hoveredDay ? chartModel.getY(toPlotNumber(hoveredDay.baselineClosingBalance)) : 0;
  const hoverScenY = hoveredDay ? chartModel.getY(toPlotNumber(hoveredDay.scenarioClosingBalance)) : 0;
  const hoverDeltaCmp = hoveredDay ? compareMoneyStrings(hoveredDay.closingBalanceDelta, '0.00') : 0;

  const hoverRatioX = hoverX / SVG_WIDTH;
  const tooltipOnLeft = hoverRatioX > 0.58;

  return (
    <div>
      <div className="chart-container" onMouseLeave={() => setHoverIdx(null)}>
        <svg
          ref={svgRef}
          viewBox={`0 0 ${SVG_WIDTH} ${SVG_HEIGHT}`}
          className="chart-svg"
          role="img"
          aria-label={`Baseline versus ${scenarioName} daily closing balance comparison chart`}
          onMouseMove={handlePointerMove}
        >
          {/* Horizontal grid lines & Y-axis labels */}
          {chartModel.yTicks.map((tick, idx) => (
            <g key={idx}>
              <line
                x1={MARGIN.left}
                y1={tick.y}
                x2={MARGIN.left + PLOT_WIDTH}
                y2={tick.y}
                stroke="#e2e8f0"
                strokeWidth="1"
              />
              <text
                x={MARGIN.left - 10}
                y={tick.y + 4}
                textAnchor="end"
                fontSize="11"
                fill="#64748b"
                className="tabular-nums"
              >
                {tick.label}
              </text>
            </g>
          ))}

          {/* X-axis baseline & labels */}
          <line
            x1={MARGIN.left}
            y1={MARGIN.top + PLOT_HEIGHT}
            x2={MARGIN.left + PLOT_WIDTH}
            y2={MARGIN.top + PLOT_HEIGHT}
            stroke="#cbd5e1"
            strokeWidth="1"
          />
          {chartModel.xTickIndices.map((idx) => {
            const x = chartModel.getX(idx);
            return (
              <g key={idx}>
                <line
                  x1={x}
                  y1={MARGIN.top + PLOT_HEIGHT}
                  x2={x}
                  y2={MARGIN.top + PLOT_HEIGHT + 5}
                  stroke="#cbd5e1"
                  strokeWidth="1"
                />
                <text
                  x={x}
                  y={MARGIN.top + PLOT_HEIGHT + 20}
                  textAnchor="middle"
                  fontSize="11"
                  fill="#64748b"
                  className="tabular-nums"
                >
                  {days[idx].date}
                </text>
              </g>
            );
          })}

          {/* Warning Threshold Line */}
          <line
            x1={MARGIN.left}
            y1={chartModel.thresholdY}
            x2={MARGIN.left + PLOT_WIDTH}
            y2={chartModel.thresholdY}
            stroke="#d97706"
            strokeWidth="1.5"
            strokeDasharray="5 4"
          />
          <text
            x={MARGIN.left + 6}
            y={Math.max(MARGIN.top + 12, chartModel.thresholdY - 6)}
            fontSize="10.5"
            fontWeight="600"
            fill="#b45309"
          >
            Warning Buffer ({formatMoneyDisplay(warningThreshold, currency)})
          </text>

          {/* Baseline & Scenario Curves */}
          {days.length > 1 ? (
            <>
              <polyline
                fill="none"
                stroke="#4f46e5"
                strokeWidth="2"
                strokeDasharray="6 4"
                strokeLinejoin="round"
                strokeLinecap="round"
                points={chartModel.baselinePoints}
              />
              <polyline
                fill="none"
                stroke="#0d9488"
                strokeWidth="2.5"
                strokeLinejoin="round"
                strokeLinecap="round"
                points={chartModel.scenarioPoints}
              />
            </>
          ) : (
            <>
              <circle cx={chartModel.getX(0)} cy={hoverBaseY || chartModel.getY(toPlotNumber(days[0].baselineClosingBalance))} r="5" fill="#4f46e5" />
              <circle cx={chartModel.getX(0)} cy={hoverScenY || chartModel.getY(toPlotNumber(days[0].scenarioClosingBalance))} r="5" fill="#0d9488" />
            </>
          )}

          {/* Hover Crosshair & Markers */}
          {hoveredDay && (
            <g>
              <line
                x1={hoverX}
                y1={MARGIN.top}
                x2={hoverX}
                y2={MARGIN.top + PLOT_HEIGHT}
                stroke="#94a3b8"
                strokeWidth="1"
                strokeDasharray="3 3"
              />
              <circle
                cx={hoverX}
                cy={hoverBaseY}
                r="4.5"
                fill="#4f46e5"
                stroke="#ffffff"
                strokeWidth="1.5"
              />
              <circle
                cx={hoverX}
                cy={hoverScenY}
                r="5"
                fill="#0d9488"
                stroke="#ffffff"
                strokeWidth="1.5"
              />
            </g>
          )}

          {/* Transparent Interaction Overlay */}
          <rect
            x={MARGIN.left}
            y={MARGIN.top}
            width={PLOT_WIDTH}
            height={PLOT_HEIGHT}
            fill="transparent"
            style={{ cursor: 'crosshair' }}
          />
        </svg>

        {hoveredDay && (
          <div
            className="chart-tooltip tabular-nums"
            style={{
              top: 12,
              left: tooltipOnLeft ? undefined : `${Math.min(72, hoverRatioX * 100 + 2)}%`,
              right: tooltipOnLeft ? `${Math.min(72, (1 - hoverRatioX) * 100 + 2)}%` : undefined,
            }}
          >
            <div className="chart-tooltip-date">{hoveredDay.date}</div>
            <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12 }}>
              <span style={{ color: '#c7d2fe' }}>Baseline Closing:</span>
              <strong>{formatMoneyDisplay(hoveredDay.baselineClosingBalance, currency)}</strong>
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12 }}>
              <span style={{ color: '#99f6e4' }}>Scenario Closing:</span>
              <strong>{formatMoneyDisplay(hoveredDay.scenarioClosingBalance, currency)}</strong>
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, marginTop: 3 }}>
              <span style={{ color: '#cbd5e1' }}>Closing Delta:</span>
              <strong
                style={{
                  color:
                    hoverDeltaCmp > 0 ? '#4ade80' : hoverDeltaCmp < 0 ? '#f87171' : '#e2e8f0',
                }}
              >
                {formatMoneyDisplay(hoveredDay.closingBalanceDelta, currency, { showSign: true })}
              </strong>
            </div>
            <div
              style={{
                borderTop: '1px solid rgba(255,255,255,0.14)',
                marginTop: 5,
                paddingTop: 5,
                fontSize: 11,
                color: '#cbd5e1',
              }}
            >
              <div>
                Inflow: {formatMoneyDisplay(hoveredDay.baselineInflow, currency)} →{' '}
                {formatMoneyDisplay(hoveredDay.scenarioInflow, currency)}
              </div>
              <div>
                Outflow: {formatMoneyDisplay(hoveredDay.baselineOutflow, currency)} →{' '}
                {formatMoneyDisplay(hoveredDay.scenarioOutflow, currency)}
              </div>
            </div>
          </div>
        )}
      </div>

      <div className="chart-legend">
        <span className="legend-item">
          <span
            className="legend-swatch"
            style={{
              background: 'repeating-linear-gradient(90deg, #4f46e5 0 6px, transparent 6px 10px)',
            }}
          />
          Baseline Closing Balance
        </span>
        <span className="legend-item">
          <span className="legend-swatch" style={{ background: '#0d9488' }} />
          {scenarioName} Closing Balance
        </span>
        <span className="legend-item">
          <span
            className="legend-swatch"
            style={{
              background: 'repeating-linear-gradient(90deg, #d97706 0 5px, transparent 5px 9px)',
            }}
          />
          Warning Threshold ({formatMoneyDisplay(warningThreshold, currency)})
        </span>
      </div>
    </div>
  );
}
