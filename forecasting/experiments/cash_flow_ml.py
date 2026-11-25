"""CM2: Leakage-safe ML & invoice-aware cash-flow forecasting models, conformal intervals, and evaluation."""

from __future__ import annotations

import argparse
from bisect import bisect_right
import csv
from dataclasses import asdict, dataclass, field
from datetime import date, datetime, timedelta
from decimal import Decimal, localcontext
import hashlib
import io
import json
import math
from pathlib import Path
import platform
from typing import Sequence

import numpy as np
import scipy
import sklearn
from sklearn.ensemble import GradientBoostingRegressor
from sklearn.linear_model import Ridge
from sklearn.preprocessing import StandardScaler

from .baseline_data import (
    Dataset,
    History,
    MissingHistoryError,
    Observation,
    Series,
    load_dataset,
    money,
)
from . import cash_flow_models
from .cash_flow_models import (
    DRIFT_CALENDAR_ITERS,
    DRIFT_CALENDAR_K_DOM,
    DRIFT_CALENDAR_K_DOW,
    INTERVAL_LEVEL_80,
    INTERVAL_LEVEL_95,
    MAX_RESIDUAL_WINDOW,
    MIN_FIT_DAYS,
    MIN_RESIDUAL_COUNT,
    IntervalForecast,
    ModelPrediction,
    Split,
    ExcludedOrigin,
    chronological_split,
    conformal_radius,
    mae_rmse_me,
    metric_text,
    quantize_money,
    write_artifacts,
)


EXPERIMENT_VERSION = "CM2"
HORIZONS = (7, 30)
CM1_MODELS = ("naive", "drift_calendar", "ridge_lag_calendar")
ML_MODELS = ("gbdt_direct", "invoice_aware_ml", "ensemble_ml")
ALL_MODELS = (
    "naive",
    "drift_calendar",
    "ridge_lag_calendar",
    "gbdt_direct",
    "invoice_aware_ml",
    "ensemble_ml",
)
DEFAULT_TRAIN_END = date(2024, 12, 31)
DEFAULT_TEST_END = date(2025, 12, 31)
DEFAULT_DATA_DIR = Path("data/synthetic/FS1-20261018")
DEFAULT_OUTPUT_DIR = Path("forecasting/results/fs1-closing-balance-models-v2")

MODEL_VERSIONS: dict[str, str] = {
    **{model: "CM1" for model in CM1_MODELS},
    **{model: "CM2" for model in ML_MODELS},
}

MODEL_DESCRIPTIONS: dict[str, str] = {
    **cash_flow_models.MODEL_DESCRIPTIONS,
    "gbdt_direct": (
        "Direct multi-horizon gradient-boosted regression trees predicting cumulative net cash movement "
        "y(t+h)-y(t) from strictly causal lags, multi-day changes, rolling statistics, EWMA trends, "
        "and target-window calendar features fitted on history <= origin."
    ),
    "invoice_aware_ml": (
        "Hybrid StandardScaler + Ridge(alpha=50.0) and GBDT residual model combining causal cash-flow "
        "features with point-in-time open invoice schedules and shrunk empirical settlement lag/rate "
        "statistics reconstructed strictly from issue_date <= origin and booked_date <= origin."
    ),
    "ensemble_ml": (
        "Inverse-squared-MAE adaptive ensemble combining drift_calendar, gbdt_direct, and "
        "invoice_aware_ml (or drift_calendar and gbdt_direct when open invoices are omitted) using "
        "strictly past rolling out-of-sample residuals with split-conformal intervals."
    ),
}

DISCLAIMER_ML = (
    "Conditional mathematical estimates derived from historical daily closing cash balances and, when provided, "
    "point-in-time open invoice schedules. Outputs are not solvency proofs, liquidity guarantees, financial advice, "
    "or credit/lending decisions."
)

ML_MIN_LAG = 28
ML_MIN_TRAIN_PAIRS = 30
ML_BASE_MIN_FIT_DAYS = 90
MAX_ML_RESIDUAL_WINDOW = 180
RESIDUAL_REFIT_STRIDE = 7
MIN_WEIGHT_RESIDUALS = 10
MAX_WEIGHT_WINDOW = 90
RANDOM_STATE = 42

GBDT_PARAMS: dict[str, object] = {
    "n_estimators": 25,
    "learning_rate": 0.025,
    "max_depth": 2,
    "min_samples_leaf": 20,
    "subsample": 0.85,
    "max_features": "sqrt",
    "random_state": RANDOM_STATE,
}
INVOICE_RIDGE_ALPHA = 50.0
SETTLEMENT_PRIOR_K = 5.0
SETTLEMENT_PRIOR_LAG = 0.0
SETTLEMENT_PRIOR_ONTIME = 0.5
SETTLEMENT_PRIOR_OVERDUE_7D = 0.35
SETTLEMENT_PRIOR_OVERDUE_30D = 0.75

VALID_DIRECTIONS = frozenset({"INFLOW", "OUTFLOW"})
RECURRING_BILLING_DOMS = (1, 15, 20, 25)
ZERO_MONEY = Decimal("0.00")

CF_FEATURE_NAMES: tuple[str, ...] = (
    *(f"lag_net_{lag}" for lag in (0, 1, 2, 3, 6, 7, 13, 14, 27, 28)),
    *(f"delta_bal_{w}d" for w in (7, 14, 28, 56)),
    *(
        f"roll_{w}d_{stat}"
        for w in (7, 14, 28)
        for stat in ("mean", "std", "min", "max")
    ),
    "ewma_7",
    "ewma_28",
    "ewma_60",
    "ewma_28_x_h",
    "origin_dow",
    "origin_sin_dow",
    "origin_cos_dow",
    "origin_dom",
    "origin_sin_dom",
    "origin_cos_dom",
    "origin_is_month_end",
    "origin_is_qtr_end_month",
    "target_dow",
    "target_sin_dow",
    "target_cos_dow",
    "target_dom",
    "target_sin_dom",
    "target_cos_dom",
    "target_is_month_end",
    "target_is_qtr_end_month",
    *(f"win_dow_count_{d}" for d in range(7)),
    "win_business_days",
    "win_weekend_days",
    *(f"win_dom_{d}_count" for d in RECURRING_BILLING_DOMS),
    "win_month_end_count",
    "win_qtr_end_count",
    "win_causal_dom_sum",
    "win_causal_cal_sum",
)

INV_FEATURE_NAMES: tuple[str, ...] = (
    "inflow_due_7",
    "inflow_due_30",
    "inflow_due_h",
    "outflow_due_7",
    "outflow_due_30",
    "outflow_due_h",
    "net_due_7",
    "net_due_30",
    "net_due_h",
    "overdue_inflow",
    "overdue_outflow",
    "overdue_net",
    "open_inflow_total",
    "open_outflow_total",
    "open_inflow_count",
    "open_outflow_count",
    "open_total_count",
    "lag_adj_inflow_h",
    "lag_adj_outflow_h",
    "lag_adj_net_h",
    "emp_inflow_lag",
    "emp_outflow_lag",
    "emp_inflow_ontime",
    "emp_outflow_ontime",
)

ALL_INV_FEATURE_NAMES: tuple[str, ...] = CF_FEATURE_NAMES + INV_FEATURE_NAMES

CF_CORE_INDICES: tuple[int, ...] = tuple(
    CF_FEATURE_NAMES.index(name)
    for name in (
        "ewma_28_x_h",
        "win_business_days",
        "win_dom_1_count",
        "win_dom_15_count",
        "win_dom_20_count",
        "win_dom_25_count",
        "win_causal_dom_sum",
        "win_causal_cal_sum",
    )
)
INV_RIDGE_INDICES: tuple[int, ...] = CF_CORE_INDICES + tuple(
    range(len(CF_FEATURE_NAMES), len(ALL_INV_FEATURE_NAMES))
)
WIN_CAUSAL_CAL_SUM_IDX: int = CF_FEATURE_NAMES.index("win_causal_cal_sum")


def _validate_calendar_date(value: date, name: str) -> date:
    if not isinstance(value, date) or isinstance(value, datetime):
        raise ValueError(f"{name} must be a calendar date")
    return value


def _validate_direction(direction: str) -> str:
    if direction not in VALID_DIRECTIONS:
        raise ValueError(f"Invalid direction {direction!r}; expected 'INFLOW' or 'OUTFLOW'")
    return direction


@dataclass(frozen=True)
class OpenInvoice:
    direction: str
    due_date: date
    outstanding: Decimal

    def __post_init__(self) -> None:
        _validate_direction(self.direction)
        _validate_calendar_date(self.due_date, "due_date")
        amount = money(self.outstanding)
        if amount <= ZERO_MONEY:
            raise ValueError("OpenInvoice outstanding must be strictly positive")
        object.__setattr__(self, "outstanding", amount)


@dataclass(frozen=True)
class InvoiceRecord:
    invoice_key: str
    company_key: str
    currency: str
    direction: str
    amount: Decimal
    issue_date: date
    due_date: date

    def __post_init__(self) -> None:
        if not self.invoice_key or not self.company_key or not self.currency:
            raise ValueError("InvoiceRecord requires invoice_key, company_key, and currency")
        _validate_direction(self.direction)
        _validate_calendar_date(self.issue_date, "issue_date")
        _validate_calendar_date(self.due_date, "due_date")
        amount = money(self.amount)
        if amount <= ZERO_MONEY:
            raise ValueError("InvoiceRecord amount must be strictly positive")
        object.__setattr__(self, "amount", amount)


@dataclass(frozen=True)
class InvoicePayment:
    transaction_key: str
    company_key: str
    currency: str
    invoice_key: str
    booked_date: date
    direction: str
    amount: Decimal

    def __post_init__(self) -> None:
        if (
            not self.transaction_key
            or not self.company_key
            or not self.currency
            or not self.invoice_key
        ):
            raise ValueError("InvoicePayment requires non-empty keys and currency")
        _validate_direction(self.direction)
        _validate_calendar_date(self.booked_date, "booked_date")
        amount = money(self.amount)
        if amount <= ZERO_MONEY:
            raise ValueError("InvoicePayment amount must be strictly positive")
        object.__setattr__(self, "amount", amount)


@dataclass(frozen=True)
class SettlementStats:
    inflow_mean_lag: float
    inflow_median_lag: float
    inflow_ontime_rate: float
    inflow_overdue_7d_rate: float
    inflow_overdue_30d_rate: float
    inflow_resolved_count: int
    outflow_mean_lag: float
    outflow_median_lag: float
    outflow_ontime_rate: float
    outflow_overdue_7d_rate: float
    outflow_overdue_30d_rate: float
    outflow_resolved_count: int
    resolved_count: int


