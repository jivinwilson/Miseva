package com.neonicz.miseva.industry.restaurant;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class TableRepository {

    record TableRow(UUID id, UUID branchId, String code, int seats, String area, boolean active) {
    }

    record TableView(UUID id, String code, int seats, String area, boolean active, String qrToken, UUID sessionId,
                     String sessionStatus, Integer guests, OffsetDateTime openedAt, long openLines, long amountMinor,
                     long pendingGuestOrders, UUID waiterMembershipId, String waiterName, long openRequests) {
    }

    record Waiter(UUID membershipId, String name) {
    }

    private final JdbcClient jdbc;

    TableRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    UUID insert(UUID businessId, UUID branchId, String code, int seats, String area, UUID by) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO dining_tables (id, business_id, branch_id, code, seats, area, created_by, updated_by)"
                        + " VALUES (:id, :b, :br, :code, :seats, :area, :by, :by)")
                .param("id", id).param("b", businessId).param("br", branchId).param("code", code)
                .param("seats", seats).param("area", area).param("by", by).update();
        return id;
    }

    void update(UUID businessId, UUID id, int seats, String area, boolean active, UUID by) {
        jdbc.sql("UPDATE dining_tables SET seats = :seats, area = :area, is_active = :active, updated_by = :by"
                        + " WHERE id = :id AND business_id = :b")
                .param("seats", seats).param("area", area).param("active", active).param("by", by)
                .param("id", id).param("b", businessId).update();
    }

    void assignWaiter(UUID businessId, UUID tableId, UUID membershipId, UUID by) {
        jdbc.sql("UPDATE dining_tables SET waiter_membership_id = :m, updated_by = :by WHERE id = :id AND business_id = :b")
                .param("m", membershipId).param("by", by).param("id", tableId).param("b", businessId).update();
    }

    List<Waiter> waiters(UUID businessId, UUID branchId) {
        return jdbc.sql("SELECT DISTINCT m.id, trim(p.first_name || ' ' || p.last_name) AS name"
                        + " FROM business_memberships m JOIN employee_profiles p ON p.membership_id = m.id"
                        + " JOIN membership_roles mr ON mr.membership_id = m.id"
                        + " JOIN role_permissions rp ON rp.role_id = mr.role_id"
                        + " JOIN permissions perm ON perm.id = rp.permission_id AND perm.code = 'restaurant.order.take'"
                        + " WHERE m.business_id = :b AND m.status = 'active'"
                        + " AND (m.all_branches OR EXISTS (SELECT 1 FROM branch_access a WHERE a.membership_id = m.id AND a.branch_id = :br))"
                        + " ORDER BY name")
                .param("b", businessId).param("br", branchId)
                .query((rs, n) -> new Waiter(rs.getObject("id", UUID.class), rs.getString("name")))
                .list();
    }

    void regenerateQr(UUID businessId, UUID id, UUID by) {
        jdbc.sql("UPDATE dining_tables SET qr_token = new_qr_token(), updated_by = :by WHERE id = :id AND business_id = :b")
                .param("by", by).param("id", id).param("b", businessId).update();
    }

    Optional<TableRow> find(UUID businessId, UUID id) {
        return jdbc.sql("SELECT id, branch_id, code, seats, area, is_active FROM dining_tables WHERE business_id = :b AND id = :id")
                .param("b", businessId).param("id", id)
                .query((rs, n) -> new TableRow(rs.getObject("id", UUID.class), rs.getObject("branch_id", UUID.class),
                        rs.getString("code"), rs.getInt("seats"), rs.getString("area"), rs.getBoolean("is_active")))
                .optional();
    }

    List<TableView> listWithSessions(UUID businessId, UUID branchId) {
        return jdbc.sql("SELECT t.id, t.code, t.seats, t.area, t.is_active, t.qr_token, s.id AS session_id, s.status AS session_status,"
                        + " t.waiter_membership_id, trim(p.first_name || ' ' || p.last_name) AS waiter_name,"
                        + " (SELECT count(*) FROM service_requests r WHERE r.table_id = t.id AND r.status = 'open') AS open_requests,"
                        + " s.guests, s.opened_at,"
                        + " (SELECT count(*) FROM order_lines l WHERE l.session_id = s.id AND l.status IN ('new', 'preparing', 'ready')) AS open_lines,"
                        + " (SELECT coalesce(sum(l.unit_price_minor * l.quantity), 0) FROM order_lines l"
                        + "   WHERE l.session_id = s.id AND l.status <> 'cancelled') AS amount,"
                        + " (SELECT count(*) FROM guest_orders g WHERE g.table_id = t.id AND g.status = 'pending') AS pending"
                        + " FROM dining_tables t"
                        + " LEFT JOIN employee_profiles p ON p.membership_id = t.waiter_membership_id"
                        + " LEFT JOIN dining_sessions s ON s.table_id = t.id AND s.status IN ('open', 'billed')"
                        + " WHERE t.business_id = :b AND t.branch_id = :br ORDER BY t.code")
                .param("b", businessId).param("br", branchId)
                .query((rs, n) -> new TableView(rs.getObject("id", UUID.class), rs.getString("code"), rs.getInt("seats"),
                        rs.getString("area"), rs.getBoolean("is_active"), rs.getString("qr_token"),
                        rs.getObject("session_id", UUID.class), rs.getString("session_status"), (Integer) rs.getObject("guests"),
                        rs.getObject("opened_at", OffsetDateTime.class), rs.getLong("open_lines"), rs.getLong("amount"),
                        rs.getLong("pending"), rs.getObject("waiter_membership_id", UUID.class), rs.getString("waiter_name"),
                        rs.getLong("open_requests")))
                .list();
    }
}
