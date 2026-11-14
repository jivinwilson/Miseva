package com.neonicz.miseva;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

final class TestCleanup {

    private TestCleanup() {
    }

    static void removeEverythingFor(JdbcTemplate owner, String emailDomain) {
        String like = "%" + emailDomain;
        List<UUID> businesses = owner.queryForList(
                "SELECT DISTINCT m.business_id FROM business_memberships m JOIN users u ON u.id = m.user_id WHERE u.email LIKE ?",
                UUID.class, like);
        owner.execute("ALTER TABLE audit_events DISABLE TRIGGER trg_audit_events_immutable");
        try {
            for (UUID b : businesses) {
                owner.update("DELETE FROM audit_events WHERE business_id = ?", b);
            }
        } finally {
            owner.execute("ALTER TABLE audit_events ENABLE TRIGGER trg_audit_events_immutable");
        }
        owner.update("DELETE FROM auth_sessions WHERE user_id IN (SELECT id FROM users WHERE email LIKE ?)", like);
        for (UUID b : businesses) {
            for (String table : new String[] {"stock_movements", "recipe_lines", "guest_order_lines", "guest_orders", "payments", "bill_lines", "bills", "order_lines", "dining_sessions",
                    "dining_tables", "menu_items", "ingredients", "menu_categories"}) {
                owner.update("DELETE FROM " + table + " WHERE business_id = ?", b);
            }
            owner.update("DELETE FROM branch_access WHERE business_id = ?", b);
            owner.update("DELETE FROM membership_roles WHERE business_id = ?", b);
            owner.update("DELETE FROM employee_profiles WHERE business_id = ?", b);
            owner.update("DELETE FROM business_memberships WHERE business_id = ?", b);
            owner.update("DELETE FROM subscriptions WHERE business_id = ?", b);
            owner.update("DELETE FROM branches WHERE business_id = ?", b);
            owner.update("DELETE FROM businesses WHERE id = ?", b);
        }
        owner.update("DELETE FROM users WHERE email LIKE ?", like);
    }
}
