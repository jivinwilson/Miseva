CREATE TABLE ingredients (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id         uuid NOT NULL REFERENCES businesses (id),
    name                text NOT NULL,
    unit                text NOT NULL,
    low_stock_threshold numeric(14, 3) NOT NULL DEFAULT 0,
    is_active           boolean NOT NULL DEFAULT true,
    created_at          timestamptz NOT NULL DEFAULT now(),
    created_by          uuid,
    updated_at          timestamptz NOT NULL DEFAULT now(),
    updated_by          uuid,
    version             bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_ingredients_name UNIQUE (business_id, name),
    CONSTRAINT uq_ingredients_scope UNIQUE (business_id, id),
    CONSTRAINT ck_ingredients_unit CHECK (unit IN ('g', 'ml', 'pcs')),
    CONSTRAINT ck_ingredients_threshold CHECK (low_stock_threshold >= 0)
);
CREATE TRIGGER trg_ingredients_touch BEFORE UPDATE ON ingredients
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

CREATE TABLE recipe_lines (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id   uuid NOT NULL REFERENCES businesses (id),
    menu_item_id  uuid NOT NULL,
    ingredient_id uuid NOT NULL,
    quantity      numeric(14, 3) NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    created_by    uuid,
    CONSTRAINT fk_recipe_lines_item FOREIGN KEY (business_id, menu_item_id) REFERENCES menu_items (business_id, id),
    CONSTRAINT fk_recipe_lines_ingredient FOREIGN KEY (business_id, ingredient_id) REFERENCES ingredients (business_id, id),
    CONSTRAINT uq_recipe_lines UNIQUE (menu_item_id, ingredient_id),
    CONSTRAINT ck_recipe_lines_quantity CHECK (quantity > 0)
);
CREATE INDEX ix_recipe_lines_item ON recipe_lines (menu_item_id);

CREATE TABLE stock_movements (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id   uuid NOT NULL REFERENCES businesses (id),
    branch_id     uuid NOT NULL,
    ingredient_id uuid NOT NULL,
    quantity      numeric(14, 3) NOT NULL,
    reason        text NOT NULL,
    reference_id  uuid,
    note          text,
    created_by    uuid REFERENCES users (id),
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_stock_movements_branch FOREIGN KEY (business_id, branch_id) REFERENCES branches (business_id, id),
    CONSTRAINT fk_stock_movements_ingredient FOREIGN KEY (business_id, ingredient_id) REFERENCES ingredients (business_id, id),
    CONSTRAINT ck_stock_movements_reason CHECK (reason IN ('purchase', 'waste', 'adjustment', 'consumption')),
    CONSTRAINT ck_stock_movements_quantity CHECK (quantity <> 0),
    CONSTRAINT ck_stock_movements_sign CHECK (
        (reason = 'purchase' AND quantity > 0) OR (reason IN ('waste', 'consumption') AND quantity < 0) OR reason = 'adjustment')
);
CREATE INDEX ix_stock_movements_level ON stock_movements (business_id, branch_id, ingredient_id);
CREATE INDEX ix_stock_movements_recent ON stock_movements (business_id, branch_id, created_at DESC);
CREATE UNIQUE INDEX uq_stock_movements_consumption ON stock_movements (reference_id, ingredient_id) WHERE reason = 'consumption';

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['ingredients', 'recipe_lines', 'stock_movements']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY %I ON %I USING (business_id = app_business_id()) WITH CHECK (business_id = app_business_id())',
                       t || '_tenant', t);
    END LOOP;
END $$;

REVOKE DELETE, TRUNCATE ON ingredients, stock_movements FROM misva_app;
REVOKE UPDATE ON stock_movements, recipe_lines FROM misva_app;
REVOKE TRUNCATE ON recipe_lines FROM misva_app;

INSERT INTO permissions (code, module, description) VALUES
    ('restaurant.inventory.read',   'restaurant', 'View ingredients, recipes and stock'),
    ('restaurant.inventory.manage', 'restaurant', 'Manage ingredients and recipes and record stock movements');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code IN ('restaurant.inventory.read', 'restaurant.inventory.manage')
WHERE r.business_id IS NULL AND r.code IN ('owner', 'manager');
