ALTER TABLE booking DROP CONSTRAINT booking_state_valid;
ALTER TABLE booking ADD CONSTRAINT booking_state_valid CHECK (
    state IN ('PAYMENT_PENDING', 'CONFIRMED', 'PAYMENT_FAILED', 'PAYMENT_UNKNOWN',
              'PARTIALLY_REFUNDED', 'REFUNDED')
);

CREATE TABLE refund (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id uuid NOT NULL REFERENCES booking (id) ON DELETE RESTRICT,
    account_id uuid NOT NULL REFERENCES account (id) ON DELETE RESTRICT,
    kind varchar(30) NOT NULL DEFAULT 'CUSTOMER_CANCELLATION',
    state varchar(20) NOT NULL DEFAULT 'REQUESTED',
    refund_key varchar(100) NOT NULL,
    request_fingerprint bytea NOT NULL,
    percentage_bps integer NOT NULL,
    amount_minor bigint NOT NULL,
    service_fee_refund_minor bigint NOT NULL DEFAULT 0,
    currency char(3) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT refund_kind_valid CHECK (kind IN (
        'CUSTOMER_CANCELLATION', 'EVENT_CANCELLATION', 'RESCHEDULE', 'EXCEPTIONAL'
    )),
    CONSTRAINT refund_state_valid CHECK (
        state IN ('REQUESTED', 'SUCCEEDED', 'FAILED', 'UNKNOWN')
    ),
    CONSTRAINT refund_key_unique UNIQUE (account_id, refund_key),
    CONSTRAINT refund_key_nonblank CHECK (length(btrim(refund_key)) BETWEEN 8 AND 100),
    CONSTRAINT refund_fingerprint_sha256 CHECK (octet_length(request_fingerprint) = 32),
    CONSTRAINT refund_percentage_valid CHECK (percentage_bps BETWEEN 0 AND 10000),
    CONSTRAINT refund_money_valid CHECK (
        amount_minor >= 0 AND service_fee_refund_minor >= 0
    ),
    CONSTRAINT refund_currency_valid CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT refund_version_nonnegative CHECK (version >= 0)
);

CREATE INDEX refund_booking_idx ON refund (booking_id, id);

CREATE TABLE refund_item (
    refund_id uuid NOT NULL REFERENCES refund (id) ON DELETE RESTRICT,
    ticket_id uuid NOT NULL REFERENCES ticket (id) ON DELETE RESTRICT,
    charged_refundable_minor bigint NOT NULL,
    service_fee_refund_minor bigint NOT NULL DEFAULT 0,
    refund_amount_minor bigint NOT NULL,
    PRIMARY KEY (refund_id, ticket_id),
    CONSTRAINT refund_item_money_valid CHECK (
        charged_refundable_minor >= 0 AND service_fee_refund_minor >= 0
        AND refund_amount_minor >= 0
        AND refund_amount_minor <= charged_refundable_minor + service_fee_refund_minor
    )
);

CREATE TABLE refund_attempt (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    refund_id uuid NOT NULL REFERENCES refund (id) ON DELETE RESTRICT,
    attempt_number integer NOT NULL,
    state varchar(20) NOT NULL DEFAULT 'INITIATED',
    requested_outcome varchar(20) NOT NULL,
    lookup_outcome varchar(20) NOT NULL,
    observed_outcome varchar(20),
    provider_reference varchar(80) NOT NULL UNIQUE,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT refund_attempt_number_positive CHECK (attempt_number > 0),
    CONSTRAINT refund_attempt_unique UNIQUE (refund_id, attempt_number),
    CONSTRAINT refund_attempt_state_valid CHECK (
        state IN ('INITIATED', 'SUCCEEDED', 'DECLINED', 'UNKNOWN')
    ),
    CONSTRAINT refund_attempt_requested_valid CHECK (
        requested_outcome IN ('SUCCESS', 'DECLINE', 'TIMEOUT', 'UNKNOWN')
    ),
    CONSTRAINT refund_attempt_lookup_valid CHECK (
        lookup_outcome IN ('SUCCESS', 'DECLINE', 'UNKNOWN')
    ),
    CONSTRAINT refund_attempt_observed_valid CHECK (
        observed_outcome IS NULL OR observed_outcome IN ('SUCCESS', 'DECLINE', 'TIMEOUT', 'UNKNOWN')
    )
);
