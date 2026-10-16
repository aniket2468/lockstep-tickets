ALTER TABLE event
    ADD COLUMN early_bird_ends_at timestamptz,
    ADD COLUMN early_bird_discount_bps integer NOT NULL DEFAULT 0,
    ADD COLUMN group_size_threshold integer,
    ADD COLUMN group_discount_bps integer NOT NULL DEFAULT 0,
    ADD CONSTRAINT event_early_discount_valid CHECK (
        early_bird_discount_bps BETWEEN 0 AND 10000
    ),
    ADD CONSTRAINT event_group_threshold_valid CHECK (
        group_size_threshold IS NULL OR group_size_threshold BETWEEN 2 AND 6
    ),
    ADD CONSTRAINT event_group_discount_valid CHECK (
        group_discount_bps BETWEEN 0 AND 10000
    );

CREATE TABLE promo_code (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id uuid REFERENCES event (id) ON DELETE RESTRICT,
    code varchar(40) NOT NULL,
    discount_type varchar(20) NOT NULL,
    discount_value bigint NOT NULL,
    valid_from timestamptz NOT NULL,
    valid_until timestamptz NOT NULL,
    global_limit integer,
    per_customer_limit integer,
    redeemed_count integer NOT NULL DEFAULT 0,
    exclusive boolean NOT NULL DEFAULT false,
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT promo_code_normalized CHECK (code = upper(btrim(code))),
    CONSTRAINT promo_code_nonblank CHECK (length(btrim(code)) BETWEEN 1 AND 40),
    CONSTRAINT promo_discount_type_valid CHECK (discount_type IN ('PERCENT', 'FIXED')),
    CONSTRAINT promo_discount_value_valid CHECK (
        discount_value > 0
        AND (discount_type <> 'PERCENT' OR discount_value <= 10000)
    ),
    CONSTRAINT promo_validity_order CHECK (valid_until > valid_from),
    CONSTRAINT promo_global_limit_valid CHECK (global_limit IS NULL OR global_limit > 0),
    CONSTRAINT promo_customer_limit_valid CHECK (
        per_customer_limit IS NULL OR per_customer_limit > 0
    ),
    CONSTRAINT promo_redeemed_count_valid CHECK (
        redeemed_count >= 0 AND (global_limit IS NULL OR redeemed_count <= global_limit)
    ),
    CONSTRAINT promo_version_nonnegative CHECK (version >= 0),
    CONSTRAINT promo_code_unique UNIQUE (code)
);

ALTER TABLE seat_hold
    DROP CONSTRAINT seat_hold_state_valid,
    DROP CONSTRAINT seat_hold_state_end_consistent,
    ADD COLUMN promo_id uuid REFERENCES promo_code (id) ON DELETE RESTRICT,
    ADD COLUMN currency char(3),
    ADD COLUMN subtotal_minor bigint,
    ADD COLUMN discount_minor bigint,
    ADD COLUMN service_fee_minor bigint,
    ADD COLUMN total_minor bigint,
    ADD CONSTRAINT seat_hold_state_valid CHECK (
        state IN ('ACTIVE', 'PAYMENT_PENDING', 'CONSUMED', 'EXPIRED', 'RELEASED')
    ),
    ADD CONSTRAINT seat_hold_state_end_consistent CHECK (
        (state IN ('ACTIVE', 'PAYMENT_PENDING') AND ended_at IS NULL)
        OR (state NOT IN ('ACTIVE', 'PAYMENT_PENDING') AND ended_at IS NOT NULL)
    ),
    ADD CONSTRAINT seat_hold_quote_all_or_none CHECK (
        (currency IS NULL AND subtotal_minor IS NULL AND discount_minor IS NULL
            AND service_fee_minor IS NULL AND total_minor IS NULL)
        OR
        (currency ~ '^[A-Z]{3}$' AND subtotal_minor >= 0 AND discount_minor >= 0
            AND service_fee_minor >= 0 AND total_minor >= 0
            AND total_minor = subtotal_minor - discount_minor + service_fee_minor)
    );

DROP INDEX seat_hold_one_active_per_customer_event;
CREATE UNIQUE INDEX seat_hold_one_active_per_customer_event
    ON seat_hold (account_id, event_id)
    WHERE state IN ('ACTIVE', 'PAYMENT_PENDING');

ALTER TABLE seat_hold_item
    DROP CONSTRAINT seat_hold_item_claim_order,
    ADD COLUMN base_price_minor bigint,
    ADD COLUMN early_discount_minor bigint,
    ADD COLUMN group_discount_minor bigint,
    ADD COLUMN promo_discount_minor bigint,
    ADD COLUMN service_fee_minor bigint,
    ADD COLUMN final_total_minor bigint,
    ADD COLUMN currency char(3),
    ADD CONSTRAINT seat_hold_item_claim_order CHECK (claim_ends_at >= claim_started_at),
    ADD CONSTRAINT seat_hold_item_quote_all_or_none CHECK (
        (currency IS NULL AND base_price_minor IS NULL AND early_discount_minor IS NULL
            AND group_discount_minor IS NULL AND promo_discount_minor IS NULL
            AND service_fee_minor IS NULL AND final_total_minor IS NULL)
        OR
        (currency ~ '^[A-Z]{3}$' AND base_price_minor >= 0
            AND early_discount_minor >= 0 AND group_discount_minor >= 0
            AND promo_discount_minor >= 0 AND service_fee_minor >= 0
            AND final_total_minor = base_price_minor - early_discount_minor
                - group_discount_minor - promo_discount_minor + service_fee_minor)
    );

