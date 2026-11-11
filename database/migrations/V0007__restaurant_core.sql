CREATE TABLE dining_tables (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses (id),
    branch_id   uuid NOT NULL,
    code        text NOT NULL,
    seats       integer NOT NULL,
    area        text,
    is_active   boolean NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now(),
    created_by  uuid,
    updated_at  timestamptz NOT NULL DEFAULT now(),
    updated_by  uuid,
    version     bigint NOT NULL DEFAULT 0,
    CONSTRAINT fk_dining_tables_branch FOREIGN KEY (business_id, branch_id) REFERENCES branches (business_id, id),
    CONSTRAINT uq_dining_tables_code UNIQUE (business_id, branch_id, code),
    CONSTRAINT uq_dining_tables_scope UNIQUE (business_id, branch_id, id),
    CONSTRAINT ck_dining_tables_seats CHECK (seats BETWEEN 1 AND 100)
);
CREATE TRIGGER trg_dining_tables_touch BEFORE UPDATE ON dining_tables
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

CREATE TABLE menu_categories (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses (id),
    name        text NOT NULL,
    sort_order  integer NOT NULL DEFAULT 0,
    is_active   boolean NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now(),
    created_by  uuid,
    updated_at  timestamptz NOT NULL DEFAULT now(),
    updated_by  uuid,
    version     bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_menu_categories_name UNIQUE (business_id, name),
    CONSTRAINT uq_menu_categories_scope UNIQUE (business_id, id)
);
CREATE TRIGGER trg_menu_categories_touch BEFORE UPDATE ON menu_categories
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

CREATE TABLE menu_items (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id  uuid NOT NULL REFERENCES businesses (id),
    category_id  uuid NOT NULL,
    name         text NOT NULL,
    description  text,
    price_minor  bigint NOT NULL,
    tax_rate_bp  integer NOT NULL DEFAULT 0,
    is_available boolean NOT NULL DEFAULT true,
    sort_order   integer NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL DEFAULT now(),
    created_by   uuid,
    updated_at   timestamptz NOT NULL DEFAULT now(),
    updated_by   uuid,
    version      bigint NOT NULL DEFAULT 0,
    CONSTRAINT fk_menu_items_category FOREIGN KEY (business_id, category_id) REFERENCES menu_categories (business_id, id),
    CONSTRAINT uq_menu_items_name UNIQUE (business_id, category_id, name),
    CONSTRAINT uq_menu_items_scope UNIQUE (business_id, id),
    CONSTRAINT ck_menu_items_price CHECK (price_minor >= 0),
    CONSTRAINT ck_menu_items_tax CHECK (tax_rate_bp BETWEEN 0 AND 10000)
);
CREATE TRIGGER trg_menu_items_touch BEFORE UPDATE ON menu_items
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

CREATE TABLE dining_sessions (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES businesses (id),
    branch_id   uuid NOT NULL,
    table_id    uuid NOT NULL,
    status      text NOT NULL DEFAULT 'open',
    guests      integer NOT NULL DEFAULT 1,
    opened_by   uuid REFERENCES users (id),
    opened_at   timestamptz NOT NULL DEFAULT now(),
    closed_at   timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    version     bigint NOT NULL DEFAULT 0,
    CONSTRAINT fk_dining_sessions_table FOREIGN KEY (business_id, branch_id, table_id)
        REFERENCES dining_tables (business_id, branch_id, id),
    CONSTRAINT uq_dining_sessions_scope UNIQUE (business_id, branch_id, id),
    CONSTRAINT ck_dining_sessions_status CHECK (status IN ('open', 'billed', 'closed')),
    CONSTRAINT ck_dining_sessions_guests CHECK (guests BETWEEN 1 AND 100)
);
CREATE UNIQUE INDEX uq_dining_sessions_one_live ON dining_sessions (table_id) WHERE status IN ('open', 'billed');
CREATE INDEX ix_dining_sessions_branch ON dining_sessions (business_id, branch_id, status);
CREATE TRIGGER trg_dining_sessions_touch BEFORE UPDATE ON dining_sessions
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

