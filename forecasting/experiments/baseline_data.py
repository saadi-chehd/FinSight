"""Read the inspected FS1 export without changing data or connecting to PostgreSQL."""

from bisect import bisect_right
import csv
from dataclasses import dataclass
from datetime import date, timedelta
from decimal import Decimal, InvalidOperation, localcontext
import hashlib
import json
from pathlib import Path


CENT = Decimal("0.01")
MAX_MONEY = Decimal("99999999999999999.99")
CSV_FILES = frozenset({
    "smes.csv", "opening_balances.csv", "invoices.csv",
    "transactions.csv", "daily_cash_flow.csv",
})
DAILY_COLUMNS = (
    "is_synthetic", "company_key", "currency", "date", "opening_balance",
    "inflow", "outflow", "net_movement", "closing_balance",
)


class MissingHistoryError(ValueError):
    """A required observed calendar day is unavailable; never impute it."""


class FutureDataError(ValueError):
    """A predictor attempted to consume an observation after its origin."""


def money(text: str | Decimal) -> Decimal:
    """Accept finite exact minor units within the existing NUMERIC(19,2) range."""
    try:
        with localcontext() as context:
            context.prec = 80
            value = Decimal(text)
            if not value.is_finite() or abs(value) > MAX_MONEY:
                raise ValueError("Money must be finite and fit NUMERIC(19,2)")
            normalized = value.quantize(CENT)
            if value != normalized:
                raise ValueError("Money must use whole minor units")
            return normalized
    except (InvalidOperation, TypeError) as error:
        raise ValueError("Invalid monetary value") from error


@dataclass(frozen=True)
class Observation:
    day: date
    closing: Decimal

    def __post_init__(self):
        if not isinstance(self.day, date) or not isinstance(self.closing, Decimal):
            raise ValueError("Observations require a calendar date and Decimal closing cash")
        object.__setattr__(self, "closing", money(self.closing))


def _chronological(observations: tuple[Observation, ...]) -> None:
    if not observations:
        raise MissingHistoryError("At least one observation is required")
    for previous, current in zip(observations, observations[1:]):
        if current.day - previous.day != timedelta(days=1):
            raise ValueError("Daily observations must be ordered, unique, and consecutive; no filling")


@dataclass(frozen=True)
class History:
    """The complete input boundary for predictors: one SME's observed prefix only."""
    origin: date
    observations: tuple[Observation, ...]

    def __post_init__(self):
        object.__setattr__(self, "observations", tuple(self.observations))
        _chronological(self.observations)
        if self.observations[-1].day > self.origin:
            raise FutureDataError("History cannot contain observations after the origin")
        if self.observations[-1].day != self.origin:
            raise MissingHistoryError("The origin's closing observation is required")

    def value(self, day: date) -> Decimal:
        if day > self.origin:
            raise FutureDataError("Future actuals are unavailable to predictors")
        index = (day - self.observations[0].day).days
        if index < 0 or index >= len(self.observations):
            raise MissingHistoryError(f"No observed closing balance for {day}")
        return self.observations[index].closing


@dataclass(frozen=True)
class Series:
    company_key: str
    currency: str
    observations: tuple[Observation, ...]

    def __post_init__(self):
        object.__setattr__(self, "observations", tuple(self.observations))
        if not self.company_key or not self.currency:
            raise ValueError("SME identity and currency are required")
        _chronological(self.observations)

    def history(self, origin: date) -> History:
        days = tuple(observation.day for observation in self.observations)
        end = bisect_right(days, origin)
        return History(origin, self.observations[:end])

    def actual(self, target: date) -> Decimal:
        index = (target - self.observations[0].day).days
        if index < 0 or index >= len(self.observations):
            raise ValueError("Target observation is outside the series")
        return self.observations[index].closing


@dataclass(frozen=True)
class Dataset:
    series: tuple[Series, ...]
    seed: int
    start: date
    end: date
    manifest_sha256: str
    file_hashes: tuple[tuple[str, str], ...]


