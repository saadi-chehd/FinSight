CREATE TABLE scenarios (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sme_id BIGINT NOT NULL REFERENCES smes (id),
    name VARCHAR(200) NOT NULL CHECK (btrim(name) <> ''),
    kind VARCHAR(20) NOT NULL CHECK (kind IN ('BASELINE', 'LATE_PAYMENT', 'NEW_CONTRACT')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (id, sme_id, kind)
);
CREATE INDEX scenarios_sme_idx ON scenarios (sme_id);

CREATE TABLE scenario_inputs (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    scenario_id BIGINT NOT NULL,
    sme_id BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    kind VARCHAR(20) NOT NULL CHECK (kind IN ('LATE_PAYMENT', 'NEW_CONTRACT')),
    invoice_id BIGINT,
    delay_days INTEGER,
    amount NUMERIC(19,2),
    expected_date DATE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (scenario_id, sme_id, kind) REFERENCES scenarios (id, sme_id, kind),
    FOREIGN KEY (sme_id, currency) REFERENCES smes (id, currency),
    FOREIGN KEY (invoice_id, sme_id, currency) REFERENCES invoices (id, sme_id, currency),
    CHECK (
        (kind = 'LATE_PAYMENT' AND invoice_id IS NOT NULL
            AND delay_days IS NOT NULL AND delay_days BETWEEN 1 AND 3650
            AND amount IS NULL AND expected_date IS NULL)
        OR (kind = 'NEW_CONTRACT' AND invoice_id IS NULL AND delay_days IS NULL
            AND amount IS NOT NULL AND amount > 0 AND amount <> 'NaN'::numeric
            AND expected_date IS NOT NULL AND isfinite(expected_date))
    )
);
CREATE INDEX scenario_inputs_scenario_idx ON scenario_inputs (scenario_id);
CREATE INDEX scenario_inputs_sme_idx ON scenario_inputs (sme_id);
CREATE INDEX scenario_inputs_invoice_idx ON scenario_inputs (invoice_id) WHERE invoice_id IS NOT NULL;
