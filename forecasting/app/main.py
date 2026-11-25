from datetime import date, timedelta
from decimal import Decimal
import re
from typing import Literal

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field, field_validator, model_validator

from forecasting.experiments.baseline_data import History, MissingHistoryError, Observation, money
from forecasting.experiments.cash_flow_ml import (
    CM1_MODELS,
    DISCLAIMER_ML,
    MAX_ML_RESIDUAL_WINDOW,
    MODEL_VERSIONS,
    OpenInvoice,
    min_required_history_days_all,
    predict_with_intervals_ml,
)
from forecasting.experiments.cash_flow_models import (
    DISCLAIMER,
    MAX_RESIDUAL_WINDOW,
)


CURRENCY_RE = re.compile(r"^[A-Z]{3}$")

app = FastAPI(title="FinSight Forecasting", version="0.1.0")


def _reject_numeric_date(value: object) -> object:
    if isinstance(value, (bool, int, float)):
        raise ValueError("date must be an ISO calendar date string (YYYY-MM-DD)")
    return value


def _parse_money_input(value: object, field_name: str) -> Decimal:
    if isinstance(value, bool) or value is None:
        raise ValueError(f"{field_name} must be a 2-decimal monetary amount")
    if isinstance(value, (int, float)):
        value = str(value)
    elif not isinstance(value, (str, Decimal)):
        raise ValueError(f"{field_name} must be a string, Decimal, or number")
    return money(value)


class HistoryEntry(BaseModel):
    date: date
    closing_balance: str | Decimal | int | float

    @field_validator("date", mode="before")
    @classmethod
    def validate_date(cls, value: object) -> object:
        return _reject_numeric_date(value)

    @field_validator("closing_balance", mode="before")
    @classmethod
    def validate_closing_balance(cls, value: object) -> Decimal:
        return _parse_money_input(value, "closing_balance")


class OpenInvoiceEntry(BaseModel):
    direction: Literal["INFLOW", "OUTFLOW"]
    due_date: date
    outstanding: str | Decimal | int | float

    @field_validator("due_date", mode="before")
    @classmethod
    def validate_due_date(cls, value: object) -> object:
        return _reject_numeric_date(value)

    @field_validator("outstanding", mode="before")
    @classmethod
    def validate_outstanding(cls, value: object) -> Decimal:
        amount = _parse_money_input(value, "outstanding")
        if amount <= Decimal("0.00"):
            raise ValueError("outstanding must be strictly greater than 0.00")
        return amount


class ForecastRequest(BaseModel):
    currency: str
    history: list[HistoryEntry]
    horizons: list[int] = Field(default_factory=lambda: [7, 30])
    model: Literal[
        "drift_calendar",
        "ridge_lag_calendar",
        "naive",
        "gbdt_direct",
        "invoice_aware_ml",
        "ensemble_ml",
    ] = "drift_calendar"
    open_invoices: list[OpenInvoiceEntry] | None = None

    @field_validator("currency")
    @classmethod
    def validate_currency(cls, value: str) -> str:
        normalized = value.strip().upper()
        if not CURRENCY_RE.fullmatch(normalized):
            raise ValueError("currency must be a 3-letter ISO code such as USD")
        return normalized

    @field_validator("horizons", mode="before")
    @classmethod
    def validate_horizons(cls, value: object) -> list[int]:
        if not isinstance(value, list) or not value:
            raise ValueError("horizons must be a nonempty list of integer horizons")
        seen: set[int] = set()
        for item in value:
            if not isinstance(item, int) or isinstance(item, bool) or not (1 <= item <= 365):
                raise ValueError("Each horizon must be an integer between 1 and 365")
            if item in seen:
                raise ValueError(f"Duplicate horizon {item} is not allowed")
            seen.add(item)
        return value

    @model_validator(mode="after")
    def validate_history_chronology(self) -> "ForecastRequest":
        if not self.history:
            raise ValueError("history must contain at least one daily observation")
        for prev, curr in zip(self.history, self.history[1:]):
            if curr.date == prev.date:
                raise ValueError(f"Duplicate calendar date {curr.date.isoformat()} in history")
            if curr.date < prev.date:
                raise ValueError(
                    f"History dates must be strictly ascending; {curr.date.isoformat()} follows {prev.date.isoformat()}"
                )
            if curr.date - prev.date != timedelta(days=1):
                raise ValueError(
                    f"Missing calendar day(s) between {prev.date.isoformat()} and {curr.date.isoformat()}; no filling"
                )
        return self