CREATE TABLE order_lines (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id       uuid NOT NULL REFERENCES businesses (id),
    branch_id         uuid NOT NULL,
    session_id        uuid NOT NULL,
    menu_item_id      uuid NOT NULL,
    item_name         text NOT NULL,
    unit_price_minor  bigint NOT NULL,
    tax_rate_bp       integer NOT NULL,
    quantity          integer NOT NULL,
    note              text,
    status            text NOT NULL DEFAULT 'new',
    status_changed_at timestamptz NOT NULL DEFAULT now(),
    created_by        uuid REFERENCES users (id),
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    version           bigint NOT NULL DEFAULT 0,
    CONSTRAINT fk_order_lines_session FOREIGN KEY (business_id, branch_id, session_id)
        REFERENCES dining_sessions (business_id, branch_id, id),
    CONSTRAINT fk_order_lines_item FOREIGN KEY (business_id, menu_item_id) REFERENCES menu_items (business_id, id),
    CONSTRAINT ck_order_lines_quantity CHECK (quantity BETWEEN 1 AND 999),
    CONSTRAINT ck_order_lines_price CHECK (unit_price_minor >= 0),
    CONSTRAINT ck_order_lines_tax CHECK (tax_rate_bp BETWEEN 0 AND 10000),
    CONSTRAINT ck_order_lines_status CHECK (status IN ('new', 'preparing', 'ready', 'served', 'cancelled'))
);
CREATE INDEX ix_order_lines_session ON order_lines (session_id);
CREATE INDEX ix_order_lines_kitchen ON order_lines (business_id, branch_id, status, created_at);
CREATE TRIGGER trg_order_lines_touch BEFORE UPDATE ON order_lines
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

CREATE TABLE bills (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id    uuid NOT NULL REFERENCES businesses (id),
    branch_id      uuid NOT NULL,
    session_id     uuid NOT NULL,
    bill_number    text NOT NULL,
    currency       char(3) NOT NULL,
    subtotal_minor bigint NOT NULL,
    tax_minor      bigint NOT NULL,
    total_minor    bigint NOT NULL,
    status         text NOT NULL DEFAULT 'issued',
    issued_by      uuid REFERENCES users (id),
    issued_at      timestamptz NOT NULL DEFAULT now(),
    paid_at        timestamptz,
    CONSTRAINT fk_bills_session FOREIGN KEY (business_id, branch_id, session_id)
        REFERENCES dining_sessions (business_id, branch_id, id),
    CONSTRAINT uq_bills_session UNIQUE (session_id),
    CONSTRAINT uq_bills_number UNIQUE (business_id, bill_number),
    CONSTRAINT uq_bills_scope UNIQUE (business_id, id),
    CONSTRAINT ck_bills_amounts CHECK (subtotal_minor >= 0 AND tax_minor >= 0 AND total_minor = subtotal_minor + tax_minor),
    CONSTRAINT ck_bills_status CHECK (status IN ('issued', 'paid'))
);

CREATE TABLE bill_lines (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id       uuid NOT NULL REFERENCES businesses (id),
    bill_id           uuid NOT NULL,
    item_name         text NOT NULL,
    quantity          integer NOT NULL,
    unit_price_minor  bigint NOT NULL,
    tax_rate_bp       integer NOT NULL,
    line_subtotal     bigint NOT NULL,
    line_tax          bigint NOT NULL,
    CONSTRAINT fk_bill_lines_bill FOREIGN KEY (business_id, bill_id) REFERENCES bills (business_id, id)
);
CREATE INDEX ix_bill_lines_bill ON bill_lines (bill_id);

CREATE TABLE payments (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id  uuid NOT NULL REFERENCES businesses (id),
    bill_id      uuid NOT NULL,
    method       text NOT NULL,
    amount_minor bigint NOT NULL,
    reference    text,
    received_by  uuid REFERENCES users (id),
    received_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_payments_bill FOREIGN KEY (business_id, bill_id) REFERENCES bills (business_id, id),
    CONSTRAINT ck_payments_method CHECK (method IN ('cash', 'card', 'upi', 'other')),
    CONSTRAINT ck_payments_amount CHECK (amount_minor > 0)
);
CREATE INDEX ix_payments_bill ON payments (bill_id);

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['dining_tables', 'menu_categories', 'menu_items', 'dining_sessions', 'order_lines',
                             'bills', 'bill_lines', 'payments']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY %I ON %I USING (business_id = app_business_id()) WITH CHECK (business_id = app_business_id())',
                       t || '_tenant', t);
    END LOOP;
END $$;

REVOKE DELETE, TRUNCATE ON dining_tables, menu_categories, menu_items, dining_sessions, order_lines,
    bills, bill_lines, payments FROM misva_app;
REVOKE UPDATE ON bill_lines, payments FROM misva_app;

INSERT INTO permissions (code, module, description) VALUES
    ('restaurant.table.read',     'restaurant', 'View tables and their orders'),
    ('restaurant.table.manage',   'restaurant', 'Create and edit tables'),
    ('restaurant.menu.read',      'restaurant', 'View the menu'),
    ('restaurant.menu.manage',    'restaurant', 'Create and edit menu categories and items'),
    ('restaurant.order.take',     'restaurant', 'Open tables and take orders'),
    ('restaurant.kitchen.update', 'restaurant', 'Update kitchen status of order lines'),
    ('restaurant.bill.manage',    'restaurant', 'Issue bills and record payments');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.module = 'restaurant'
WHERE r.business_id IS NULL AND r.code IN ('owner', 'manager');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code IN (
    'restaurant.table.read', 'restaurant.menu.read', 'restaurant.order.take', 'restaurant.kitchen.update')
WHERE r.business_id IS NULL AND r.code = 'staff';
