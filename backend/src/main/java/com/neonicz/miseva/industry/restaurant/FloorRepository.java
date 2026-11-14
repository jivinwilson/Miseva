package com.neonicz.miseva.industry.restaurant;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class FloorRepository {

    record Alert(String kind, UUID id, String tableCode, UUID waiterMembershipId, String detail, OffsetDateTime at) {
    }

    record RequestRef(UUID id, UUID branchId, String status) {
    }

    private final JdbcClient jdbc;

    FloorRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    boolean openRequest(UUID businessId, UUID branchId, UUID tableId, String type) {
        return jdbc.sql("INSERT INTO service_requests (business_id, branch_id, table_id, type) VALUES (:b, :br, :t, :type)"
                        + " ON CONFLICT (table_id, type) WHERE status = 'open' DO NOTHING")
                .param("b", businessId).param("br", branchId).param("t", tableId).param("type", type).update() == 1;
    }

    Optional<RequestRef> request(UUID businessId, UUID id) {
        return jdbc.sql("SELECT id, branch_id, status FROM service_requests WHERE business_id = :b AND id = :id")
                .param("b", businessId).param("id", id)
                .query((rs, n) -> new RequestRef(rs.getObject("id", UUID.class), rs.getObject("branch_id", UUID.class),
                        rs.getString("status")))
                .optional();
    }

    void resolveRequest(UUID businessId, UUID id, UUID by) {
        jdbc.sql("UPDATE service_requests SET status = 'done', resolved_by = :by, resolved_at = now()"
                        + " WHERE business_id = :b AND id = :id AND status = 'open'")
                .param("by", by).param("b", businessId).param("id", id).update();
    }

    boolean membershipActive(UUID businessId, UUID membershipId) {
        return jdbc.sql("SELECT count(*) FROM business_memberships WHERE business_id = :b AND id = :m AND status = 'active'")
                .param("b", businessId).param("m", membershipId).query(Integer.class).single() > 0;
    }

    List<Alert> alerts(UUID businessId, UUID branchId) {
        return jdbc.sql("SELECT 'guest_order' AS kind, g.id, t.code, t.waiter_membership_id,"
                        + "   (SELECT string_agg(gl.quantity || ' × ' || gl.item_name, ', ' ORDER BY gl.item_name)"
                        + "    FROM guest_order_lines gl WHERE gl.guest_order_id = g.id) AS detail, g.created_at AS at"
                        + " FROM guest_orders g JOIN dining_tables t ON t.id = g.table_id"
                        + " WHERE g.business_id = :b AND g.branch_id = :br AND g.status = 'pending'"
                        + " UNION ALL"
                        + " SELECT 'ready', l.id, t.code, t.waiter_membership_id, l.quantity || ' × ' || l.item_name, l.status_changed_at"
                        + " FROM order_lines l JOIN dining_sessions s ON s.id = l.session_id JOIN dining_tables t ON t.id = s.table_id"
                        + " WHERE l.business_id = :b AND l.branch_id = :br AND l.status = 'ready'"
                        + " UNION ALL"
                        + " SELECT r.type, r.id, t.code, t.waiter_membership_id, NULL, r.created_at"
                        + " FROM service_requests r JOIN dining_tables t ON t.id = r.table_id"
                        + " WHERE r.business_id = :b AND r.branch_id = :br AND r.status = 'open'"
                        + " ORDER BY at")
                .param("b", businessId).param("br", branchId)
                .query((rs, n) -> new Alert(rs.getString("kind"), rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getObject("waiter_membership_id", UUID.class), rs.getString("detail"),
                        rs.getObject("at", OffsetDateTime.class)))
                .list();
    }
}
