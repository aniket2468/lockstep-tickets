CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE event_seat
    ADD CONSTRAINT event_seat_event_identity_unique UNIQUE (event_id, id);

CREATE TABLE customer_event_limit (
    account_id uuid NOT NULL REFERENCES account (id) ON DELETE RESTRICT,
    event_id uuid NOT NULL REFERENCES event (id) ON DELETE RESTRICT,
    active_hold_seat_count integer NOT NULL DEFAULT 0,
    confirmed_ticket_count integer NOT NULL DEFAULT 0,
    version bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (account_id, event_id),
    CONSTRAINT customer_event_hold_count_valid CHECK (
        active_hold_seat_count BETWEEN 0 AND 12
    ),
    CONSTRAINT customer_event_ticket_count_valid CHECK (
        confirmed_ticket_count BETWEEN 0 AND 12
    ),
    CONSTRAINT customer_event_total_limit CHECK (
        active_hold_seat_count + confirmed_ticket_count <= 12
    ),
    CONSTRAINT customer_event_limit_version_nonnegative CHECK (version >= 0)
);

CREATE TABLE seat_hold (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id uuid NOT NULL REFERENCES event (id) ON DELETE RESTRICT,
    account_id uuid NOT NULL REFERENCES account (id) ON DELETE RESTRICT,
    state varchar(20) NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    ended_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT seat_hold_state_valid CHECK (
        state IN ('ACTIVE', 'CONSUMED', 'EXPIRED', 'RELEASED')
    ),
    CONSTRAINT seat_hold_expiry_order CHECK (expires_at > created_at),
    CONSTRAINT seat_hold_end_order CHECK (ended_at IS NULL OR ended_at >= created_at),
    CONSTRAINT seat_hold_state_end_consistent CHECK (
        (state = 'ACTIVE' AND ended_at IS NULL)
        OR (state <> 'ACTIVE' AND ended_at IS NOT NULL)
    ),
    CONSTRAINT seat_hold_version_nonnegative CHECK (version >= 0),
    CONSTRAINT seat_hold_event_identity_unique UNIQUE (event_id, id)
);

CREATE UNIQUE INDEX seat_hold_one_active_per_customer_event
    ON seat_hold (account_id, event_id)
    WHERE state = 'ACTIVE';
CREATE INDEX seat_hold_expiry_idx ON seat_hold (event_id, expires_at)
    WHERE state = 'ACTIVE';

CREATE TABLE seat_hold_item (
    hold_id uuid NOT NULL,
    event_id uuid NOT NULL,
    event_seat_id uuid NOT NULL,
    claim_started_at timestamptz NOT NULL,
    claim_ends_at timestamptz NOT NULL,
    PRIMARY KEY (hold_id, event_seat_id),
    CONSTRAINT seat_hold_item_hold_same_event FOREIGN KEY (event_id, hold_id)
        REFERENCES seat_hold (event_id, id) ON DELETE RESTRICT,
    CONSTRAINT seat_hold_item_seat_same_event FOREIGN KEY (event_id, event_seat_id)
        REFERENCES event_seat (event_id, id) ON DELETE RESTRICT,
    CONSTRAINT seat_hold_item_claim_order CHECK (claim_ends_at > claim_started_at),
    CONSTRAINT seat_hold_item_no_overlapping_claims EXCLUDE USING gist (
        event_seat_id WITH =,
        tstzrange(claim_started_at, claim_ends_at, '[)') WITH &&
    )
);

CREATE INDEX seat_hold_item_hold_idx ON seat_hold_item (hold_id, event_seat_id);
