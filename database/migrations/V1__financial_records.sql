-- Currency scope deliberately limited to supported two-decimal currencies.
CREATE TABLE smes (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(200) NOT NULL CHECK (btrim(name) <> ''),
    currency VARCHAR(3) NOT NULL CHECK (currency IN ('USD', 'EUR', 'GBP', 'TRY')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (id, currency)
);

CREATE TABLE invoices (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sme_id BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    reference VARCHAR(100) NOT NULL CHECK (btrim(reference) <> ''),
    direction VARCHAR(7) NOT NULL CHECK (direction IN ('INFLOW', 'OUTFLOW')),
    amount NUMERIC(19,2) NOT NULL CHECK (amount > 0 AND amount <> 'NaN'::numeric),
    issue_date DATE NOT NULL CHECK (isfinite(issue_date)),
    due_date DATE NOT NULL CHECK (isfinite(due_date) AND due_date >= issue_date),
    payment_status VARCHAR(20) NOT NULL DEFAULT 'UNPAID'
        CHECK (payment_status IN ('UNPAID', 'PARTIALLY_PAID', 'PAID', 'CANCELLED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (sme_id, currency) REFERENCES smes (id, currency),
    UNIQUE (sme_id, reference),
    UNIQUE (id, sme_id, currency),
    UNIQUE (id, sme_id, currency, direction)
);
CREATE INDEX invoices_sme_status_due_idx ON invoices (sme_id, payment_status, due_date);

CREATE TABLE financial_transactions (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sme_id BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    invoice_id BIGINT,
    booked_date DATE NOT NULL CHECK (isfinite(booked_date)),
    direction VARCHAR(7) NOT NULL CHECK (direction IN ('INFLOW', 'OUTFLOW')),
    transaction_type VARCHAR(30) NOT NULL CHECK (transaction_type IN (
        'CUSTOMER_RECEIPT', 'SUPPLIER_PAYMENT', 'PAYROLL', 'TAX',
        'FINANCING_RECEIPT', 'FINANCING_REPAYMENT', 'OTHER')),
    amount NUMERIC(19,2) NOT NULL CHECK (amount > 0 AND amount <> 'NaN'::numeric),
    description VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (sme_id, currency) REFERENCES smes (id, currency),
    FOREIGN KEY (invoice_id, sme_id, currency, direction)
        REFERENCES invoices (id, sme_id, currency, direction),
    CHECK (
        transaction_type = 'OTHER'
        OR (direction = 'INFLOW' AND transaction_type IN ('CUSTOMER_RECEIPT', 'FINANCING_RECEIPT'))
        OR (direction = 'OUTFLOW' AND transaction_type IN ('SUPPLIER_PAYMENT', 'PAYROLL', 'TAX', 'FINANCING_REPAYMENT'))
    )
);
CREATE INDEX transactions_sme_date_idx ON financial_transactions (sme_id, booked_date);
CREATE INDEX transactions_invoice_idx ON financial_transactions (invoice_id) WHERE invoice_id IS NOT NULL;
