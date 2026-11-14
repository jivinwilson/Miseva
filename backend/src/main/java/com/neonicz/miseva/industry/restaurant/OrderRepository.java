package com.neonicz.miseva.industry.restaurant;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class OrderRepository {

    record Session(UUID id, UUID branchId, UUID tableId, String tableCode, String status, int guests,
                   OffsetDateTime openedAt, OffsetDateTime closedAt) {
    }

    record Line(UUID id, UUID sessionId, UUID branchId, String tableCode, String itemName, long unitPriceMinor,
                int taxRateBp, int quantity, String note, String status, OffsetDateTime createdAt,
                OffsetDateTime statusChangedAt) {
    }

    private static final String LINE_SELECT = "SELECT l.id, l.session_id, l.branch_id, t.code AS table_code, l.item_name,"
            + " l.unit_price_minor, l.tax_rate_bp, l.quantity, l.note, l.status, l.created_at, l.status_changed_at"
            + " FROM order_lines l JOIN dining_sessions s ON s.id = l.session_id JOIN dining_tables t ON t.id = s.table_id";

    private final JdbcClient jdbc;

    OrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    UUID openSession(UUID businessId, UUID branchId, UUID tableId, int guests, UUID by) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO dining_sessions (id, business_id, branch_id, table_id, guests, opened_by)"
                        + " VALUES (:id, :b, :br, :t, :g, :by)")
                .param("id", id).param("b", businessId).param("br", branchId).param("t", tableId).param("g", guests)
                .param("by", by).update();
        return id;
    }

    Optional<Session> session(UUID businessId, UUID id) {
        return jdbc.sql("SELECT s.id, s.branch_id, s.table_id, t.code, s.status, s.guests, s.opened_at, s.closed_at"
                        + " FROM dining_sessions s JOIN dining_tables t ON t.id = s.table_id WHERE s.business_id = :b AND s.id = :id")
                .param("b", businessId).param("id", id)
                .query((rs, n) -> new Session(rs.getObject("id", UUID.class), rs.getObject("branch_id", UUID.class),
                        rs.getObject("table_id", UUID.class), rs.getString("code"), rs.getString("status"), rs.getInt("guests"),
                        rs.getObject("opened_at", OffsetDateTime.class), rs.getObject("closed_at", OffsetDateTime.class)))
                .optional();
    }

    Optional<UUID> liveSessionOfTable(UUID businessId, UUID tableId) {
        return jdbc.sql("SELECT id FROM dining_sessions WHERE business_id = :b AND table_id = :t AND status IN ('open', 'billed') FOR UPDATE")
                .param("b", businessId).param("t", tableId).query(UUID.class).optional();
    }

    Session lockSession(UUID businessId, UUID id) {
        jdbc.sql("SELECT id FROM dining_sessions WHERE business_id = :b AND id = :id FOR UPDATE")
                .param("b", businessId).param("id", id).query(UUID.class).optional();
        return session(businessId, id).orElse(null);
    }

    boolean transitionSession(UUID businessId, UUID id, String from, String to) {
        return jdbc.sql("UPDATE dining_sessions SET status = :to,"
                        + " closed_at = CASE WHEN :to = 'closed' THEN now() ELSE closed_at END"
                        + " WHERE business_id = :b AND id = :id AND status = :from")
                .param("to", to).param("b", businessId).param("id", id).param("from", from).update() == 1;
    }

    List<Line> lines(UUID businessId, UUID sessionId) {
        return jdbc.sql(LINE_SELECT + " WHERE l.business_id = :b AND l.session_id = :s ORDER BY l.created_at, l.item_name")
                .param("b", businessId).param("s", sessionId).query(this::line).list();
    }

    Optional<Line> line(UUID businessId, UUID id) {
        return jdbc.sql(LINE_SELECT + " WHERE l.business_id = :b AND l.id = :id")
                .param("b", businessId).param("id", id).query(this::line).optional();
    }

    void insertLine(UUID businessId, UUID branchId, UUID sessionId, UUID itemId, String name, long price, int taxBp,
                    int quantity, String note, UUID by, UUID guestOrderLineId) {
        jdbc.sql("INSERT INTO order_lines (business_id, branch_id, session_id, menu_item_id, item_name, unit_price_minor,"
                        + " tax_rate_bp, quantity, note, created_by, guest_order_line_id)"
                        + " VALUES (:b, :br, :s, :i, :n, :p, :t, :q, :note, :by, :g)")
                .param("b", businessId).param("br", branchId).param("s", sessionId).param("i", itemId).param("n", name)
                .param("p", price).param("t", taxBp).param("q", quantity).param("note", note).param("by", by)
                .param("g", guestOrderLineId).update();
    }

    boolean transitionLine(UUID businessId, UUID id, String from, String to) {
        return jdbc.sql("UPDATE order_lines SET status = :to, status_changed_at = now()"
                        + " WHERE business_id = :b AND id = :id AND status = :from")
                .param("to", to).param("b", businessId).param("id", id).param("from", from).update() == 1;
    }

    List<Line> kitchen(UUID businessId, UUID branchId) {
        return jdbc.sql(LINE_SELECT + " WHERE l.business_id = :b AND l.branch_id = :br AND l.status IN ('new', 'preparing', 'ready')"
                        + " ORDER BY l.created_at")
                .param("b", businessId).param("br", branchId).query(this::line).list();
    }

    private Line line(ResultSet rs, int n) throws SQLException {
        return new Line(rs.getObject("id", UUID.class), rs.getObject("session_id", UUID.class),
                rs.getObject("branch_id", UUID.class), rs.getString("table_code"), rs.getString("item_name"),
                rs.getLong("unit_price_minor"), rs.getInt("tax_rate_bp"), rs.getInt("quantity"), rs.getString("note"),
                rs.getString("status"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("status_changed_at", OffsetDateTime.class));
    }
}
