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
                     long pendingGuestOrders) {
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
                        + " s.guests, s.opened_at,"
                        + " (SELECT count(*) FROM order_lines l WHERE l.session_id = s.id AND l.status IN ('new', 'preparing', 'ready')) AS open_lines,"
                        + " (SELECT coalesce(sum(l.unit_price_minor * l.quantity), 0) FROM order_lines l"
                        + "   WHERE l.session_id = s.id AND l.status <> 'cancelled') AS amount,"
                        + " (SELECT count(*) FROM guest_orders g WHERE g.table_id = t.id AND g.status = 'pending') AS pending"
                        + " FROM dining_tables t"
                        + " LEFT JOIN dining_sessions s ON s.table_id = t.id AND s.status IN ('open', 'billed')"
                        + " WHERE t.business_id = :b AND t.branch_id = :br ORDER BY t.code")
                .param("b", businessId).param("br", branchId)
                .query((rs, n) -> new TableView(rs.getObject("id", UUID.class), rs.getString("code"), rs.getInt("seats"),
                        rs.getString("area"), rs.getBoolean("is_active"), rs.getString("qr_token"),
                        rs.getObject("session_id", UUID.class), rs.getString("session_status"), (Integer) rs.getObject("guests"),
                        rs.getObject("opened_at", OffsetDateTime.class), rs.getLong("open_lines"), rs.getLong("amount"),
                        rs.getLong("pending")))
                .list();
    }
}
