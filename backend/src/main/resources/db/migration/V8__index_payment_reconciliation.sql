CREATE INDEX payment_attempt_reconciliation_idx
    ON payment_attempt (updated_at, booking_id);
