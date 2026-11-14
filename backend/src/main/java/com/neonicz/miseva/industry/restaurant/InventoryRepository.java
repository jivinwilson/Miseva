package com.neonicz.miseva.industry.restaurant;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class InventoryRepository {

    record Ingredient(UUID id, String name, String unit, BigDecimal lowStockThreshold, boolean active) {
    }

    record StockLevel(UUID ingredientId, String name, String unit, BigDecimal onHand, BigDecimal lowStockThreshold,
                      boolean low) {
    }

    record Movement(UUID id, String ingredientName, String unit, BigDecimal quantity, String reason, String note,
                    OffsetDateTime createdAt) {
    }

    record RecipeLine(UUID ingredientId, String name, String unit, BigDecimal quantity) {
    }

    private final JdbcClient jdbc;

    InventoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<Ingredient> ingredients(UUID businessId) {
        return jdbc.sql("SELECT id, name, unit, low_stock_threshold, is_active FROM ingredients WHERE business_id = :b ORDER BY name")
                .param("b", businessId)
                .query((rs, n) -> new Ingredient(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("unit"),
                        rs.getBigDecimal("low_stock_threshold"), rs.getBoolean("is_active")))
                .list();
    }

    boolean ingredientExists(UUID businessId, UUID id) {
        return jdbc.sql("SELECT count(*) FROM ingredients WHERE business_id = :b AND id = :id AND is_active")
                .param("b", businessId).param("id", id).query(Integer.class).single() > 0;
    }

    UUID insertIngredient(UUID businessId, String name, String unit, BigDecimal threshold, UUID by) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO ingredients (id, business_id, name, unit, low_stock_threshold, created_by, updated_by)"
                        + " VALUES (:id, :b, :n, :u, :t, :by, :by)")
                .param("id", id).param("b", businessId).param("n", name).param("u", unit).param("t", threshold)
                .param("by", by).update();
        return id;
    }

    boolean updateIngredient(UUID businessId, UUID id, String name, BigDecimal threshold, boolean active, UUID by) {
        return jdbc.sql("UPDATE ingredients SET name = :n, low_stock_threshold = :t, is_active = :a, updated_by = :by"
                        + " WHERE business_id = :b AND id = :id")
                .param("n", name).param("t", threshold).param("a", active).param("by", by).param("b", businessId)
                .param("id", id).update() == 1;
    }

    List<RecipeLine> recipe(UUID businessId, UUID menuItemId) {
        return jdbc.sql("SELECT r.ingredient_id, i.name, i.unit, r.quantity FROM recipe_lines r"
                        + " JOIN ingredients i ON i.id = r.ingredient_id WHERE r.business_id = :b AND r.menu_item_id = :m ORDER BY i.name")
                .param("b", businessId).param("m", menuItemId)
                .query((rs, n) -> new RecipeLine(rs.getObject("ingredient_id", UUID.class), rs.getString("name"),
                        rs.getString("unit"), rs.getBigDecimal("quantity")))
                .list();
    }

    void replaceRecipe(UUID businessId, UUID menuItemId, List<RecipeLine> lines, UUID by) {
        jdbc.sql("DELETE FROM recipe_lines WHERE business_id = :b AND menu_item_id = :m")
                .param("b", businessId).param("m", menuItemId).update();
        for (RecipeLine l : lines) {
            jdbc.sql("INSERT INTO recipe_lines (business_id, menu_item_id, ingredient_id, quantity, created_by)"
                            + " VALUES (:b, :m, :i, :q, :by)")
                    .param("b", businessId).param("m", menuItemId).param("i", l.ingredientId()).param("q", l.quantity())
                    .param("by", by).update();
        }
    }

    List<StockLevel> levels(UUID businessId, UUID branchId) {
        return jdbc.sql("SELECT i.id, i.name, i.unit, i.low_stock_threshold,"
                        + " coalesce((SELECT sum(m.quantity) FROM stock_movements m"
                        + "   WHERE m.business_id = i.business_id AND m.branch_id = :br AND m.ingredient_id = i.id), 0) AS on_hand"
                        + " FROM ingredients i WHERE i.business_id = :b AND i.is_active ORDER BY i.name")
                .param("b", businessId).param("br", branchId)
                .query((rs, n) -> {
                    BigDecimal onHand = rs.getBigDecimal("on_hand");
                    BigDecimal threshold = rs.getBigDecimal("low_stock_threshold");
                    return new StockLevel(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("unit"),
                            onHand, threshold, onHand.compareTo(threshold) <= 0);
                })
                .list();
    }

    void insertMovement(UUID businessId, UUID branchId, UUID ingredientId, BigDecimal quantity, String reason,
                        String note, UUID by) {
        jdbc.sql("INSERT INTO stock_movements (business_id, branch_id, ingredient_id, quantity, reason, note, created_by)"
                        + " VALUES (:b, :br, :i, :q, :r, :n, :by)")
                .param("b", businessId).param("br", branchId).param("i", ingredientId).param("q", quantity)
                .param("r", reason).param("n", note).param("by", by).update();
    }

    int consumeForSession(UUID businessId, UUID branchId, UUID sessionId, UUID by) {
        return jdbc.sql("INSERT INTO stock_movements (business_id, branch_id, ingredient_id, quantity, reason, reference_id, created_by)"
                        + " SELECT :b, :br, r.ingredient_id, -sum(l.quantity * r.quantity), 'consumption', :s, :by"
                        + " FROM order_lines l JOIN recipe_lines r ON r.menu_item_id = l.menu_item_id AND r.business_id = l.business_id"
                        + " WHERE l.business_id = :b AND l.session_id = :s AND l.status <> 'cancelled'"
                        + " GROUP BY r.ingredient_id")
                .param("b", businessId).param("br", branchId).param("s", sessionId).param("by", by).update();
    }

    List<Movement> recentMovements(UUID businessId, UUID branchId, int limit) {
        return jdbc.sql("SELECT m.id, i.name, i.unit, m.quantity, m.reason, m.note, m.created_at FROM stock_movements m"
                        + " JOIN ingredients i ON i.id = m.ingredient_id"
                        + " WHERE m.business_id = :b AND m.branch_id = :br ORDER BY m.created_at DESC LIMIT :limit")
                .param("b", businessId).param("br", branchId).param("limit", limit)
                .query((rs, n) -> new Movement(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("unit"),
                        rs.getBigDecimal("quantity"), rs.getString("reason"), rs.getString("note"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }

    Optional<UUID> findItem(UUID businessId, UUID menuItemId) {
        return jdbc.sql("SELECT id FROM menu_items WHERE business_id = :b AND id = :m")
                .param("b", businessId).param("m", menuItemId).query(UUID.class).optional();
    }
}
