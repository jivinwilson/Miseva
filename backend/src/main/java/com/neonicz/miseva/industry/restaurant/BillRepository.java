package com.neonicz.miseva.industry.restaurant;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class BillRepository {

    record Bill(UUID id, UUID branchId, UUID sessionId, String billNumber, String currency, long subtotalMinor,
                long taxMinor, long totalMinor, String status, OffsetDateTime issuedAt, OffsetDateTime paidAt) {
    }

    record BillLine(String itemName, int quantity, long unitPriceMinor, int taxRateBp, long lineSubtotal, long lineTax) {
    }

    record Payment(UUID id, String method, long amountMinor, String reference, OffsetDateTime receivedAt) {
    }

    private static final String BILL_SELECT = "SELECT id, branch_id, session_id, bill_number, currency, subtotal_minor,"
            + " tax_minor, total_minor, status, issued_at, paid_at FROM bills";

    private final JdbcClient jdbc;

    BillRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void lockBusiness(UUID businessId) {
        jdbc.sql("SELECT id FROM businesses WHERE id = :id FOR UPDATE").param("id", businessId).query(UUID.class).single();
    }

    String nextBillNumber(UUID businessId) {
        long next = jdbc.sql("SELECT count(*) FROM bills WHERE business_id = :b").param("b", businessId)
                .query(Long.class).single() + 1;
        return String.format("B-%06d", next);
    }

    String currency(UUID businessId) {
        return jdbc.sql("SELECT default_currency FROM businesses WHERE id = :b").param("b", businessId)
                .query(String.class).single();
    }

    UUID insertBill(UUID businessId, UUID branchId, UUID sessionId, String number, String currency, long subtotal,
                    long tax, UUID by) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO bills (id, business_id, branch_id, session_id, bill_number, currency, subtotal_minor, tax_minor,"
                        + " total_minor, issued_by) VALUES (:id, :b, :br, :s, :n, :c, :sub, :tax, :total, :by)")
                .param("id", id).param("b", businessId).param("br", branchId).param("s", sessionId).param("n", number)
                .param("c", currency).param("sub", subtotal).param("tax", tax).param("total", subtotal + tax).param("by", by)
                .update();
        return id;
    }

    void insertBillLine(UUID businessId, UUID billId, BillLine l) {
        jdbc.sql("INSERT INTO bill_lines (business_id, bill_id, item_name, quantity, unit_price_minor, tax_rate_bp,"
                        + " line_subtotal, line_tax) VALUES (:b, :bill, :n, :q, :p, :t, :sub, :tax)")
                .param("b", businessId).param("bill", billId).param("n", l.itemName()).param("q", l.quantity())
                .param("p", l.unitPriceMinor()).param("t", l.taxRateBp()).param("sub", l.lineSubtotal())
                .param("tax", l.lineTax()).update();
    }

    Optional<Bill> bySession(UUID businessId, UUID sessionId) {
        return jdbc.sql(BILL_SELECT + " WHERE business_id = :b AND session_id = :s")
                .param("b", businessId).param("s", sessionId).query(this::bill).optional();
    }

    Optional<Bill> lock(UUID businessId, UUID id) {
        jdbc.sql("SELECT id FROM bills WHERE business_id = :b AND id = :id FOR UPDATE")
                .param("b", businessId).param("id", id).query(UUID.class).optional();
        return jdbc.sql(BILL_SELECT + " WHERE business_id = :b AND id = :id")
                .param("b", businessId).param("id", id).query(this::bill).optional();
    }

    List<BillLine> lines(UUID businessId, UUID billId) {
        return jdbc.sql("SELECT item_name, quantity, unit_price_minor, tax_rate_bp, line_subtotal, line_tax FROM bill_lines"
                        + " WHERE business_id = :b AND bill_id = :bill ORDER BY item_name")
                .param("b", businessId).param("bill", billId)
                .query((rs, n) -> new BillLine(rs.getString("item_name"), rs.getInt("quantity"), rs.getLong("unit_price_minor"),
                        rs.getInt("tax_rate_bp"), rs.getLong("line_subtotal"), rs.getLong("line_tax")))
                .list();
    }

    List<Payment> payments(UUID businessId, UUID billId) {
        return jdbc.sql("SELECT id, method, amount_minor, reference, received_at FROM payments"
                        + " WHERE business_id = :b AND bill_id = :bill ORDER BY received_at")
                .param("b", businessId).param("bill", billId)
                .query((rs, n) -> new Payment(rs.getObject("id", UUID.class), rs.getString("method"),
                        rs.getLong("amount_minor"), rs.getString("reference"), rs.getObject("received_at", OffsetDateTime.class)))
                .list();
    }

    void insertPayment(UUID businessId, UUID billId, String method, long amount, String reference, UUID by) {
        jdbc.sql("INSERT INTO payments (business_id, bill_id, method, amount_minor, reference, received_by)"
                        + " VALUES (:b, :bill, :m, :a, :r, :by)")
                .param("b", businessId).param("bill", billId).param("m", method).param("a", amount)
                .param("r", reference).param("by", by).update();
    }

    void markPaid(UUID businessId, UUID id) {
        jdbc.sql("UPDATE bills SET status = 'paid', paid_at = now() WHERE business_id = :b AND id = :id AND status = 'issued'")
                .param("b", businessId).param("id", id).update();
    }

    private Bill bill(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Bill(rs.getObject("id", UUID.class), rs.getObject("branch_id", UUID.class),
                rs.getObject("session_id", UUID.class), rs.getString("bill_number"), rs.getString("currency"),
                rs.getLong("subtotal_minor"), rs.getLong("tax_minor"), rs.getLong("total_minor"), rs.getString("status"),
                rs.getObject("issued_at", OffsetDateTime.class), rs.getObject("paid_at", OffsetDateTime.class));
    }
}
