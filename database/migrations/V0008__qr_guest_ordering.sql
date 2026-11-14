CREATE FUNCTION new_qr_token() RETURNS text
    LANGUAGE sql VOLATILE AS $$ SELECT replace(gen_random_uuid()::text, '-', '') || replace(gen_random_uuid()::text, '-', '') $$;

ALTER TABLE dining_tables ADD COLUMN qr_token text;
UPDATE dining_tables SET qr_token = new_qr_token();
ALTER TABLE dining_tables ALTER COLUMN qr_token SET NOT NULL, ALTER COLUMN qr_token SET DEFAULT new_qr_token();
ALTER TABLE dining_tables ADD CONSTRAINT uq_dining_tables_qr_token UNIQUE (qr_token);

CREATE TABLE guest_orders (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses (id),
    branch_id   uuid NOT NULL,
    table_id    uuid NOT NULL,
    status      text NOT NULL DEFAULT 'pending',
    guests      integer NOT NULL DEFAULT 1,
    note        text,
    session_id  uuid REFERENCES dining_sessions (id),
    decided_by  uuid REFERENCES users (id),
    decided_at  timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_guest_orders_table FOREIGN KEY (business_id, branch_id, table_id)
        REFERENCES dining_tables (business_id, branch_id, id),
    CONSTRAINT uq_guest_orders_scope UNIQUE (business_id, id),
    CONSTRAINT ck_guest_orders_status CHECK (status IN ('pending', 'accepted', 'rejected')),
    CONSTRAINT ck_guest_orders_guests CHECK (guests BETWEEN 1 AND 50)
);
CREATE INDEX ix_guest_orders_pending ON guest_orders (business_id, branch_id, status, created_at);
CREATE INDEX ix_guest_orders_table ON guest_orders (table_id, status);

CREATE TABLE guest_order_lines (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id      uuid NOT NULL REFERENCES businesses (id),
    guest_order_id   uuid NOT NULL,
    menu_item_id     uuid NOT NULL,
    item_name        text NOT NULL,
    unit_price_minor bigint NOT NULL,
    tax_rate_bp      integer NOT NULL,
    quantity         integer NOT NULL,
    note             text,
    CONSTRAINT fk_guest_order_lines_order FOREIGN KEY (business_id, guest_order_id) REFERENCES guest_orders (business_id, id),
    CONSTRAINT fk_guest_order_lines_item FOREIGN KEY (business_id, menu_item_id) REFERENCES menu_items (business_id, id),
    CONSTRAINT ck_guest_order_lines_quantity CHECK (quantity BETWEEN 1 AND 20)
);
CREATE INDEX ix_guest_order_lines_order ON guest_order_lines (guest_order_id);

ALTER TABLE guest_orders ENABLE ROW LEVEL SECURITY;
CREATE POLICY guest_orders_tenant ON guest_orders
    USING (business_id = app_business_id()) WITH CHECK (business_id = app_business_id());
ALTER TABLE guest_order_lines ENABLE ROW LEVEL SECURITY;
CREATE POLICY guest_order_lines_tenant ON guest_order_lines
    USING (business_id = app_business_id()) WITH CHECK (business_id = app_business_id());

REVOKE DELETE, TRUNCATE ON guest_orders, guest_order_lines FROM misva_app;
REVOKE UPDATE ON guest_order_lines FROM misva_app;

CREATE FUNCTION public_table_by_qr(p_token text)
    RETURNS TABLE (business_id uuid, branch_id uuid, table_id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = miseva, pg_temp
AS $$
    SELECT t.business_id, t.branch_id, t.id
    FROM dining_tables t
    JOIN branches br ON br.id = t.branch_id AND br.status = 'active'
    JOIN businesses b ON b.id = t.business_id AND b.status = 'active'
    WHERE t.qr_token = p_token AND t.is_active
$$;
REVOKE ALL ON FUNCTION public_table_by_qr(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public_table_by_qr(text) TO misva_app;
