CREATE TABLE account (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email varchar(320) NOT NULL,
    password_hash varchar(100) NOT NULL,
    role varchar(20) NOT NULL,
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    updated_at timestamptz NOT NULL DEFAULT current_timestamp,
    CONSTRAINT account_email_normalized CHECK (email = lower(btrim(email))),
    CONSTRAINT account_email_nonblank CHECK (length(btrim(email)) > 3),
    CONSTRAINT account_password_is_hash CHECK (password_hash LIKE '$2%'),
    CONSTRAINT account_role_valid CHECK (role IN ('CUSTOMER', 'ADMIN'))
);

CREATE UNIQUE INDEX account_email_unique ON account (email);

CREATE TABLE access_session (
    token_hash bytea PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES account (id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    CONSTRAINT access_session_token_sha256 CHECK (octet_length(token_hash) = 32),
    CONSTRAINT access_session_expiry_order CHECK (expires_at > created_at),
    CONSTRAINT access_session_revocation_order CHECK (
        revoked_at IS NULL OR revoked_at >= created_at
    )
);

CREATE INDEX access_session_account_idx ON access_session (account_id);
CREATE INDEX access_session_expiry_idx ON access_session (expires_at)
    WHERE revoked_at IS NULL;

CREATE TABLE login_throttle (
    identifier_hash bytea PRIMARY KEY,
    window_started_at timestamptz NOT NULL,
    failure_count integer NOT NULL,
    blocked_until timestamptz,
    CONSTRAINT login_throttle_identifier_sha256 CHECK (octet_length(identifier_hash) = 32),
    CONSTRAINT login_throttle_failure_positive CHECK (failure_count > 0),
    CONSTRAINT login_throttle_block_order CHECK (
        blocked_until IS NULL OR blocked_until > window_started_at
    )
);

CREATE INDEX login_throttle_blocked_idx ON login_throttle (blocked_until)
    WHERE blocked_until IS NOT NULL;

CREATE TABLE venue (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name varchar(160) NOT NULL,
    time_zone varchar(64) NOT NULL,
    address varchar(500) NOT NULL,
    retired_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    CONSTRAINT venue_name_nonblank CHECK (length(btrim(name)) > 0),
    CONSTRAINT venue_zone_nonblank CHECK (length(btrim(time_zone)) > 0),
    CONSTRAINT venue_address_nonblank CHECK (length(btrim(address)) > 0)
);

CREATE TABLE section (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    venue_id uuid NOT NULL REFERENCES venue (id) ON DELETE RESTRICT,
    name varchar(100) NOT NULL,
    display_order integer NOT NULL,
    retired_at timestamptz,
    CONSTRAINT section_name_nonblank CHECK (length(btrim(name)) > 0),
    CONSTRAINT section_display_order_nonnegative CHECK (display_order >= 0),
    CONSTRAINT section_identity_unique UNIQUE (venue_id, name),
    CONSTRAINT section_order_unique UNIQUE (venue_id, display_order)
);

CREATE TABLE seat (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    section_id uuid NOT NULL REFERENCES section (id) ON DELETE RESTRICT,
    row_label varchar(20) NOT NULL,
    seat_number varchar(20) NOT NULL,
    display_order integer NOT NULL,
    accessible boolean NOT NULL DEFAULT false,
    retired_at timestamptz,
    CONSTRAINT seat_row_nonblank CHECK (length(btrim(row_label)) > 0),
    CONSTRAINT seat_number_nonblank CHECK (length(btrim(seat_number)) > 0),
    CONSTRAINT seat_display_order_nonnegative CHECK (display_order >= 0),
    CONSTRAINT seat_identity_unique UNIQUE (section_id, row_label, seat_number),
    CONSTRAINT seat_order_unique UNIQUE (section_id, display_order)
);

CREATE TABLE event (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    venue_id uuid NOT NULL REFERENCES venue (id) ON DELETE RESTRICT,
    name varchar(200) NOT NULL,
    state varchar(30) NOT NULL DEFAULT 'DRAFT',
    sales_start_at timestamptz NOT NULL,
    sales_end_at timestamptz NOT NULL,
    starts_at timestamptz NOT NULL,
    time_zone varchar(64) NOT NULL,
    currency char(3) NOT NULL,
    service_fee_minor bigint NOT NULL DEFAULT 0,
    version bigint NOT NULL DEFAULT 0,
    retired_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    updated_at timestamptz NOT NULL DEFAULT current_timestamp,
    CONSTRAINT event_name_nonblank CHECK (length(btrim(name)) > 0),
    CONSTRAINT event_state_valid CHECK (
        state IN ('DRAFT', 'PUBLISHED', 'ON_SALE', 'SALES_CLOSED', 'COMPLETED', 'CANCELLED')
    ),
    CONSTRAINT event_time_order CHECK (
        sales_start_at < sales_end_at AND sales_end_at <= starts_at
    ),
    CONSTRAINT event_zone_nonblank CHECK (length(btrim(time_zone)) > 0),
    CONSTRAINT event_currency_uppercase CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT event_service_fee_nonnegative CHECK (service_fee_minor >= 0),
    CONSTRAINT event_version_nonnegative CHECK (version >= 0)
);

CREATE INDEX event_catalog_idx ON event (state, starts_at, id)
    WHERE retired_at IS NULL;

CREATE TABLE price_tier (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id uuid NOT NULL REFERENCES event (id) ON DELETE RESTRICT,
    name varchar(100) NOT NULL,
    price_minor bigint NOT NULL,
    currency char(3) NOT NULL,
    retired_at timestamptz,
    CONSTRAINT price_tier_name_nonblank CHECK (length(btrim(name)) > 0),
    CONSTRAINT price_tier_price_nonnegative CHECK (price_minor >= 0),
    CONSTRAINT price_tier_currency_uppercase CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT price_tier_name_unique UNIQUE (event_id, name),
    CONSTRAINT price_tier_event_identity_unique UNIQUE (event_id, id)
);

CREATE TABLE event_seat (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id uuid NOT NULL REFERENCES event (id) ON DELETE RESTRICT,
    source_seat_id uuid NOT NULL REFERENCES seat (id) ON DELETE RESTRICT,
    price_tier_id uuid NOT NULL,
    section_name varchar(100) NOT NULL,
    row_label varchar(20) NOT NULL,
    seat_number varchar(20) NOT NULL,
    display_order integer NOT NULL,
    accessible boolean NOT NULL,
    inventory_state varchar(20) NOT NULL DEFAULT 'AVAILABLE',
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT event_seat_tier_same_event FOREIGN KEY (event_id, price_tier_id)
        REFERENCES price_tier (event_id, id) ON DELETE RESTRICT,
    CONSTRAINT event_seat_snapshot_nonblank CHECK (
        length(btrim(section_name)) > 0
        AND length(btrim(row_label)) > 0
        AND length(btrim(seat_number)) > 0
    ),
    CONSTRAINT event_seat_display_order_nonnegative CHECK (display_order >= 0),
    CONSTRAINT event_seat_state_valid CHECK (inventory_state IN ('AVAILABLE', 'BLOCKED')),
    CONSTRAINT event_seat_version_nonnegative CHECK (version >= 0),
    CONSTRAINT event_seat_source_unique UNIQUE (event_id, source_seat_id),
    CONSTRAINT event_seat_label_unique UNIQUE (event_id, section_name, row_label, seat_number),
    CONSTRAINT event_seat_order_unique UNIQUE (event_id, display_order)
);

CREATE INDEX event_seat_availability_idx
    ON event_seat (event_id, inventory_state, display_order, id);