class IntervalBoundsResponse(BaseModel):
    lower: str
    upper: str


class HorizonForecastResponse(BaseModel):
    horizon_days: int
    target_date: date
    predicted_closing_balance: str
    interval_80: IntervalBoundsResponse
    interval_95: IntervalBoundsResponse
    residual_count: int


class ModelMetadataResponse(BaseModel):
    name: str
    version: str
    description: str
    interval_method: str
    min_history_days: int
    residual_window_max: int
    uses_open_invoices: bool = False
    open_invoice_count: int | None = None
    active_members: list[str] | None = None
    ensemble_weights: dict[str, dict[str, float]] | None = None


class ForecastResponse(BaseModel):
    currency: str
    origin_date: date
    history_start_date: date
    history_days: int
    model: ModelMetadataResponse
    forecasts: list[HorizonForecastResponse]
    disclaimer: str


@app.get("/health")
def health() -> dict[str, str]:
    """Process liveness only; no model or database is loaded."""
    return {"status": "UP", "service": "forecasting"}


@app.post("/forecast", response_model=ForecastResponse)
def forecast(request: ForecastRequest) -> ForecastResponse:
    """Produce conditional closing-cash point forecasts and empirical 80%/95% prediction intervals."""
    if request.model == "invoice_aware_ml" and request.open_invoices is None:
        raise HTTPException(
            status_code=422,
            detail="Model 'invoice_aware_ml' requires 'open_invoices' (pass [] if none are open as of origin)",
        )

    max_horizon = max(request.horizons)
    required_days = min_required_history_days_all(request.model, max_horizon)
    if len(request.history) < required_days:
        raise HTTPException(
            status_code=422,
            detail=(
                f"Insufficient history for model '{request.model}' at max horizon {max_horizon}: "
                f"requires at least {required_days} consecutive daily observations, received {len(request.history)}"
            ),
        )

    observations = tuple(
        Observation(entry.date, entry.closing_balance)
        for entry in request.history
    )
    history = History(observations[-1].day, observations)
    open_invoices = (
        tuple(
            OpenInvoice(
                direction=entry.direction,
                due_date=entry.due_date,
                outstanding=entry.outstanding,
            )
            for entry in request.open_invoices
        )
        if request.open_invoices is not None
        else None
    )

    try:
        bundle = predict_with_intervals_ml(
            history,
            request.horizons,
            request.model,
            open_invoices=open_invoices,
        )
    except (MissingHistoryError, ValueError) as error:
        raise HTTPException(status_code=422, detail=str(error)) from error

    predictions = bundle.forecasts
    forecasts = [
        HorizonForecastResponse(
            horizon_days=h,
            target_date=predictions[h].target_date,
            predicted_closing_balance=format(predictions[h].predicted, ".2f"),
            interval_80=IntervalBoundsResponse(
                lower=format(predictions[h].lower_80, ".2f"),
                upper=format(predictions[h].upper_80, ".2f"),
            ),
            interval_95=IntervalBoundsResponse(
                lower=format(predictions[h].lower_95, ".2f"),
                upper=format(predictions[h].upper_95, ".2f"),
            ),
            residual_count=predictions[h].residual_count,
        )
        for h in request.horizons
    ]

    is_cm1 = request.model in CM1_MODELS
    ensemble_weights = (
        {str(horizon): dict(weights) for horizon, weights in bundle.ensemble_weights.items()}
        if bundle.ensemble_weights is not None
        else None
    )

    return ForecastResponse(
        currency=request.currency,
        origin_date=history.origin,
        history_start_date=observations[0].day,
        history_days=len(observations),
        model=ModelMetadataResponse(
            name=request.model,
            version=MODEL_VERSIONS[request.model],
            description=bundle.description,
            interval_method="split_conformal_past_rolling_residuals",
            min_history_days=required_days,
            residual_window_max=MAX_RESIDUAL_WINDOW if is_cm1 else MAX_ML_RESIDUAL_WINDOW,
            uses_open_invoices=bundle.uses_open_invoices,
            open_invoice_count=bundle.open_invoice_count,
            active_members=list(bundle.active_members) if bundle.active_members is not None else None,
            ensemble_weights=ensemble_weights,
        ),
        forecasts=forecasts,
        disclaimer=DISCLAIMER if is_cm1 else DISCLAIMER_ML,
    )
