package com.neonicz.miseva.industry.restaurant;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class GuestOrderRepository {

    record TableRef(UUID businessId, UUID branchId, UUID tableId) {
    }

    record PublicTable(String businessName, String branchName, String tableCode, String currency) {
    }

    record GuestOrder(UUID id, UUID branchId, UUID tableId, String tableCode, String status, int guests, String note,
                      UUID sessionId, OffsetDateTime createdAt) {
    }

    record GuestLine(UUID id, UUID guestOrderId, UUID menuItemId, String itemName, long unitPriceMinor, int taxRateBp,
                     int quantity, String note) {
    }

    private static final String ORDER_SELECT = "SELECT g.id, g.branch_id, g.table_id, t.code, g.status, g.guests, g.note,"
            + " g.session_id, g.created_at FROM guest_orders g JOIN dining_tables t ON t.id = g.table_id";

    private final JdbcClient jdbc;

    GuestOrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<TableRef> byToken(String token) {
        return jdbc.sql("SELECT business_id, branch_id, table_id FROM public_table_by_qr(:token)")
                .param("token", token)
                .query((rs, n) -> new TableRef(rs.getObject("business_id", UUID.class), rs.getObject("branch_id", UUID.class),
                        rs.getObject("table_id", UUID.class)))
                .optional();
    }

    PublicTable publicTable(TableRef ref) {
        return jdbc.sql("SELECT b.display_name, br.name, t.code, b.default_currency FROM dining_tables t"
                        + " JOIN branches br ON br.id = t.branch_id JOIN businesses b ON b.id = t.business_id"
                        + " WHERE t.business_id = :b AND t.id = :t")
                .param("b", ref.businessId()).param("t", ref.tableId())
                .query((rs, n) -> new PublicTable(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)))
                .single();
    }

    void lockTable(UUID businessId, UUID tableId) {
        jdbc.sql("SELECT id FROM dining_tables WHERE business_id = :b AND id = :t FOR UPDATE")
                .param("b", businessId).param("t", tableId).query(UUID.class).single();
    }

    long pendingForTable(UUID businessId, UUID tableId) {
        return jdbc.sql("SELECT count(*) FROM guest_orders WHERE business_id = :b AND table_id = :t AND status = 'pending'")
                .param("b", businessId).param("t", tableId).query(Long.class).single();
    }

    UUID insertOrder(TableRef ref, int guests, String note) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO guest_orders (id, business_id, branch_id, table_id, guests, note) VALUES (:id, :b, :br, :t, :g, :n)")
                .param("id", id).param("b", ref.businessId()).param("br", ref.branchId()).param("t", ref.tableId())
                .param("g", guests).param("n", note).update();
        return id;
    }

    void insertLine(UUID businessId, UUID orderId, UUID itemId, String name, long price, int taxBp, int quantity, String note) {
        jdbc.sql("INSERT INTO guest_order_lines (business_id, guest_order_id, menu_item_id, item_name, unit_price_minor,"
                        + " tax_rate_bp, quantity, note) VALUES (:b, :o, :i, :n, :p, :t, :q, :note)")
                .param("b", businessId).param("o", orderId).param("i", itemId).param("n", name).param("p", price)
                .param("t", taxBp).param("q", quantity).param("note", note).update();
    }

    Optional<GuestOrder> find(UUID businessId, UUID id) {
        return jdbc.sql(ORDER_SELECT + " WHERE g.business_id = :b AND g.id = :id")
                .param("b", businessId).param("id", id).query(this::order).optional();
    }

    Optional<GuestOrder> lock(UUID businessId, UUID id) {
        jdbc.sql("SELECT id FROM guest_orders WHERE business_id = :b AND id = :id FOR UPDATE")
                .param("b", businessId).param("id", id).query(UUID.class).optional();
        return find(businessId, id);
    }

    List<GuestOrder> pending(UUID businessId, UUID branchId) {
        return jdbc.sql(ORDER_SELECT + " WHERE g.business_id = :b AND g.branch_id = :br AND g.status = 'pending' ORDER BY g.created_at")
                .param("b", businessId).param("br", branchId).query(this::order).list();
    }

    List<GuestLine> lines(UUID businessId, UUID orderId) {
        return jdbc.sql("SELECT id, guest_order_id, menu_item_id, item_name, unit_price_minor, tax_rate_bp, quantity, note"
                        + " FROM guest_order_lines WHERE business_id = :b AND guest_order_id = :o ORDER BY item_name")
                .param("b", businessId).param("o", orderId)
                .query((rs, n) -> new GuestLine(rs.getObject("id", UUID.class), rs.getObject("guest_order_id", UUID.class),
                        rs.getObject("menu_item_id", UUID.class), rs.getString("item_name"), rs.getLong("unit_price_minor"),
                        rs.getInt("tax_rate_bp"), rs.getInt("quantity"), rs.getString("note")))
                .list();
    }

    void decide(UUID businessId, UUID id, String status, UUID sessionId, UUID by) {
        jdbc.sql("UPDATE guest_orders SET status = :s, session_id = :session, decided_by = :by, decided_at = now()"
                        + " WHERE business_id = :b AND id = :id AND status = 'pending'")
                .param("s", status).param("session", sessionId).param("by", by).param("b", businessId).param("id", id)
                .update();
    }

    private GuestOrder order(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new GuestOrder(rs.getObject("id", UUID.class), rs.getObject("branch_id", UUID.class),
                rs.getObject("table_id", UUID.class), rs.getString("code"), rs.getString("status"), rs.getInt("guests"),
                rs.getString("note"), rs.getObject("session_id", UUID.class), rs.getObject("created_at", OffsetDateTime.class));
    }
}