@dataclass(frozen=True)
class InvoiceLedger:
    company_key: str
    currency: str
    invoices: tuple[InvoiceRecord, ...]
    payments: tuple[InvoicePayment, ...]
    _sorted_invoices: tuple[InvoiceRecord, ...] = field(init=False, repr=False, compare=False)
    _issue_dates: tuple[date, ...] = field(init=False, repr=False, compare=False)
    _payments_by_invoice: dict[str, tuple[InvoicePayment, ...]] = field(
        init=False, repr=False, compare=False
    )

    def __post_init__(self) -> None:
        if not self.company_key or not self.currency:
            raise ValueError("InvoiceLedger requires company_key and currency")
        invs = tuple(sorted(self.invoices, key=lambda r: (r.issue_date, r.invoice_key)))
        pays = tuple(sorted(self.payments, key=lambda p: (p.booked_date, p.transaction_key)))
        by_inv: dict[str, list[InvoicePayment]] = {}
        for pay in pays:
            if pay.company_key != self.company_key or pay.currency != self.currency:
                raise ValueError("Payment company/currency mismatch in InvoiceLedger")
            by_inv.setdefault(pay.invoice_key, []).append(pay)
        for inv in invs:
            if inv.company_key != self.company_key or inv.currency != self.currency:
                raise ValueError("Invoice company/currency mismatch in InvoiceLedger")
        object.__setattr__(self, "invoices", tuple(self.invoices))
        object.__setattr__(self, "payments", tuple(self.payments))
        object.__setattr__(self, "_sorted_invoices", invs)
        object.__setattr__(self, "_issue_dates", tuple(inv.issue_date for inv in invs))
        object.__setattr__(
            self,
            "_payments_by_invoice",
            {key: tuple(items) for key, items in by_inv.items()},
        )

    def open_invoices_as_of(self, origin: date) -> tuple[OpenInvoice, ...]:
        """Strictly reconstruct open invoices as of origin using issue_date <= origin and booked_date <= origin."""
        _validate_calendar_date(origin, "origin")
        cutoff_idx = bisect_right(self._issue_dates, origin)
        result: list[OpenInvoice] = []
        for inv in self._sorted_invoices[:cutoff_idx]:
            paid = ZERO_MONEY
            for pay in self._payments_by_invoice.get(inv.invoice_key, ()):
                if pay.booked_date > origin:
                    break
                paid += pay.amount
            outstanding = inv.amount - paid
            if outstanding > ZERO_MONEY:
                result.append(OpenInvoice(inv.direction, inv.due_date, outstanding))
        return tuple(result)

    def empirical_settlement_stats(self, origin: date) -> SettlementStats:
        """Compute shrunk per-direction settlement statistics strictly from invoices resolved <= origin."""
        _validate_calendar_date(origin, "origin")
        cutoff_idx = bisect_right(self._issue_dates, origin)
        lags_by_dir: dict[str, list[int]] = {"INFLOW": [], "OUTFLOW": []}

        for inv in self._sorted_invoices[:cutoff_idx]:
            paid = ZERO_MONEY
            resolved_date: date | None = None
            for pay in self._payments_by_invoice.get(inv.invoice_key, ()):
                if pay.booked_date > origin:
                    break
                paid += pay.amount
                if paid >= inv.amount:
                    resolved_date = pay.booked_date
                    break
            if resolved_date is not None:
                lags_by_dir[inv.direction].append((resolved_date - inv.due_date).days)

        def _summarize_dir(lags: list[int]) -> tuple[float, float, float, float, float, int]:
            n = len(lags)
            k = SETTLEMENT_PRIOR_K
            if n == 0:
                return (
                    SETTLEMENT_PRIOR_LAG,
                    SETTLEMENT_PRIOR_LAG,
                    SETTLEMENT_PRIOR_ONTIME,
                    SETTLEMENT_PRIOR_OVERDUE_7D,
                    SETTLEMENT_PRIOR_OVERDUE_30D,
                    0,
                )
            mean_lag = (sum(lags) + k * SETTLEMENT_PRIOR_LAG) / (n + k)
            med_raw = float(np.median(lags))
            median_lag = (n * med_raw + k * SETTLEMENT_PRIOR_LAG) / (n + k)
            ontime = sum(1 for lag in lags if lag <= 0)
            ontime_rate = (ontime + k * SETTLEMENT_PRIOR_ONTIME) / (n + k)
            overdue_lags = [lag for lag in lags if lag > 0]
            n_ov = len(overdue_lags)
            ov7 = sum(1 for lag in overdue_lags if lag <= 7)
            ov30 = sum(1 for lag in overdue_lags if lag <= 30)
            ov7_rate = (ov7 + k * SETTLEMENT_PRIOR_OVERDUE_7D) / (n_ov + k)
            ov30_rate = (ov30 + k * SETTLEMENT_PRIOR_OVERDUE_30D) / (n_ov + k)
            return mean_lag, median_lag, ontime_rate, ov7_rate, ov30_rate, n

        in_stats = _summarize_dir(lags_by_dir["INFLOW"])
        out_stats = _summarize_dir(lags_by_dir["OUTFLOW"])
        return SettlementStats(
            inflow_mean_lag=in_stats[0],
            inflow_median_lag=in_stats[1],
            inflow_ontime_rate=in_stats[2],
            inflow_overdue_7d_rate=in_stats[3],
            inflow_overdue_30d_rate=in_stats[4],
            inflow_resolved_count=in_stats[5],
            outflow_mean_lag=out_stats[0],
            outflow_median_lag=out_stats[1],
            outflow_ontime_rate=out_stats[2],
            outflow_overdue_7d_rate=out_stats[3],
            outflow_overdue_30d_rate=out_stats[4],
            outflow_resolved_count=out_stats[5],
            resolved_count=in_stats[5] + out_stats[5],
        )


@dataclass(frozen=True)
class MLForecastBundle:
    forecasts: dict[int, IntervalForecast]
    uses_open_invoices: bool
    open_invoice_count: int | None
    active_members: tuple[str, ...] | None
    ensemble_weights: dict[int, dict[str, float]] | None
    description: str


@dataclass(frozen=True)
class MLEvaluation:
    splits: tuple[Split, ...]
    origins: tuple[date, ...]
    excluded: tuple[ExcludedOrigin, ...]
    predictions: tuple[ModelPrediction, ...]
    train_scales: dict[str, Decimal]
    feature_importances: dict[str, dict[int, dict[str, float]]]
    ensemble_weights_summary: dict[int, dict[str, dict[str, float]]]


