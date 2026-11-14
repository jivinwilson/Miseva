ALTER TABLE dining_tables ADD COLUMN waiter_membership_id uuid;
ALTER TABLE dining_tables ADD CONSTRAINT fk_dining_tables_waiter
    FOREIGN KEY (business_id, waiter_membership_id) REFERENCES business_memberships (business_id, id);

ALTER TABLE order_lines ADD COLUMN guest_order_line_id uuid REFERENCES guest_order_lines (id);
CREATE INDEX ix_order_lines_guest_line ON order_lines (guest_order_line_id) WHERE guest_order_line_id IS NOT NULL;

CREATE TABLE service_requests (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses (id),
    branch_id   uuid NOT NULL,
    table_id    uuid NOT NULL,
    type        text NOT NULL,
    status      text NOT NULL DEFAULT 'open',
    created_at  timestamptz NOT NULL DEFAULT now(),
    resolved_by uuid REFERENCES users (id),
    resolved_at timestamptz,
    CONSTRAINT fk_service_requests_table FOREIGN KEY (business_id, branch_id, table_id)
        REFERENCES dining_tables (business_id, branch_id, id),
    CONSTRAINT ck_service_requests_type CHECK (type IN ('call_waiter', 'request_bill')),
    CONSTRAINT ck_service_requests_status CHECK (status IN ('open', 'done'))
);
CREATE UNIQUE INDEX uq_service_requests_open ON service_requests (table_id, type) WHERE status = 'open';
CREATE INDEX ix_service_requests_branch ON service_requests (business_id, branch_id, status);

ALTER TABLE service_requests ENABLE ROW LEVEL SECURITY;
CREATE POLICY service_requests_tenant ON service_requests
    USING (business_id = app_business_id()) WITH CHECK (business_id = app_business_id());
REVOKE DELETE, TRUNCATE ON service_requests FROM misva_app;