def _rows(path: Path, columns: tuple[str, ...]) -> list[dict[str, str]]:
    with path.open(encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        if tuple(reader.fieldnames or ()) != columns:
            raise ValueError(f"{path.name}: columns differ from the inspected FS1 contract")
        rows = list(reader)
    if any(None in row or any(value is None for value in row.values()) for row in rows):
        raise ValueError(f"{path.name}: malformed CSV row")
    if any(row["is_synthetic"] != "true" for row in rows):
        raise ValueError(f"{path.name}: all records must be explicitly synthetic")
    return rows


def load_dataset(directory: Path) -> Dataset:
    manifest_bytes = (directory / "manifest.json").read_bytes()
    manifest = json.loads(manifest_bytes)
    if (manifest.get("is_synthetic") is not True or manifest.get("generator_version") != "FS1"
            or manifest.get("amount_scale") != 2 or manifest.get("currency") != "USD"):
        raise ValueError("This experiment requires the inspected synthetic FS1 USD/scale-2 export")
    hashes = manifest.get("sha256", {})
    if set(hashes) != CSV_FILES:
        raise ValueError("Manifest must identify exactly the five canonical FS1 CSV files")
    for name, expected in sorted(hashes.items()):
        if hashlib.sha256((directory / name).read_bytes()).hexdigest() != expected:
            raise ValueError(f"Input checksum mismatch: {name}")

    companies = _rows(directory / "smes.csv", ("is_synthetic", "company_key", "name", "currency"))
    currencies = {row["company_key"]: row["currency"] for row in companies}
    if len(currencies) != len(companies) or len(companies) != manifest["sme_count"]:
        raise ValueError("SME identities/count differ from the manifest")
    if any(not row["name"].startswith("SYNTHETIC ") or row["currency"] != "USD" for row in companies):
        raise ValueError("Fictional labels and one USD currency are required for pooled metrics")

    openings = _rows(directory / "opening_balances.csv", (
        "is_synthetic", "company_key", "opening_date", "currency", "opening_balance",
    ))
    opening_by_key = {row["company_key"]: row for row in openings}
    if (set(opening_by_key) != set(currencies) or len(openings) != len(currencies)
            or len(openings) != manifest["opening_balance_count"]):
        raise ValueError("Exactly one dated opening input per SME is required")
    start, end = date.fromisoformat(manifest["start_date"]), date.fromisoformat(manifest["end_date"])
    if end < start:
        raise ValueError("Invalid manifest date range")
    previous = {}
    observations: dict[str, list[Observation]] = {key: [] for key in currencies}
    for key, row in opening_by_key.items():
        if row["currency"] != currencies[key] or date.fromisoformat(row["opening_date"]) != start:
            raise ValueError("Dated opening input does not match the series")
        previous[key] = money(row["opening_balance"])

    rows = _rows(directory / "daily_cash_flow.csv", DAILY_COLUMNS)
    if len(rows) != manifest["daily_row_count"]:
        raise ValueError("Daily row count differs from manifest")
    for row in rows:
        key = row["company_key"]
        if key not in observations or row["currency"] != currencies[key]:
            raise ValueError("Daily row has unknown SME or mismatched currency")
        day = date.fromisoformat(row["date"])
        expected_day = start + timedelta(days=len(observations[key]))
        if day != expected_day or day > end:
            raise ValueError("Daily dates must be ordered and complete; no sorting/filling")
        opening, inflow, outflow, net, closing = (
            money(row[column]) for column in (
                "opening_balance", "inflow", "outflow", "net_movement", "closing_balance",
            )
        )
        if inflow < 0 or outflow < 0:
            raise ValueError("Inflow/outflow are nonnegative magnitudes")
        if opening != previous[key] or net != inflow - outflow or closing != opening + net:
            raise ValueError("Daily row violates the existing financial-engine roll-forward")
        observations[key].append(Observation(day, closing))
        previous[key] = closing
    expected_count = (end - start).days + 1
    if any(len(values) != expected_count for values in observations.values()):
        raise ValueError("Each SME must have the complete manifest date range")
    return Dataset(
        tuple(Series(key, currencies[key], tuple(observations[key])) for key in sorted(observations)),
        int(manifest["seed"]), start, end, hashlib.sha256(manifest_bytes).hexdigest(),
        tuple(sorted(hashes.items())),
    )