def load_invoice_ledgers(directory: Path) -> dict[str, InvoiceLedger]:
    """Load point-in-time invoice ledgers after manifest SHA-256 verification."""
    dataset = load_dataset(directory)
    sme_currency = {item.company_key: item.currency for item in dataset.series}

    invoices_by_sme: dict[str, list[InvoiceRecord]] = {key: [] for key in sme_currency}
    payments_by_sme: dict[str, list[InvoicePayment]] = {key: [] for key in sme_currency}

    invoices_path = directory / "invoices.csv"
    with invoices_path.open(encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        for row in reader:
            if row.get("is_synthetic") != "true":
                raise ValueError("invoices.csv: all records must be explicitly synthetic")
            company_key = row["company_key"]
            currency = row["currency"]
            if company_key not in sme_currency or currency != sme_currency[company_key]:
                raise ValueError(f"invoices.csv: unknown SME or mismatched currency for {company_key}")
            invoices_by_sme[company_key].append(
                InvoiceRecord(
                    invoice_key=row["invoice_key"],
                    company_key=company_key,
                    currency=currency,
                    direction=row["direction"],
                    amount=money(row["amount"]),
                    issue_date=date.fromisoformat(row["issue_date"]),
                    due_date=date.fromisoformat(row["due_date"]),
                )
            )

    transactions_path = directory / "transactions.csv"
    with transactions_path.open(encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        for row in reader:
            if row.get("is_synthetic") != "true":
                raise ValueError("transactions.csv: all records must be explicitly synthetic")
            invoice_key = (row.get("invoice_key") or "").strip()
            if not invoice_key:
                continue
            company_key = row["company_key"]
            currency = row["currency"]
            if company_key not in sme_currency or currency != sme_currency[company_key]:
                raise ValueError(f"transactions.csv: unknown SME or mismatched currency for {company_key}")
            payments_by_sme[company_key].append(
                InvoicePayment(
                    transaction_key=row["transaction_key"],
                    company_key=company_key,
                    currency=currency,
                    invoice_key=invoice_key,
                    booked_date=date.fromisoformat(row["booked_date"]),
                    direction=row["direction"],
                    amount=money(row["amount"]),
                )
            )

    return {
        key: InvoiceLedger(
            company_key=key,
            currency=sme_currency[key],
            invoices=tuple(invoices_by_sme[key]),
            payments=tuple(payments_by_sme[key]),
        )
        for key in sorted(sme_currency)
    }


def _validate_horizons(horizons: Sequence[int]) -> tuple[int, ...]:
    if not horizons:
        raise ValueError("At least one forecast horizon is required")
    validated: list[int] = []
    for horizon in horizons:
        if not isinstance(horizon, int) or isinstance(horizon, bool) or horizon < 1:
            raise ValueError("Horizons must be positive integers")
        validated.append(horizon)
    return tuple(validated)


def _validate_model_and_horizons_all(model: str, horizons: Sequence[int]) -> tuple[int, ...]:
    if model not in ALL_MODELS:
        raise ValueError(f"Unknown model {model!r}; expected one of {ALL_MODELS}")
    return _validate_horizons(horizons)


def min_fit_days_for_horizon(model: str, max_horizon: int) -> int:
    if model not in ALL_MODELS:
        raise ValueError(f"Unknown model {model!r}; expected one of {ALL_MODELS}")
    if not isinstance(max_horizon, int) or isinstance(max_horizon, bool) or max_horizon < 1:
        raise ValueError("max_horizon must be a positive integer")
    if model in CM1_MODELS:
        return MIN_FIT_DAYS[model]
    return max(ML_BASE_MIN_FIT_DAYS, ML_MIN_LAG + max_horizon + ML_MIN_TRAIN_PAIRS)


def min_required_history_days_all(model: str, max_horizon: int) -> int:
    """Minimum consecutive observed calendar days required for point + conformal interval prediction."""
    if model not in ALL_MODELS:
        raise ValueError(f"Unknown model {model!r}; expected one of {ALL_MODELS}")
    if not isinstance(max_horizon, int) or isinstance(max_horizon, bool) or max_horizon < 1:
        raise ValueError("max_horizon must be a positive integer")
    if model in CM1_MODELS:
        return cash_flow_models.min_required_history_days(model, max_horizon)
    return min_fit_days_for_horizon(model, max_horizon) + max_horizon + MIN_RESIDUAL_COUNT - 1


def _is_month_end(d: date) -> bool:
    return (d + timedelta(days=1)).day == 1


def _is_quarter_end_month(d: date) -> bool:
    return d.month in (3, 6, 9, 12)


def _is_quarter_end_day(d: date) -> bool:
    return _is_month_end(d) and _is_quarter_end_month(d)


@dataclass
class _CausalSeriesCache:
    """Precomputed strictly-causal per-index quantities for obs[0..n-1]."""

    days: tuple[date, ...]
    closes: np.ndarray
    nets: np.ndarray
    ewma_7: np.ndarray
    ewma_28: np.ndarray
    ewma_60: np.ndarray
    causal_dom_beta: np.ndarray  # shape (n, 32)
    causal_dow_alpha: np.ndarray  # shape (n, 7)
    causal_mu: np.ndarray  # shape (n,)

    @classmethod
    def from_observations(cls, observations: Sequence[Observation]) -> "_CausalSeriesCache":
        n = len(observations)
        days = tuple(obs.day for obs in observations)
        closes = np.fromiter((float(obs.closing) for obs in observations), dtype=np.float64, count=n)
        nets = np.zeros(n, dtype=np.float64)
        if n > 1:
            nets[1:] = closes[1:] - closes[:-1]

        ewma_7 = np.zeros(n, dtype=np.float64)
        ewma_28 = np.zeros(n, dtype=np.float64)
        ewma_60 = np.zeros(n, dtype=np.float64)
        a7 = 2.0 / (7.0 + 1.0)
        a28 = 2.0 / (28.0 + 1.0)
        a60 = 2.0 / (60.0 + 1.0)

        causal_dom_beta = np.zeros((n, 32), dtype=np.float64)
        causal_dow_alpha = np.zeros((n, 7), dtype=np.float64)
        causal_mu = np.zeros(n, dtype=np.float64)

        dom_sum = np.zeros(32, dtype=np.float64)
        dom_cnt = np.zeros(32, dtype=np.float64)
        dow_sum = np.zeros(7, dtype=np.float64)
        dow_cnt = np.zeros(7, dtype=np.float64)
        total_net = 0.0

        for idx in range(1, n):
            net = nets[idx]
            if idx == 1:
                ewma_7[idx] = net
                ewma_28[idx] = net
                ewma_60[idx] = net
            else:
                ewma_7[idx] = a7 * net + (1.0 - a7) * ewma_7[idx - 1]
                ewma_28[idx] = a28 * net + (1.0 - a28) * ewma_28[idx - 1]
                ewma_60[idx] = a60 * net + (1.0 - a60) * ewma_60[idx - 1]

            d = days[idx]
            dom_sum[d.day] += net
            dom_cnt[d.day] += 1.0
            dow_sum[d.weekday()] += net
            dow_cnt[d.weekday()] += 1.0
            total_net += net
            mu = total_net / idx
            causal_mu[idx] = mu

            beta = np.zeros(32, dtype=np.float64)
            active_dom = dom_cnt > 0
            beta[active_dom] = (dom_sum[active_dom] - mu * dom_cnt[active_dom]) / (
                dom_cnt[active_dom] + DRIFT_CALENDAR_K_DOM
            )
            mean_beta = float(np.sum(beta * dom_cnt)) / idx
            beta[active_dom] -= mean_beta
            causal_dom_beta[idx] = beta

            alpha = np.zeros(7, dtype=np.float64)
            active_dow = dow_cnt > 0
            alpha[active_dow] = (dow_sum[active_dow] - mu * dow_cnt[active_dow]) / (
                dow_cnt[active_dow] + DRIFT_CALENDAR_K_DOW
            )
            mean_alpha = float(np.sum(alpha * dow_cnt)) / idx
            alpha[active_dow] -= mean_alpha
            causal_dow_alpha[idx] = alpha

        return cls(
            days=days,
            closes=closes,
            nets=nets,
            ewma_7=ewma_7,
            ewma_28=ewma_28,
            ewma_60=ewma_60,
            causal_dom_beta=causal_dom_beta,
            causal_dow_alpha=causal_dow_alpha,
            causal_mu=causal_mu,
        )

    def build_cf_features(self, s_idx: int, horizon: int) -> np.ndarray:
        """Build causal cash-flow features at (s_idx, horizon) strictly using <= s_idx."""
        feat = np.zeros(len(CF_FEATURE_NAMES), dtype=np.float64)
        col = 0
        nets = self.nets
        closes = self.closes

        # Daily net lags: 0, 1, 2, 3, 6, 7, 13, 14, 27, 28
        for lag in (0, 1, 2, 3, 6, 7, 13, 14, 27, 28):
            pos = s_idx - lag
            feat[col] = nets[pos] if pos >= 1 else 0.0
            col += 1

        # Multi-day balance changes: 7d, 14d, 28d, 56d
        for w in (7, 14, 28, 56):
            if s_idx >= w:
                feat[col] = closes[s_idx] - closes[s_idx - w]
            elif s_idx > 0:
                feat[col] = (closes[s_idx] - closes[0]) * (w / s_idx)
            col += 1

        # Rolling mean, std, min, max over trailing 7, 14, 28 days
        for w in (7, 14, 28):
            start = max(1, s_idx - w + 1)
            window_vals = nets[start : s_idx + 1]
            feat[col] = float(np.mean(window_vals))
            feat[col + 1] = float(np.std(window_vals))
            feat[col + 2] = float(np.min(window_vals))
            feat[col + 3] = float(np.max(window_vals))
            col += 4

        # Exponential trends
        feat[col] = self.ewma_7[s_idx]
        feat[col + 1] = self.ewma_28[s_idx]
        feat[col + 2] = self.ewma_60[s_idx]
        feat[col + 3] = self.ewma_28[s_idx] * horizon
        col += 4

        # Origin & target calendar features
        origin_day = self.days[s_idx]
        target_day = origin_day + timedelta(days=horizon)
        for d in (origin_day, target_day):
            dow = d.weekday()
            dom = d.day
            feat[col] = float(dow)
            feat[col + 1] = math.sin(2.0 * math.pi * dow / 7.0)
            feat[col + 2] = math.cos(2.0 * math.pi * dow / 7.0)
            feat[col + 3] = float(dom)
            feat[col + 4] = math.sin(2.0 * math.pi * (dom - 1) / 31.0)
            feat[col + 5] = math.cos(2.0 * math.pi * (dom - 1) / 31.0)
            feat[col + 6] = 1.0 if _is_month_end(d) else 0.0
            feat[col + 7] = 1.0 if _is_quarter_end_month(d) else 0.0
            col += 8

        # Horizon window (s, s+h] calendar counts and causal calendar sums
        dow_counts = [0.0] * 7
        biz_days = 0.0
        wknd_days = 0.0
        dom_counts = {d_val: 0.0 for d_val in RECURRING_BILLING_DOMS}
        month_ends = 0.0
        qtr_ends = 0.0
        causal_dom_sum = 0.0
        causal_cal_sum = 0.0
        beta = self.causal_dom_beta[s_idx]
        alpha = self.causal_dow_alpha[s_idx]
        mu = self.causal_mu[s_idx]

        for step in range(1, horizon + 1):
            fd = origin_day + timedelta(days=step)
            wday = fd.weekday()
            dow_counts[wday] += 1.0
            if wday < 5:
                biz_days += 1.0
            else:
                wknd_days += 1.0
            if fd.day in dom_counts:
                dom_counts[fd.day] += 1.0
            if _is_month_end(fd):
                month_ends += 1.0
                if _is_quarter_end_month(fd):
                    qtr_ends += 1.0
            causal_dom_sum += beta[fd.day]
            causal_cal_sum += mu + alpha[wday] + beta[fd.day]

        for wday in range(7):
            feat[col + wday] = dow_counts[wday]
        col += 7
        feat[col] = biz_days
        feat[col + 1] = wknd_days
        col += 2
        for d_val in RECURRING_BILLING_DOMS:
            feat[col] = dom_counts[d_val]
            col += 1
        feat[col] = month_ends
        feat[col + 1] = qtr_ends
        feat[col + 2] = causal_dom_sum
        feat[col + 3] = causal_cal_sum
        return feat


def _build_invoice_feature_vector(
    origin: date,
    horizon: int,
    open_invoices: Sequence[OpenInvoice],
    stats: SettlementStats,
) -> np.ndarray:
    """Construct X_inv(origin, horizon) strictly from point-in-time open invoices and settlement stats."""
    inflow_due_7 = 0.0
    inflow_due_30 = 0.0
    inflow_due_h = 0.0
    outflow_due_7 = 0.0
    outflow_due_30 = 0.0
    outflow_due_h = 0.0
    overdue_inflow = 0.0
    overdue_outflow = 0.0
    open_inflow_total = 0.0
    open_outflow_total = 0.0
    open_inflow_count = 0.0
    open_outflow_count = 0.0
    lag_adj_inflow_h = 0.0
    lag_adj_outflow_h = 0.0

    due_7 = origin + timedelta(days=7)
    due_30 = origin + timedelta(days=30)
    due_h = origin + timedelta(days=horizon)

    inflow_lag_shift = int(round(stats.inflow_mean_lag))
    outflow_lag_shift = int(round(stats.outflow_mean_lag))
    overdue_collect_rate_in = (
        stats.inflow_overdue_7d_rate if horizon <= 7 else stats.inflow_overdue_30d_rate
    )
    overdue_collect_rate_out = (
        stats.outflow_overdue_7d_rate if horizon <= 7 else stats.outflow_overdue_30d_rate
    )

    for inv in open_invoices:
        amt = float(inv.outstanding)
        if inv.direction == "INFLOW":
            open_inflow_total += amt
            open_inflow_count += 1.0
            if inv.due_date <= origin:
                overdue_inflow += amt
                lag_adj_inflow_h += amt * overdue_collect_rate_in
            else:
                if inv.due_date <= due_7:
                    inflow_due_7 += amt
                if inv.due_date <= due_30:
                    inflow_due_30 += amt
                if inv.due_date <= due_h:
                    inflow_due_h += amt
                expected_pay_date = inv.due_date + timedelta(days=inflow_lag_shift)
                if expected_pay_date <= due_h:
                    lag_adj_inflow_h += amt
        else:
            open_outflow_total += amt
            open_outflow_count += 1.0
            if inv.due_date <= origin:
                overdue_outflow += amt
                lag_adj_outflow_h += amt * overdue_collect_rate_out
            else:
                if inv.due_date <= due_7:
                    outflow_due_7 += amt
                if inv.due_date <= due_30:
                    outflow_due_30 += amt
                if inv.due_date <= due_h:
                    outflow_due_h += amt
                expected_pay_date = inv.due_date + timedelta(days=outflow_lag_shift)
                if expected_pay_date <= due_h:
                    lag_adj_outflow_h += amt

    return np.array(
        [
            inflow_due_7,
            inflow_due_30,
            inflow_due_h,
            outflow_due_7,
            outflow_due_30,
            outflow_due_h,
            inflow_due_7 - outflow_due_7,
            inflow_due_30 - outflow_due_30,
            inflow_due_h - outflow_due_h,
            overdue_inflow,
            overdue_outflow,
            overdue_inflow - overdue_outflow,
            open_inflow_total,
            open_outflow_total,
            open_inflow_count,
            open_outflow_count,
            open_inflow_count + open_outflow_count,
            lag_adj_inflow_h,
            lag_adj_outflow_h,
            lag_adj_inflow_h - lag_adj_outflow_h,
            stats.inflow_mean_lag,
            stats.outflow_mean_lag,
            stats.inflow_ontime_rate,
            stats.outflow_ontime_rate,
        ],
        dtype=np.float64,
    )


def _compute_ledger_timeline(
    days: tuple[date, ...],
    ledger: InvoiceLedger,
) -> tuple[list[tuple[OpenInvoice, ...]], list[SettlementStats]]:
    """Chronologically sweep events <= days[idx] to produce point-in-time open invoices and stats."""
    n = len(days)
    open_by_idx: list[tuple[OpenInvoice, ...]] = [() for _ in range(n)]
    stats_by_idx: list[SettlementStats] = []

    invs = ledger._sorted_invoices
    pays = tuple(sorted(ledger.payments, key=lambda p: (p.booked_date, p.transaction_key)))
    inv_ptr = 0
    pay_ptr = 0
    n_invs = len(invs)
    n_pays = len(pays)

    active: dict[str, tuple[InvoiceRecord, Decimal]] = {}
    lags_by_dir: dict[str, list[int]] = {"INFLOW": [], "OUTFLOW": []}

    def _stats_snapshot() -> SettlementStats:
        def _dir(lags: list[int]) -> tuple[float, float, float, float, float, int]:
            cnt = len(lags)
            k = SETTLEMENT_PRIOR_K
            if cnt == 0:
                return (
                    SETTLEMENT_PRIOR_LAG,
                    SETTLEMENT_PRIOR_LAG,
                    SETTLEMENT_PRIOR_ONTIME,
                    SETTLEMENT_PRIOR_OVERDUE_7D,
                    SETTLEMENT_PRIOR_OVERDUE_30D,
                    0,
                )
            mean_lag = (sum(lags) + k * SETTLEMENT_PRIOR_LAG) / (cnt + k)
            med_lag = (cnt * float(np.median(lags)) + k * SETTLEMENT_PRIOR_LAG) / (cnt + k)
            ontime = sum(1 for lag in lags if lag <= 0)
            ontime_rate = (ontime + k * SETTLEMENT_PRIOR_ONTIME) / (cnt + k)
            ov = [lag for lag in lags if lag > 0]
            n_ov = len(ov)
            ov7 = sum(1 for lag in ov if lag <= 7)
            ov30 = sum(1 for lag in ov if lag <= 30)
            return (
                mean_lag,
                med_lag,
                ontime_rate,
                (ov7 + k * SETTLEMENT_PRIOR_OVERDUE_7D) / (n_ov + k),
                (ov30 + k * SETTLEMENT_PRIOR_OVERDUE_30D) / (n_ov + k),
                cnt,
            )

        in_s = _dir(lags_by_dir["INFLOW"])
        out_s = _dir(lags_by_dir["OUTFLOW"])
        return SettlementStats(
            inflow_mean_lag=in_s[0],
            inflow_median_lag=in_s[1],
            inflow_ontime_rate=in_s[2],
            inflow_overdue_7d_rate=in_s[3],
            inflow_overdue_30d_rate=in_s[4],
            inflow_resolved_count=in_s[5],
            outflow_mean_lag=out_s[0],
            outflow_median_lag=out_s[1],
            outflow_ontime_rate=out_s[2],
            outflow_overdue_7d_rate=out_s[3],
            outflow_overdue_30d_rate=out_s[4],
            outflow_resolved_count=out_s[5],
            resolved_count=in_s[5] + out_s[5],
        )

    # Track payments arriving on invoices not yet added or already resolved cleanly
    pre_paid: dict[str, Decimal] = {}
    for idx, day in enumerate(days):
        while inv_ptr < n_invs and invs[inv_ptr].issue_date <= day:
            inv = invs[inv_ptr]
            paid = pre_paid.pop(inv.invoice_key, ZERO_MONEY)
            outstanding = inv.amount - paid
            if outstanding > ZERO_MONEY:
                active[inv.invoice_key] = (inv, outstanding)
            inv_ptr += 1

        while pay_ptr < n_pays and pays[pay_ptr].booked_date <= day:
            pay = pays[pay_ptr]
            entry = active.get(pay.invoice_key)
            if entry is not None:
                inv, rem = entry
                new_rem = rem - pay.amount
                if new_rem > ZERO_MONEY:
                    active[pay.invoice_key] = (inv, new_rem)
                else:
                    del active[pay.invoice_key]
                    lags_by_dir[inv.direction].append((pay.booked_date - inv.due_date).days)
            else:
                # Payment before issue_date (if any) or after full payment
                pre_paid[pay.invoice_key] = pre_paid.get(pay.invoice_key, ZERO_MONEY) + pay.amount
            pay_ptr += 1

        open_by_idx[idx] = tuple(
            OpenInvoice(inv.direction, inv.due_date, rem) for inv, rem in active.values()
        )
        stats_by_idx.append(_stats_snapshot())

    return open_by_idx, stats_by_idx


@dataclass
class _FittedGBDTDirect:
    model: GradientBoostingRegressor
    feature_importances: np.ndarray

    def predict(self, x_row: np.ndarray) -> float:
        base = float(x_row[WIN_CAUSAL_CAL_SUM_IDX])
        return base + float(self.model.predict(x_row.reshape(1, -1))[0])


@dataclass
class _FittedInvoiceAware:
    scaler: StandardScaler
    ridge: Ridge
    gbdt: GradientBoostingRegressor
    feature_importances: np.ndarray

    def predict(self, x_row: np.ndarray) -> float:
        x_2d = x_row.reshape(1, -1)
        x_scaled = self.scaler.transform(x_2d[:, INV_RIDGE_INDICES])
        return float(self.ridge.predict(x_scaled)[0] + self.gbdt.predict(x_2d)[0])


def _fit_gbdt_direct(
    X_all: np.ndarray,
    y_all: np.ndarray,
    fit_idx: int,
    horizon: int,
) -> _FittedGBDTDirect:
    end_s = fit_idx - horizon
    n_rows = end_s - ML_MIN_LAG + 1
    if n_rows < ML_MIN_TRAIN_PAIRS:
        raise MissingHistoryError(
            f"gbdt_direct requires at least {ML_MIN_TRAIN_PAIRS} training pairs; got {n_rows}"
        )
    X_train = X_all[ML_MIN_LAG : end_s + 1]
    y_train = y_all[ML_MIN_LAG : end_s + 1]
    y_residual = y_train - X_train[:, WIN_CAUSAL_CAL_SUM_IDX]
    reg = GradientBoostingRegressor(**GBDT_PARAMS)
    reg.fit(X_train, y_residual)
    return _FittedGBDTDirect(model=reg, feature_importances=reg.feature_importances_.copy())


def _fit_invoice_aware(
    X_all: np.ndarray,
    y_all: np.ndarray,
    fit_idx: int,
    horizon: int,
) -> _FittedInvoiceAware:
    end_s = fit_idx - horizon
    n_rows = end_s - ML_MIN_LAG + 1
    if n_rows < ML_MIN_TRAIN_PAIRS:
        raise MissingHistoryError(
            f"invoice_aware_ml requires at least {ML_MIN_TRAIN_PAIRS} training pairs; got {n_rows}"
        )
    X_train = X_all[ML_MIN_LAG : end_s + 1]
    y_train = y_all[ML_MIN_LAG : end_s + 1]
    scaler = StandardScaler()
    X_scaled = scaler.fit_transform(X_train[:, INV_RIDGE_INDICES])
    ridge = Ridge(alpha=INVOICE_RIDGE_ALPHA)
    ridge.fit(X_scaled, y_train)
    residuals = y_train - ridge.predict(X_scaled)
    gbdt = GradientBoostingRegressor(**GBDT_PARAMS)
    gbdt.fit(X_train, residuals)
    return _FittedInvoiceAware(
        scaler=scaler,
        ridge=ridge,
        gbdt=gbdt,
        feature_importances=gbdt.feature_importances_.copy(),
    )


def _live_open_invoice_adjustment(
    cache: _CausalSeriesCache,
    origin_idx: int,
    horizon: int,
    open_invoices: Sequence[OpenInvoice],
) -> float:
    """Conservative bounded open-invoice schedule adjustment for Live API Snapshot mode."""
    if not open_invoices:
        return 0.0
    origin = cache.days[origin_idx]
    due_h = origin + timedelta(days=horizon)
    net_due_h = 0.0
    net_overdue = 0.0
    for inv in open_invoices:
        signed = float(inv.outstanding) if inv.direction == "INFLOW" else -float(inv.outstanding)
        if inv.due_date <= origin:
            net_overdue += signed
        elif inv.due_date <= due_h:
            net_due_h += signed

    schedule_signal = net_due_h + 0.25 * net_overdue
    closes = cache.closes[: origin_idx + 1]
    h_diffs = closes[horizon:] - closes[:-horizon]
    scale_h = max(1.0, float(np.std(h_diffs)) if len(h_diffs) > 1 else 1.0)
    max_adj = 0.35 * scale_h
    return max_adj * math.tanh(schedule_signal / scale_h)


def _strided_fit_index(s_idx: int, min_fit_idx: int) -> int:
    return min_fit_idx + ((s_idx - min_fit_idx) // RESIDUAL_REFIT_STRIDE) * RESIDUAL_REFIT_STRIDE


def _normalize_open_invoices(open_invoices: Sequence[OpenInvoice] | None) -> tuple[OpenInvoice, ...] | None:
    if open_invoices is None:
        return None
    normalized: list[OpenInvoice] = []
    for item in open_invoices:
        if isinstance(item, OpenInvoice):
            normalized.append(item)
        else:
            raise ValueError("open_invoices entries must be OpenInvoice instances")
    return tuple(normalized)


def _compute_inverse_mae_weights(
    member_errors: dict[str, Sequence[float]],
    members: tuple[str, ...],
) -> dict[str, float]:
    k = len(members)
    if any(len(member_errors[m]) < MIN_WEIGHT_RESIDUALS for m in members):
        equal = 1.0 / k
        return {m: equal for m in members}
    raw: dict[str, float] = {}
    for m in members:
        mae_m = sum(member_errors[m]) / len(member_errors[m])
        raw[m] = (1.0 / (mae_m + 1e-6)) ** 2
    total = sum(raw.values())
    if total <= 0.0 or not math.isfinite(total):
        equal = 1.0 / k
        return {m: equal for m in members}
    return {m: raw[m] / total for m in members}


def _build_cf_matrix_for_horizon(cache: _CausalSeriesCache, max_idx: int, horizon: int) -> tuple[np.ndarray, np.ndarray]:
    n = max_idx + 1
    X = np.zeros((n, len(CF_FEATURE_NAMES)), dtype=np.float64)
    y = np.zeros(n, dtype=np.float64)
    for s in range(ML_MIN_LAG, n):
        X[s] = cache.build_cf_features(s, horizon)
        if s + horizon < n:
            y[s] = cache.closes[s + horizon] - cache.closes[s]
    return X, y


def _build_inv_matrix_for_horizon(
    cache: _CausalSeriesCache,
    X_cf: np.ndarray,
    open_by_idx: list[tuple[OpenInvoice, ...]],
    stats_by_idx: list[SettlementStats],
    max_idx: int,
    horizon: int,
) -> np.ndarray:
    n = max_idx + 1
    X_inv = np.zeros((n, len(INV_FEATURE_NAMES)), dtype=np.float64)
    for s in range(ML_MIN_LAG, n):
        X_inv[s] = _build_invoice_feature_vector(
            cache.days[s], horizon, open_by_idx[s], stats_by_idx[s]
        )
    return np.hstack((X_cf, X_inv))


def _forecast_single_series_ml(
    history: History,
    horizons: tuple[int, ...],
    model: str,
    invoice_ledger: InvoiceLedger | None,
    open_invoices: tuple[OpenInvoice, ...] | None,
    need_intervals: bool,
) -> MLForecastBundle:
    if invoice_ledger is not None and open_invoices is not None:
        raise ValueError("Specify at most one of invoice_ledger or open_invoices")

    max_h = max(horizons)
    obs = history.observations
    n = len(obs)
    min_fit_days = min_fit_days_for_horizon(model, max_h)
    required_days = (
        min_required_history_days_all(model, max_h) if need_intervals else min_fit_days
    )
    if n < required_days:
        raise MissingHistoryError(
            f"Model {model!r} at max horizon {max_h} requires at least {required_days} consecutive days; "
            f"received {n}"
        )

    if model in CM1_MODELS:
        if need_intervals:
            forecasts = cash_flow_models.predict_with_intervals(history, horizons, model)
        else:
            points = cash_flow_models.predict_horizons(history, horizons, model)
            forecasts = {
                h: IntervalForecast(
                    horizon_days=h,
                    target_date=history.origin + timedelta(days=h),
                    predicted=points[h],
                    lower_80=points[h],
                    upper_80=points[h],
                    lower_95=points[h],
                    upper_95=points[h],
                    residual_count=0,
                )
                for h in horizons
            }
        return MLForecastBundle(
            forecasts=forecasts,
            uses_open_invoices=False,
            open_invoice_count=None,
            active_members=None,
            ensemble_weights=None,
            description=MODEL_DESCRIPTIONS[model],
        )

    if model == "invoice_aware_ml" and invoice_ledger is None and open_invoices is None:
        raise ValueError(
            "Model 'invoice_aware_ml' requires 'open_invoices' (pass [] if none are open as of origin)"
        )

    t_idx = n - 1
    min_fit_idx = min_fit_days - 1
    cache = _CausalSeriesCache.from_observations(obs)

    has_invoices = invoice_ledger is not None or open_invoices is not None
    open_count: int | None = None
    open_by_idx: list[tuple[OpenInvoice, ...]] | None = None
    stats_by_idx: list[SettlementStats] | None = None
    if invoice_ledger is not None:
        open_by_idx, stats_by_idx = _compute_ledger_timeline(cache.days, invoice_ledger)
        open_count = len(open_by_idx[t_idx])
    elif open_invoices is not None:
        open_count = len(open_invoices)

    if model == "ensemble_ml":
        active_members: tuple[str, ...] = (
            ("drift_calendar", "gbdt_direct", "invoice_aware_ml")
            if has_invoices
            else ("drift_calendar", "gbdt_direct")
        )
    else:
        active_members = None

    def _run_gbdt_for_h(h: int, needed_oos: Sequence[int]) -> tuple[Decimal, dict[int, Decimal]]:
        X_cf, y = _build_cf_matrix_for_horizon(cache, t_idx, h)
        fresh = _fit_gbdt_direct(X_cf, y, t_idx, h)
        point_pred = quantize_money(cache.closes[t_idx] + fresh.predict(X_cf[t_idx]))
        oos_preds: dict[int, Decimal] = {}
        fit_cache: dict[int, _FittedGBDTDirect] = {}
        for s_idx in needed_oos:
            f_idx = _strided_fit_index(s_idx, min_fit_idx)
            fitted = fit_cache.get(f_idx)
            if fitted is None:
                fitted = _fit_gbdt_direct(X_cf, y, f_idx, h)
                fit_cache[f_idx] = fitted
            oos_preds[s_idx] = quantize_money(cache.closes[s_idx] + fitted.predict(X_cf[s_idx]))
        return point_pred, oos_preds

    def _run_inv_for_h(h: int, needed_oos: Sequence[int]) -> tuple[Decimal, dict[int, Decimal]]:
        if invoice_ledger is not None:
            assert open_by_idx is not None and stats_by_idx is not None
            X_cf, y = _build_cf_matrix_for_horizon(cache, t_idx, h)
            X_full = _build_inv_matrix_for_horizon(cache, X_cf, open_by_idx, stats_by_idx, t_idx, h)
            fresh = _fit_invoice_aware(X_full, y, t_idx, h)
            point_pred = quantize_money(cache.closes[t_idx] + fresh.predict(X_full[t_idx]))
            oos_preds: dict[int, Decimal] = {}
            fit_cache: dict[int, _FittedInvoiceAware] = {}
            for s_idx in needed_oos:
                f_idx = _strided_fit_index(s_idx, min_fit_idx)
                fitted = fit_cache.get(f_idx)
                if fitted is None:
                    fitted = _fit_invoice_aware(X_full, y, f_idx, h)
                    fit_cache[f_idx] = fitted
                oos_preds[s_idx] = quantize_money(cache.closes[s_idx] + fitted.predict(X_full[s_idx]))
            return point_pred, oos_preds
        # Live API snapshot mode
        assert open_invoices is not None
        gbdt_point, gbdt_oos = _run_gbdt_for_h(h, needed_oos)
        adj = _live_open_invoice_adjustment(cache, t_idx, h, open_invoices)
        return quantize_money(float(gbdt_point) + adj), gbdt_oos

    forecasts: dict[int, IntervalForecast] = {}
    ensemble_weights_out: dict[int, dict[str, float]] | None = {} if model == "ensemble_ml" else None

    for h in horizons:
        res_start = max(min_fit_idx, t_idx - h - (MAX_ML_RESIDUAL_WINDOW - 1))
        res_end = t_idx - h + 1
        res_indices = list(range(res_start, res_end)) if need_intervals else []

        if model == "gbdt_direct":
            point, oos = _run_gbdt_for_h(h, res_indices)
        elif model == "invoice_aware_ml":
            point, oos = _run_inv_for_h(h, res_indices)
        else:
            # ensemble_ml
            assert active_members is not None
            if need_intervals:
                # Indices s_idx for conformal residuals, plus u_idx for weights at t_idx and each s_idx
                weight_start_t = max(min_fit_idx, t_idx - h - (MAX_WEIGHT_WINDOW - 1))
                weight_indices_t = list(range(weight_start_t, t_idx - h + 1))
                earliest_u = (
                    max(min_fit_idx, res_start - h - (MAX_WEIGHT_WINDOW - 1))
                    if res_indices
                    else weight_start_t
                )
                all_member_indices = list(range(earliest_u, t_idx - h + 1))
            else:
                weight_start_t = max(min_fit_idx, t_idx - h - (MAX_WEIGHT_WINDOW - 1))
                weight_indices_t = list(range(weight_start_t, t_idx - h + 1))
                all_member_indices = weight_indices_t

            member_points: dict[str, Decimal] = {}
            member_oos: dict[str, dict[int, Decimal]] = {}

            # drift_calendar
            sub_h_t = History(obs[t_idx].day, obs[: t_idx + 1])
            member_points["drift_calendar"] = cash_flow_models.predict_horizons(
                sub_h_t, (h,), "drift_calendar"
            )[h]
            member_oos["drift_calendar"] = {
                idx: cash_flow_models.predict_horizons(
                    History(obs[idx].day, obs[: idx + 1]), (h,), "drift_calendar"
                )[h]
                for idx in all_member_indices
            }

            # gbdt_direct
            gbdt_pt, gbdt_oos = _run_gbdt_for_h(h, all_member_indices)
            member_points["gbdt_direct"] = gbdt_pt
            member_oos["gbdt_direct"] = gbdt_oos

            # invoice_aware_ml
            if "invoice_aware_ml" in active_members:
                if invoice_ledger is not None:
                    inv_pt, inv_oos = _run_inv_for_h(h, all_member_indices)
                    member_points["invoice_aware_ml"] = inv_pt
                    member_oos["invoice_aware_ml"] = inv_oos
                else:
                    assert open_invoices is not None
                    adj = _live_open_invoice_adjustment(cache, t_idx, h, open_invoices)
                    member_points["invoice_aware_ml"] = quantize_money(float(gbdt_pt) + adj)
                    member_oos["invoice_aware_ml"] = gbdt_oos

            def _weights_at(idx: int) -> dict[str, float]:
                u_start = max(min_fit_idx, idx - h - (MAX_WEIGHT_WINDOW - 1))
                u_end = idx - h + 1
                errs = {
                    m: [abs(float(obs[u + h].closing - member_oos[m][u])) for u in range(u_start, u_end)]
                    for m in active_members
                }
                return _compute_inverse_mae_weights(errs, active_members)

            w_t = _weights_at(t_idx)
            assert ensemble_weights_out is not None
            ensemble_weights_out[h] = w_t
            point = quantize_money(sum(w_t[m] * float(member_points[m]) for m in active_members))

            oos = {}
            for s_idx in res_indices:
                w_s = _weights_at(s_idx)
                oos[s_idx] = quantize_money(
                    sum(w_s[m] * float(member_oos[m][s_idx]) for m in active_members)
                )

        if need_intervals:
            abs_residuals = [abs(obs[s_idx + h].closing - oos[s_idx]) for s_idx in res_indices]
            r80 = conformal_radius(abs_residuals, INTERVAL_LEVEL_80)
            r95 = conformal_radius(abs_residuals, INTERVAL_LEVEL_95)
            forecasts[h] = IntervalForecast(
                horizon_days=h,
                target_date=history.origin + timedelta(days=h),
                predicted=point,
                lower_80=point - r80,
                upper_80=point + r80,
                lower_95=point - r95,
                upper_95=point + r95,
                residual_count=len(abs_residuals),
            )
        else:
            forecasts[h] = IntervalForecast(
                horizon_days=h,
                target_date=history.origin + timedelta(days=h),
                predicted=point,
                lower_80=point,
                upper_80=point,
                lower_95=point,
                upper_95=point,
                residual_count=0,
            )

    if model == "ensemble_ml" and not has_invoices:
        description = (
            f"{MODEL_DESCRIPTIONS['ensemble_ml']} Running without open_invoices snapshot "
            "using active members: drift_calendar, gbdt_direct."
        )
    else:
        description = MODEL_DESCRIPTIONS[model]

    return MLForecastBundle(
        forecasts=forecasts,
        uses_open_invoices=has_invoices and model in ("invoice_aware_ml", "ensemble_ml"),
        open_invoice_count=open_count if model in ("invoice_aware_ml", "ensemble_ml") and has_invoices else None,
        active_members=active_members,
        ensemble_weights=ensemble_weights_out,
        description=description,
    )


def predict_horizons_ml(
    history: History,
    horizons: Sequence[int],
    model: str,
    *,
    invoice_ledger: InvoiceLedger | None = None,
    open_invoices: Sequence[OpenInvoice] | None = None,
) -> dict[int, Decimal]:
    """Predict closing balances strictly from an origin-bounded History prefix (and optional invoice context)."""
    validated_horizons = _validate_model_and_horizons_all(model, horizons)
    normalized_open = _normalize_open_invoices(open_invoices)
    bundle = _forecast_single_series_ml(
        history=history,
        horizons=validated_horizons,
        model=model,
        invoice_ledger=invoice_ledger,
        open_invoices=normalized_open,
        need_intervals=False,
    )
    return {h: bundle.forecasts[h].predicted for h in validated_horizons}


def predict_with_intervals_ml(
    history: History,
    horizons: Sequence[int],
    model: str,
    *,
    invoice_ledger: InvoiceLedger | None = None,
    open_invoices: Sequence[OpenInvoice] | None = None,
) -> MLForecastBundle:
    """Compute point forecasts and split-conformal 80%/95% intervals strictly from origin-bounded inputs."""
    validated_horizons = _validate_model_and_horizons_all(model, horizons)
    normalized_open = _normalize_open_invoices(open_invoices)
    return _forecast_single_series_ml(
        history=history,
        horizons=validated_horizons,
        model=model,
        invoice_ledger=invoice_ledger,
        open_invoices=normalized_open,
        need_intervals=True,
    )


def evaluate_ml(
    series: Sequence[Series],
    ledgers: dict[str, InvoiceLedger],
    train_end: date,
    test_end: date,
) -> MLEvaluation:
    """Rolling-origin 6-model evaluation with strictly prefix-bounded fitting and strided OOS residual cache."""
    ordered = tuple(sorted(series, key=lambda item: item.company_key))
    if not ordered or len({item.company_key for item in ordered}) != len(ordered):
        raise ValueError("At least one uniquely identified SME is required")
    if len({item.currency for item in ordered}) != 1:
        raise ValueError("Pooled monetary metrics cannot silently combine different currencies")
    for item in ordered:
        if item.company_key not in ledgers:
            raise ValueError(f"Missing InvoiceLedger for SME {item.company_key}")
        ledger = ledgers[item.company_key]
        if ledger.currency != item.currency:
            raise ValueError(f"Currency mismatch between Series and InvoiceLedger for {item.company_key}")

    splits = tuple(chronological_split(item, train_end, test_end) for item in ordered)
    max_h = max(HORIZONS)
    last_origin = test_end - timedelta(days=max_h)
    if last_origin < train_end:
        raise ValueError("Test span must contain the complete longest-horizon target")

    required_days = max(min_required_history_days_all(m, max_h) for m in ALL_MODELS)
    candidate_origins = [
        train_end + timedelta(days=offset)
        for offset in range((last_origin - train_end).days + 1)
    ]

    origins: list[date] = []
    excluded: list[ExcludedOrigin] = []
    for origin in candidate_origins:
        missing = []
        for item in ordered:
            obs_count = (origin - item.observations[0].day).days + 1
            if obs_count < required_days:
                missing.append(item.company_key)
        if missing:
            excluded.append(ExcludedOrigin(origin, tuple(missing)))
            continue
        origins.append(origin)

    if not origins:
        raise MissingHistoryError("No common origins satisfy the minimum model + interval history requirement")

    train_scales: dict[str, Decimal] = {}
    with localcontext() as context:
        context.prec = 80
        for item in ordered:
            train_end_idx = (train_end - item.observations[0].day).days
            if train_end_idx < 1:
                raise ValueError(f"SME {item.company_key} needs at least 2 training days to compute MASE scale")
            diffs = [
                abs(item.observations[i].closing - item.observations[i - 1].closing)
                for i in range(1, train_end_idx + 1)
            ]
            train_scales[item.company_key] = sum(diffs, Decimal(0)) / Decimal(len(diffs))

    origin_set = set(origins)
    first_eval_origin = origins[0]
    per_sme_records: dict[tuple[str, date, int, str], ModelPrediction] = {}

    # Track feature importances and ensemble weights for reporting
    gbdt_imp_accum: dict[int, list[np.ndarray]] = {h: [] for h in HORIZONS}
    inv_imp_accum: dict[int, list[np.ndarray]] = {h: [] for h in HORIZONS}
    weight_accum: dict[int, dict[str, list[float]]] = {
        h: {m: [] for m in ("drift_calendar", "gbdt_direct", "invoice_aware_ml")}
        for h in HORIZONS
    }

    for item in ordered:
        obs = item.observations
        start_day = obs[0].day
        first_eval_idx = (first_eval_origin - start_day).days
        last_eval_idx = (origins[-1] - start_day).days
        ledger = ledgers[item.company_key]

        # 1) Exact CM1 models (guarantee bit-for-bit CM1 parity)
        cm1_cached: dict[str, dict[int, dict[int, Decimal]]] = {}
        for model in CM1_MODELS:
            min_fit_idx_cm1 = MIN_FIT_DAYS[model] - 1
            earliest_cm1 = max(
                min_fit_idx_cm1,
                first_eval_idx - max_h - (MAX_RESIDUAL_WINDOW - 1),
            )
            if model == "drift_calendar":
                # drift_calendar is also used by ensemble_ml, so ensure we cover ensemble weight lookback too
                min_fit_idx_ml = min_fit_days_for_horizon("ensemble_ml", max_h) - 1
                earliest_cm1 = min(
                    earliest_cm1,
                    max(min_fit_idx_ml, first_eval_idx - max_h - (MAX_ML_RESIDUAL_WINDOW - 1) - max_h - (MAX_WEIGHT_WINDOW - 1)),
                )
            cached_point: dict[int, dict[int, Decimal]] = {}
            for idx in range(earliest_cm1, last_eval_idx + 1):
                prefix_history = History(obs[idx].day, obs[: idx + 1])
                cached_point[idx] = cash_flow_models.predict_horizons(prefix_history, HORIZONS, model)
            cm1_cached[model] = cached_point

            for t_idx in range(first_eval_idx, last_eval_idx + 1):
                origin = obs[t_idx].day
                if origin not in origin_set:
                    continue
                for horizon in HORIZONS:
                    point = cached_point[t_idx][horizon]
                    start_res_idx = max(min_fit_idx_cm1, t_idx - horizon - (MAX_RESIDUAL_WINDOW - 1))
                    end_res_idx = t_idx - horizon + 1
                    abs_residuals = [
                        abs(obs[s_idx + horizon].closing - cached_point[s_idx][horizon])
                        for s_idx in range(start_res_idx, end_res_idx)
                    ]
                    r80 = conformal_radius(abs_residuals, INTERVAL_LEVEL_80)
                    r95 = conformal_radius(abs_residuals, INTERVAL_LEVEL_95)
                    target_date = origin + timedelta(days=horizon)
                    actual = item.actual(target_date)
                    per_sme_records[(item.company_key, origin, horizon, model)] = ModelPrediction(
                        company_key=item.company_key,
                        currency=item.currency,
                        origin=origin,
                        horizon_days=horizon,
                        target_date=target_date,
                        model=model,
                        predicted=point,
                        actual=actual,
                        lower_80=point - r80,
                        upper_80=point + r80,
                        lower_95=point - r95,
                        upper_95=point + r95,
                        residual_count=len(abs_residuals),
                    )

        # 2) CM2 ML models
        cache = _CausalSeriesCache.from_observations(obs[: last_eval_idx + 1])
        open_by_idx, stats_by_idx = _compute_ledger_timeline(cache.days, ledger)
        min_fit_idx_ml = min_fit_days_for_horizon("gbdt_direct", max_h) - 1
        earliest_ml_oos = max(
            min_fit_idx_ml,
            first_eval_idx - max_h - (MAX_ML_RESIDUAL_WINDOW - 1) - max_h - (MAX_WEIGHT_WINDOW - 1),
        )

        gbdt_fresh: dict[int, dict[int, Decimal]] = {h: {} for h in HORIZONS}
        gbdt_oos: dict[int, dict[int, Decimal]] = {h: {} for h in HORIZONS}
        inv_fresh: dict[int, dict[int, Decimal]] = {h: {} for h in HORIZONS}
        inv_oos: dict[int, dict[int, Decimal]] = {h: {} for h in HORIZONS}

        for h in HORIZONS:
            X_cf, y = _build_cf_matrix_for_horizon(cache, last_eval_idx, h)
            X_full = _build_inv_matrix_for_horizon(cache, X_cf, open_by_idx, stats_by_idx, last_eval_idx, h)

            gbdt_stride_cache: dict[int, _FittedGBDTDirect] = {}
            inv_stride_cache: dict[int, _FittedInvoiceAware] = {}

            for s_idx in range(earliest_ml_oos, last_eval_idx - h + 1):
                f_idx = _strided_fit_index(s_idx, min_fit_idx_ml)
                g_fit = gbdt_stride_cache.get(f_idx)
                if g_fit is None:
                    g_fit = _fit_gbdt_direct(X_cf, y, f_idx, h)
                    gbdt_stride_cache[f_idx] = g_fit
                gbdt_oos[h][s_idx] = quantize_money(cache.closes[s_idx] + g_fit.predict(X_cf[s_idx]))

                i_fit = inv_stride_cache.get(f_idx)
                if i_fit is None:
                    i_fit = _fit_invoice_aware(X_full, y, f_idx, h)
                    inv_stride_cache[f_idx] = i_fit
                inv_oos[h][s_idx] = quantize_money(cache.closes[s_idx] + i_fit.predict(X_full[s_idx]))

            for t_idx in range(first_eval_idx, last_eval_idx + 1):
                if obs[t_idx].day not in origin_set:
                    continue
                g_fresh = _fit_gbdt_direct(X_cf, y, t_idx, h)
                gbdt_fresh[h][t_idx] = quantize_money(cache.closes[t_idx] + g_fresh.predict(X_cf[t_idx]))
                gbdt_imp_accum[h].append(g_fresh.feature_importances)

                i_fresh = _fit_invoice_aware(X_full, y, t_idx, h)
                inv_fresh[h][t_idx] = quantize_money(cache.closes[t_idx] + i_fresh.predict(X_full[t_idx]))
                inv_imp_accum[h].append(i_fresh.feature_importances)

        # Record gbdt_direct and invoice_aware_ml predictions + conformal intervals
        for t_idx in range(first_eval_idx, last_eval_idx + 1):
            origin = obs[t_idx].day
            if origin not in origin_set:
                continue
            for horizon in HORIZONS:
                target_date = origin + timedelta(days=horizon)
                actual = item.actual(target_date)
                res_start = max(min_fit_idx_ml, t_idx - horizon - (MAX_ML_RESIDUAL_WINDOW - 1))
                res_end = t_idx - horizon + 1

                # gbdt_direct
                g_pt = gbdt_fresh[horizon][t_idx]
                g_res = [
                    abs(obs[s_idx + horizon].closing - gbdt_oos[horizon][s_idx])
                    for s_idx in range(res_start, res_end)
                ]
                g_r80 = conformal_radius(g_res, INTERVAL_LEVEL_80)
                g_r95 = conformal_radius(g_res, INTERVAL_LEVEL_95)
                per_sme_records[(item.company_key, origin, horizon, "gbdt_direct")] = ModelPrediction(
                    company_key=item.company_key,
                    currency=item.currency,
                    origin=origin,
                    horizon_days=horizon,
                    target_date=target_date,
                    model="gbdt_direct",
                    predicted=g_pt,
                    actual=actual,
                    lower_80=g_pt - g_r80,
                    upper_80=g_pt + g_r80,
                    lower_95=g_pt - g_r95,
                    upper_95=g_pt + g_r95,
                    residual_count=len(g_res),
                )

                # invoice_aware_ml
                i_pt = inv_fresh[horizon][t_idx]
                i_res = [
                    abs(obs[s_idx + horizon].closing - inv_oos[horizon][s_idx])
                    for s_idx in range(res_start, res_end)
                ]
                i_r80 = conformal_radius(i_res, INTERVAL_LEVEL_80)
                i_r95 = conformal_radius(i_res, INTERVAL_LEVEL_95)
                per_sme_records[(item.company_key, origin, horizon, "invoice_aware_ml")] = ModelPrediction(
                    company_key=item.company_key,
                    currency=item.currency,
                    origin=origin,
                    horizon_days=horizon,
                    target_date=target_date,
                    model="invoice_aware_ml",
                    predicted=i_pt,
                    actual=actual,
                    lower_80=i_pt - i_r80,
                    upper_80=i_pt + i_r80,
                    lower_95=i_pt - i_r95,
                    upper_95=i_pt + i_r95,
                    residual_count=len(i_res),
                )

                # ensemble_ml
                members = ("drift_calendar", "gbdt_direct", "invoice_aware_ml")
                drift_oos = cm1_cached["drift_calendar"]

                def _ens_weights(idx: int) -> dict[str, float]:
                    u_start = max(min_fit_idx_ml, idx - horizon - (MAX_WEIGHT_WINDOW - 1))
                    u_end = idx - horizon + 1
                    errs = {
                        "drift_calendar": [
                            abs(float(obs[u + horizon].closing - drift_oos[u][horizon]))
                            for u in range(u_start, u_end)
                        ],
                        "gbdt_direct": [
                            abs(float(obs[u + horizon].closing - gbdt_oos[horizon][u]))
                            for u in range(u_start, u_end)
                        ],
                        "invoice_aware_ml": [
                            abs(float(obs[u + horizon].closing - inv_oos[horizon][u]))
                            for u in range(u_start, u_end)
                        ],
                    }
                    return _compute_inverse_mae_weights(errs, members)

                w_t = _ens_weights(t_idx)
                for m, w_val in w_t.items():
                    weight_accum[horizon][m].append(w_val)

                drift_pt = drift_oos[t_idx][horizon]
                e_pt = quantize_money(
                    w_t["drift_calendar"] * float(drift_pt)
                    + w_t["gbdt_direct"] * float(g_pt)
                    + w_t["invoice_aware_ml"] * float(i_pt)
                )
                e_res = []
                for s_idx in range(res_start, res_end):
                    w_s = _ens_weights(s_idx)
                    e_oos_s = quantize_money(
                        w_s["drift_calendar"] * float(drift_oos[s_idx][horizon])
                        + w_s["gbdt_direct"] * float(gbdt_oos[horizon][s_idx])
                        + w_s["invoice_aware_ml"] * float(inv_oos[horizon][s_idx])
                    )
                    e_res.append(abs(obs[s_idx + horizon].closing - e_oos_s))

                e_r80 = conformal_radius(e_res, INTERVAL_LEVEL_80)
                e_r95 = conformal_radius(e_res, INTERVAL_LEVEL_95)
                per_sme_records[(item.company_key, origin, horizon, "ensemble_ml")] = ModelPrediction(
                    company_key=item.company_key,
                    currency=item.currency,
                    origin=origin,
                    horizon_days=horizon,
                    target_date=target_date,
                    model="ensemble_ml",
                    predicted=e_pt,
                    actual=actual,
                    lower_80=e_pt - e_r80,
                    upper_80=e_pt + e_r80,
                    lower_95=e_pt - e_r95,
                    upper_95=e_pt + e_r95,
                    residual_count=len(e_res),
                )

    predictions: list[ModelPrediction] = []
    for origin in origins:
        for item in ordered:
            for horizon in HORIZONS:
                for model in ALL_MODELS:
                    predictions.append(per_sme_records[(item.company_key, origin, horizon, model)])

    feature_importances: dict[str, dict[int, dict[str, float]]] = {
        "gbdt_direct": {},
        "invoice_aware_ml": {},
    }
    for h in HORIZONS:
        g_mean = np.mean(gbdt_imp_accum[h], axis=0)
        g_pairs = sorted(
            zip(CF_FEATURE_NAMES, (float(v) for v in g_mean)),
            key=lambda kv: (-kv[1], kv[0]),
        )
        feature_importances["gbdt_direct"][h] = {k: round(v, 6) for k, v in g_pairs[:15]}

        i_mean = np.mean(inv_imp_accum[h], axis=0)
        i_pairs = sorted(
            zip(ALL_INV_FEATURE_NAMES, (float(v) for v in i_mean)),
            key=lambda kv: (-kv[1], kv[0]),
        )
        feature_importances["invoice_aware_ml"][h] = {k: round(v, 6) for k, v in i_pairs[:15]}

    ensemble_weights_summary: dict[int, dict[str, dict[str, float]]] = {}
    for h in HORIZONS:
        ensemble_weights_summary[h] = {}
        for m, vals in weight_accum[h].items():
            ensemble_weights_summary[h][m] = {
                "mean": round(float(np.mean(vals)), 6),
                "min": round(float(np.min(vals)), 6),
                "max": round(float(np.max(vals)), 6),
            }

    return MLEvaluation(
        splits=splits,
        origins=tuple(origins),
        excluded=tuple(excluded),
        predictions=tuple(predictions),
        train_scales=train_scales,
        feature_importances=feature_importances,
        ensemble_weights_summary=ensemble_weights_summary,
    )


def summarize_ml(
    predictions: Sequence[ModelPrediction],
    train_scales: dict[str, Decimal],
) -> list[dict]:
    """Pool and summarize 6-model forecast accuracy and conformal coverage metrics."""
    if not predictions or len({row.currency for row in predictions}) != 1:
        raise ValueError("Summary requires nonempty single-currency predictions")
    keys = tuple(sorted({row.company_key for row in predictions}))
    scores: dict[
        tuple[int, str, str],
        tuple[int, Decimal, Decimal, Decimal, Decimal | None, Decimal, Decimal],
    ] = {}

    with localcontext() as context:
        context.prec = 80
        for horizon in HORIZONS:
            for model in ALL_MODELS:
                for key in (*keys, "ALL"):
                    group = [
                        row
                        for row in predictions
                        if row.horizon_days == horizon
                        and row.model == model
                        and (key == "ALL" or row.company_key == key)
                    ]
                    mae, rmse, me = mae_rmse_me(
                        [row.actual for row in group],
                        [row.predicted for row in group],
                    )
                    n = len(group)
                    n_dec = Decimal(n)
                    group_keys = {row.company_key for row in group}
                    if any(train_scales.get(k, Decimal(0)) <= 0 for k in group_keys):
                        mase = None
                    else:
                        scaled_errors = [
                            abs(row.predicted - row.actual) / train_scales[row.company_key]
                            for row in group
                        ]
                        mase = sum(scaled_errors, Decimal(0)) / n_dec
                    cov80 = Decimal(sum(1 for r in group if r.lower_80 <= r.actual <= r.upper_80)) / n_dec
                    cov95 = Decimal(sum(1 for r in group if r.lower_95 <= r.actual <= r.upper_95)) / n_dec
                    scores[(horizon, model, key)] = (n, mae, rmse, me, mase, cov80, cov95)

        result = []
        for (horizon, model, key), (n, mae, rmse, me, mase, cov80, cov95) in scores.items():
            _, naive_mae, naive_rmse, *_ = scores[(horizon, "naive", key)]
            result.append(
                {
                    "company_key": key,
                    "currency": predictions[0].currency,
                    "horizon_days": horizon,
                    "model": model,
                    "n": n,
                    "mae": metric_text(mae),
                    "rmse": metric_text(rmse),
                    "me": metric_text(me),
                    "mase": metric_text(mase) if mase is not None else None,
                    "mae_delta_vs_naive": metric_text(mae - naive_mae),
                    "rmse_delta_vs_naive": metric_text(rmse - naive_rmse),
                    "mae_improvement_pct": metric_text(100 * (naive_mae - mae) / naive_mae)
                    if naive_mae
                    else None,
                    "rmse_improvement_pct": metric_text(100 * (naive_rmse - rmse) / naive_rmse)
                    if naive_rmse
                    else None,
                    "coverage_80": metric_text(cov80),
                    "coverage_95": metric_text(cov95),
                }
            )
    return result


def _csv(rows: list[dict]) -> str:
    stream = io.StringIO(newline="")
    writer = csv.DictWriter(stream, fieldnames=tuple(rows[0]), lineterminator="\n")
    writer.writeheader()
    writer.writerows(rows)
    return stream.getvalue()


def artifacts_ml(dataset: Dataset, evaluation: MLEvaluation) -> dict[str, str]:
    """Serialize metrics.csv, predictions.csv, and results.json for CM2."""
    metrics = summarize_ml(evaluation.predictions, evaluation.train_scales)
    predictions = [
        {
            "is_synthetic": "true",
            "company_key": row.company_key,
            "currency": row.currency,
            "origin": row.origin.isoformat(),
            "horizon_days": row.horizon_days,
            "target_date": row.target_date.isoformat(),
            "model": row.model,
            "predicted_closing": format(row.predicted, ".2f"),
            "actual_closing": format(row.actual, ".2f"),
            "lower_80": format(row.lower_80, ".2f"),
            "upper_80": format(row.upper_80, ".2f"),
            "lower_95": format(row.lower_95, ".2f"),
            "upper_95": format(row.upper_95, ".2f"),
            "residual_count": row.residual_count,
        }
        for row in evaluation.predictions
    ]
    origin_start, origin_end = evaluation.origins[0], evaluation.origins[-1]
    report = {
        "experiment_version": EXPERIMENT_VERSION,
        "is_synthetic": True,
        "target": "daily_closing_cash_balance",
        "unit": evaluation.predictions[0].currency,
        "horizon_days": list(HORIZONS),
        "models": MODEL_DESCRIPTIONS,
        "model_versions": MODEL_VERSIONS,
        "hyperparameters": {
            "cm1_min_fit_days": MIN_FIT_DAYS,
            "cm1_max_residual_window": MAX_RESIDUAL_WINDOW,
            "min_residual_count": MIN_RESIDUAL_COUNT,
            "ml_min_lag": ML_MIN_LAG,
            "ml_min_train_pairs": ML_MIN_TRAIN_PAIRS,
            "ml_base_min_fit_days": ML_BASE_MIN_FIT_DAYS,
            "max_ml_residual_window": MAX_ML_RESIDUAL_WINDOW,
            "residual_refit_stride": RESIDUAL_REFIT_STRIDE,
            "min_weight_residuals": MIN_WEIGHT_RESIDUALS,
            "max_weight_window": MAX_WEIGHT_WINDOW,
            "random_state": RANDOM_STATE,
            "gbdt_direct": {
                **GBDT_PARAMS,
                "feature_count": len(CF_FEATURE_NAMES),
            },
            "invoice_aware_ml": {
                "ridge_alpha": INVOICE_RIDGE_ALPHA,
                "gbdt_residual": GBDT_PARAMS,
                "feature_count": len(ALL_INV_FEATURE_NAMES),
                "settlement_prior_k": SETTLEMENT_PRIOR_K,
            },
            "ensemble_ml": {
                "members": ["drift_calendar", "gbdt_direct", "invoice_aware_ml"],
                "weighting": "inverse_squared_mae",
                "min_weight_residuals": MIN_WEIGHT_RESIDUALS,
                "max_weight_window": MAX_WEIGHT_WINDOW,
            },
        },
        "protocol": {
            "forecast_origin": "after closing on t; history and invoice ledger contain only dates <= t",
            "rolling_observations": True,
            "interval_calibration": (
                f"split conformal from strictly past rolling out-of-sample residuals "
                f"(s + h <= t, window <= {MAX_ML_RESIDUAL_WINDOW} for ML / {MAX_RESIDUAL_WINDOW} for CM1, "
                f"min {MIN_RESIDUAL_COUNT}; strided refit={RESIDUAL_REFIT_STRIDE}d)"
            ),
            "common_origin_start": origin_start.isoformat(),
            "common_origin_end": origin_end.isoformat(),
            "common_origin_count_per_sme": len(evaluation.origins),
            "candidate_origin_count": len(evaluation.origins) + len(evaluation.excluded),
            "excluded_origins": [
                {**asdict(row), "origin": row.origin.isoformat()} for row in evaluation.excluded
            ],
            "target_date_ranges": {
                str(h): {
                    "start": (origin_start + timedelta(days=h)).isoformat(),
                    "end": (origin_end + timedelta(days=h)).isoformat(),
                }
                for h in HORIZONS
            },
            "metric_aggregation": "pool all absolute/squared/signed errors within each horizon/model; never average SME RMSE",
            "metric_serialization": "6 decimal places, ROUND_HALF_UP; Decimal precision 80 before serialization",
        },
        "feature_importances": {
            model: {str(h): feats for h, feats in by_h.items()}
            for model, by_h in evaluation.feature_importances.items()
        },
        "ensemble_weights_summary": {
            str(h): summary for h, summary in evaluation.ensemble_weights_summary.items()
        },
        "train_in_sample_scales": {
            key: metric_text(value) for key, value in sorted(evaluation.train_scales.items())
        },
        "splits": [
            {
                key: value.isoformat() if isinstance(value, date) else value
                for key, value in asdict(split).items()
            }
            for split in evaluation.splits
        ],
        "input": {
            "generator_version": "FS1",
            "seed": dataset.seed,
            "start": dataset.start.isoformat(),
            "end": dataset.end.isoformat(),
            "manifest_sha256": dataset.manifest_sha256,
            "csv_sha256": dict(dataset.file_hashes),
        },
        "runtime": {
            "python_version": platform.python_version(),
            "third_party_dependencies": {
                "numpy": np.__version__,
                "scikit-learn": sklearn.__version__,
                "scipy": scipy.__version__,
            },
        },
        "source_sha256": {
            name: hashlib.sha256(Path(__file__).with_name(name).read_bytes()).hexdigest()
            for name in ("baseline_data.py", "cash_flow_models.py", "cash_flow_ml.py")
        },
        "prediction_row_count": len(predictions),
        "metrics": metrics,
        "disclaimer": DISCLAIMER_ML,
    }
    return {
        "predictions.csv": _csv(predictions),
        "metrics.csv": _csv(metrics),
        "results.json": json.dumps(report, indent=2, sort_keys=True) + "\n",
    }


def _run_multi_seed_check(
    seed_dirs: Sequence[Path],
    train_end: date,
    test_end: date,
) -> None:
    print("\n=== MULTI-SEED GENERALIZATION CHECK (LOCKED HYPERPARAMETERS) ===")
    for seed_dir in seed_dirs:
        ds = load_dataset(seed_dir)
        ledgers = load_invoice_ledgers(seed_dir)
        ev = evaluate_ml(ds.series, ledgers, train_end, test_end)
        summary = summarize_ml(ev.predictions, ev.train_scales)
        print(f"\nSeed {ds.seed} ({seed_dir}): {len(ev.origins)} origins, {len(ev.excluded)} excluded")
        for row in summary:
            if row["company_key"] == "ALL":
                print(
                    f"  h={row['horizon_days']:>2} {row['model']:<18} "
                    f"MAE={row['mae']} RMSE={row['rmse']} "
                    f"MAE_imp={row['mae_improvement_pct']}% "
                    f"cov80={row['coverage_80']} cov95={row['coverage_95']}"
                )


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-dir", type=Path, default=DEFAULT_DATA_DIR)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--train-end", type=date.fromisoformat, default=DEFAULT_TRAIN_END)
    parser.add_argument("--test-end", type=date.fromisoformat, default=DEFAULT_TEST_END)
    parser.add_argument(
        "--multi-seed-dirs",
        type=Path,
        nargs="*",
        default=(),
        help="Optional additional synthetic dataset directories for locked-hyperparameter generalization check.",
    )
    args = parser.parse_args(argv)
    try:
        dataset = load_dataset(args.data_dir)
        ledgers = load_invoice_ledgers(args.data_dir)
        evaluation = evaluate_ml(dataset.series, ledgers, args.train_end, args.test_end)
        contents = artifacts_ml(dataset, evaluation)
        write_artifacts(contents, args.output_dir, args.data_dir)
    except (ValueError, OSError, KeyError) as error:
        parser.exit(1, f"ML experiment failed: {error}\n")
    print(
        f"SYNTHETIC ONLY: {len(dataset.series)} SMEs; {len(evaluation.origins)} common origins each; "
        f"{len(evaluation.predictions)} prediction rows; {len(evaluation.excluded)} excluded origins."
    )
    print(contents["metrics.csv"], end="")
    if args.multi_seed_dirs:
        try:
            _run_multi_seed_check(args.multi_seed_dirs, args.train_end, args.test_end)
        except (ValueError, OSError, KeyError) as error:
            parser.exit(1, f"Multi-seed check failed: {error}\n")


if __name__ == "__main__":
    main()
