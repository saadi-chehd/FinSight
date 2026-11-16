from fastapi import FastAPI

app = FastAPI(title="FinSight Forecasting", version="0.0.1")


@app.get("/health")
def health() -> dict[str, str]:
    """Process liveness only; no model or database is loaded."""
    return {"status": "UP", "service": "forecasting"}