CREATE TABLE booking (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    reference varchar(24) NOT NULL,
    account_id uuid NOT NULL REFERENCES account (id) ON DELETE RESTRICT,
    event_id uuid NOT NULL REFERENCES event (id) ON DELETE RESTRICT,
    hold_id uuid NOT NULL UNIQUE REFERENCES seat_hold (id) ON DELETE RESTRICT,
    state varchar(30) NOT NULL,
    currency char(3) NOT NULL,
    subtotal_minor bigint NOT NULL,
    discount_minor bigint NOT NULL,
    service_fee_minor bigint NOT NULL,
    total_minor bigint NOT NULL,
    checkout_key varchar(100) NOT NULL,
    request_fingerprint bytea NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT booking_reference_unique UNIQUE (reference),
    CONSTRAINT booking_checkout_key_unique UNIQUE (account_id, checkout_key),
    CONSTRAINT booking_state_valid CHECK (
        state IN ('PAYMENT_PENDING', 'CONFIRMED', 'PAYMENT_FAILED', 'PAYMENT_UNKNOWN')
    ),
    CONSTRAINT booking_currency_valid CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT booking_money_valid CHECK (
        subtotal_minor >= 0 AND discount_minor >= 0 AND service_fee_minor >= 0
        AND total_minor = subtotal_minor - discount_minor + service_fee_minor
    ),
    CONSTRAINT booking_fingerprint_sha256 CHECK (octet_length(request_fingerprint) = 32),
    CONSTRAINT booking_key_nonblank CHECK (length(btrim(checkout_key)) BETWEEN 8 AND 100),
    CONSTRAINT booking_version_nonnegative CHECK (version >= 0)
);

CREATE TABLE ticket (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id uuid NOT NULL REFERENCES booking (id) ON DELETE RESTRICT,
    event_id uuid NOT NULL,
    event_seat_id uuid NOT NULL,
    state varchar(30) NOT NULL DEFAULT 'CONFIRMED',
    base_price_minor bigint NOT NULL,
    early_discount_minor bigint NOT NULL,
    group_discount_minor bigint NOT NULL,
    promo_discount_minor bigint NOT NULL,
    service_fee_minor bigint NOT NULL,
    charged_total_minor bigint NOT NULL,
    currency char(3) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT ticket_seat_same_event FOREIGN KEY (event_id, event_seat_id)
        REFERENCES event_seat (event_id, id) ON DELETE RESTRICT,
    CONSTRAINT ticket_state_valid CHECK (
        state IN ('CONFIRMED', 'CANCELLATION_PENDING', 'REFUNDED')
    ),
    CONSTRAINT ticket_money_valid CHECK (
        base_price_minor >= 0 AND early_discount_minor >= 0
        AND group_discount_minor >= 0 AND promo_discount_minor >= 0
        AND service_fee_minor >= 0
        AND charged_total_minor = base_price_minor - early_discount_minor
            - group_discount_minor - promo_discount_minor + service_fee_minor
    ),
    CONSTRAINT ticket_currency_valid CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE UNIQUE INDEX ticket_active_seat_unique ON ticket (event_id, event_seat_id)
    WHERE state IN ('CONFIRMED', 'CANCELLATION_PENDING');

CREATE TABLE payment_attempt (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id uuid NOT NULL UNIQUE REFERENCES booking (id) ON DELETE RESTRICT,
    state varchar(20) NOT NULL,
    requested_outcome varchar(20) NOT NULL,
    lookup_outcome varchar(20),
    observed_outcome varchar(20),
    amount_minor bigint NOT NULL,
    currency char(3) NOT NULL,
    provider_reference varchar(80) NOT NULL UNIQUE,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT payment_state_valid CHECK (state IN ('INITIATED', 'SUCCEEDED', 'DECLINED', 'UNKNOWN')),
    CONSTRAINT payment_requested_outcome_valid CHECK (
        requested_outcome IN ('SUCCESS', 'DECLINE', 'TIMEOUT', 'UNKNOWN')
    ),
    CONSTRAINT payment_lookup_outcome_valid CHECK (
        lookup_outcome IS NULL OR lookup_outcome IN ('SUCCESS', 'DECLINE', 'UNKNOWN')
    ),
    CONSTRAINT payment_observed_outcome_valid CHECK (
        observed_outcome IS NULL OR observed_outcome IN ('SUCCESS', 'DECLINE', 'TIMEOUT', 'UNKNOWN')
    ),
    CONSTRAINT payment_amount_nonnegative CHECK (amount_minor >= 0),
    CONSTRAINT payment_currency_valid CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE TABLE promo_redemption (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    promo_id uuid NOT NULL REFERENCES promo_code (id) ON DELETE RESTRICT,
    account_id uuid NOT NULL REFERENCES account (id) ON DELETE RESTRICT,
    booking_id uuid NOT NULL UNIQUE REFERENCES booking (id) ON DELETE RESTRICT,
    state varchar(20) NOT NULL DEFAULT 'RESERVED',
    created_at timestamptz NOT NULL,
    CONSTRAINT promo_redemption_state_valid CHECK (
        state IN ('RESERVED', 'CONSUMED', 'RESTORED')
    )
);

CREATE INDEX promo_redemption_customer_idx
    ON promo_redemption (promo_id, account_id, state);

CREATE TABLE outbox_event (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type varchar(40) NOT NULL,
    aggregate_id uuid NOT NULL,
    event_type varchar(80) NOT NULL,
    payload text NOT NULL,
    created_at timestamptz NOT NULL,
    published_at timestamptz,
    CONSTRAINT outbox_type_nonblank CHECK (
        length(btrim(aggregate_type)) > 0 AND length(btrim(event_type)) > 0
    ),
    CONSTRAINT outbox_payload_nonblank CHECK (length(btrim(payload)) > 0)
);
