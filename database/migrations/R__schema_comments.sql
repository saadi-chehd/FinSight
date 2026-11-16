-- Repeatable metadata only: reruns when its checksum changes; inserts no data.
COMMENT ON TABLE smes IS 'SME identity with one supported two-decimal reporting currency.';
COMMENT ON TABLE invoices IS 'Receivables (INFLOW) or payables (OUTFLOW); status is recorded, not calculated.';
COMMENT ON TABLE financial_transactions IS 'Actual cash movements; positive amounts with explicit direction. No forecast values.';
COMMENT ON TABLE scenarios IS 'Named assumptions, separate from actual financial records.';
COMMENT ON TABLE scenario_inputs IS 'Typed late-payment or new-contract assumptions; no simulation is performed.';
COMMENT ON COLUMN invoices.amount IS 'Positive face value in invoice currency, NUMERIC(19,2).';
COMMENT ON COLUMN financial_transactions.amount IS 'Positive magnitude; direction determines inflow/outflow. Java rejects fractional minor units.';
COMMENT ON COLUMN scenario_inputs.amount IS 'Proposed new-contract receipt magnitude, not actual cash or a prediction.';
