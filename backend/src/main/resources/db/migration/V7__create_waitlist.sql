ALTER TABLE seat_hold
    DROP CONSTRAINT seat_hold_state_valid,
    DROP CONSTRAINT seat_hold_state_end_consistent,
    ADD CONSTRAINT seat_hold_state_valid CHECK (
        state IN ('ACTIVE', 'WAITLIST_OFFER', 'PAYMENT_PENDING',
                  'CONSUMED', 'EXPIRED', 'RELEASED')
    ),
    ADD CONSTRAINT seat_hold_state_end_consistent CHECK (
        (state IN ('ACTIVE', 'WAITLIST_OFFER', 'PAYMENT_PENDING') AND ended_at IS NULL)
        OR (state NOT IN ('ACTIVE', 'WAITLIST_OFFER', 'PAYMENT_PENDING') AND ended_at IS NOT NULL)
    );

DROP INDEX seat_hold_one_active_per_customer_event;
CREATE UNIQUE INDEX seat_hold_one_active_per_customer_event
    ON seat_hold (account_id, event_id)
    WHERE state IN ('ACTIVE', 'WAITLIST_OFFER', 'PAYMENT_PENDING');

CREATE TABLE waitlist_entry (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    enqueue_sequence bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    event_id uuid NOT NULL REFERENCES event (id) ON DELETE RESTRICT,
    account_id uuid NOT NULL REFERENCES account (id) ON DELETE RESTRICT,
    preferred_section_id uuid REFERENCES section (id) ON DELETE RESTRICT,
    quantity integer NOT NULL,
    state varchar(20) NOT NULL DEFAULT 'WAITING',
    offer_hold_id uuid UNIQUE REFERENCES seat_hold (id) ON DELETE RESTRICT,
    claimed_hold_id uuid UNIQUE REFERENCES seat_hold (id) ON DELETE RESTRICT,
    offered_at timestamptz,
    offer_expires_at timestamptz,
    resolved_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT waitlist_quantity_valid CHECK (quantity BETWEEN 1 AND 6),
    CONSTRAINT waitlist_state_valid CHECK (
        state IN ('WAITING', 'OFFERED', 'CLAIMED', 'CANCELLED', 'EXPIRED')
    ),
    CONSTRAINT waitlist_offer_order CHECK (
        offer_expires_at IS NULL OR
        (offered_at IS NOT NULL AND offer_expires_at > offered_at)
    ),
    CONSTRAINT waitlist_state_fields_consistent CHECK (
        (state = 'WAITING' AND offer_hold_id IS NULL AND claimed_hold_id IS NULL
            AND offered_at IS NULL AND offer_expires_at IS NULL AND resolved_at IS NULL)
        OR (state = 'OFFERED' AND offer_hold_id IS NOT NULL AND claimed_hold_id IS NULL
            AND offered_at IS NOT NULL AND offer_expires_at IS NOT NULL AND resolved_at IS NULL)
        OR (state = 'CLAIMED' AND offer_hold_id IS NOT NULL AND claimed_hold_id IS NOT NULL
            AND offered_at IS NOT NULL AND offer_expires_at IS NOT NULL AND resolved_at IS NOT NULL)
        OR (state = 'EXPIRED' AND offer_hold_id IS NOT NULL AND claimed_hold_id IS NULL
            AND offered_at IS NOT NULL AND offer_expires_at IS NOT NULL AND resolved_at IS NOT NULL)
        OR (state = 'CANCELLED' AND claimed_hold_id IS NULL AND resolved_at IS NOT NULL)
    ),
    CONSTRAINT waitlist_version_nonnegative CHECK (version >= 0)
);

CREATE UNIQUE INDEX waitlist_one_active_customer_event
    ON waitlist_entry (account_id, event_id)
    WHERE state IN ('WAITING', 'OFFERED');
CREATE INDEX waitlist_fifo_idx
    ON waitlist_entry (event_id, enqueue_sequence)
    WHERE state = 'WAITING';
CREATE INDEX waitlist_offer_expiry_idx
    ON waitlist_entry (event_id, offer_expires_at)
    WHERE state = 'OFFERED';
