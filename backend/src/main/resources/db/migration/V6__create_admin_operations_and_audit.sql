CREATE TABLE event_reschedule (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id uuid NOT NULL REFERENCES event (id) ON DELETE RESTRICT,
    previous_starts_at timestamptz NOT NULL,
    new_starts_at timestamptz NOT NULL,
    announced_at timestamptz NOT NULL,
    refund_window_ends_at timestamptz NOT NULL,
    actor_account_id uuid NOT NULL REFERENCES account (id) ON DELETE RESTRICT,
    reason varchar(500) NOT NULL,
    CONSTRAINT event_reschedule_changed CHECK (previous_starts_at <> new_starts_at),
    CONSTRAINT event_reschedule_window_order CHECK (refund_window_ends_at > announced_at),
    CONSTRAINT event_reschedule_reason_valid CHECK (length(btrim(reason)) BETWEEN 1 AND 500)
);

CREATE INDEX event_reschedule_event_idx
    ON event_reschedule (event_id, announced_at DESC, id DESC);

CREATE TABLE admin_audit (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_account_id uuid NOT NULL REFERENCES account (id) ON DELETE RESTRICT,
    action varchar(80) NOT NULL,
    entity_type varchar(40) NOT NULL,
    entity_id uuid NOT NULL,
    reason varchar(500) NOT NULL,
    before_state jsonb NOT NULL,
    after_state jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT admin_audit_action_valid CHECK (length(btrim(action)) BETWEEN 1 AND 80),
    CONSTRAINT admin_audit_entity_type_valid CHECK (length(btrim(entity_type)) BETWEEN 1 AND 40),
    CONSTRAINT admin_audit_reason_valid CHECK (length(btrim(reason)) BETWEEN 1 AND 500),
    CONSTRAINT admin_audit_before_object CHECK (jsonb_typeof(before_state) = 'object'),
    CONSTRAINT admin_audit_after_object CHECK (jsonb_typeof(after_state) = 'object')
);

CREATE INDEX admin_audit_feed_idx ON admin_audit (created_at DESC, id DESC);
CREATE INDEX admin_audit_entity_idx ON admin_audit (entity_type, entity_id, created_at DESC);

CREATE FUNCTION reject_admin_audit_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'admin_audit is append-only';
END;
$$;

CREATE TRIGGER admin_audit_no_update
BEFORE UPDATE ON admin_audit
FOR EACH ROW EXECUTE FUNCTION reject_admin_audit_mutation();

CREATE TRIGGER admin_audit_no_delete
BEFORE DELETE ON admin_audit
FOR EACH ROW EXECUTE FUNCTION reject_admin_audit_mutation();
