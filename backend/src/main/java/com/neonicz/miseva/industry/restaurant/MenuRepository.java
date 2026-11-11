package com.neonicz.miseva.industry.restaurant;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class MenuRepository {

    record Category(UUID id, String name, int sortOrder, boolean active) {
    }

    record Item(UUID id, UUID categoryId, String name, String description, long priceMinor, int taxRateBp,
                boolean available, int sortOrder) {
    }

    private static final String ITEM_COLUMNS = "id, category_id, name, description, price_minor, tax_rate_bp, is_available, sort_order";

    private final JdbcClient jdbc;

    MenuRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<Category> categories(UUID businessId) {
        return jdbc.sql("SELECT id, name, sort_order, is_active FROM menu_categories WHERE business_id = :b ORDER BY sort_order, name")
                .param("b", businessId)
                .query((rs, n) -> new Category(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getInt("sort_order"), rs.getBoolean("is_active")))
                .list();
    }

    List<Item> items(UUID businessId) {
        return jdbc.sql("SELECT " + ITEM_COLUMNS + " FROM menu_items WHERE business_id = :b ORDER BY sort_order, name")
                .param("b", businessId).query(this::item).list();
    }

    Optional<Item> item(UUID businessId, UUID id) {
        return jdbc.sql("SELECT " + ITEM_COLUMNS + " FROM menu_items WHERE business_id = :b AND id = :id")
                .param("b", businessId).param("id", id).query(this::item).optional();
    }

    List<Item> orderable(UUID businessId, Collection<UUID> ids) {
        return jdbc.sql("SELECT i.id, i.category_id, i.name, i.description, i.price_minor, i.tax_rate_bp, i.is_available, i.sort_order"
                        + " FROM menu_items i JOIN menu_categories c ON c.id = i.category_id"
                        + " WHERE i.business_id = :b AND i.id IN (:ids) AND i.is_available AND c.is_active")
                .param("b", businessId).param("ids", ids).query(this::item).list();
    }

    boolean categoryExists(UUID businessId, UUID id) {
        return jdbc.sql("SELECT count(*) FROM menu_categories WHERE business_id = :b AND id = :id")
                .param("b", businessId).param("id", id).query(Integer.class).single() > 0;
    }

    UUID insertCategory(UUID businessId, String name, int sortOrder, UUID by) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO menu_categories (id, business_id, name, sort_order, created_by, updated_by)"
                        + " VALUES (:id, :b, :name, :sort, :by, :by)")
                .param("id", id).param("b", businessId).param("name", name).param("sort", sortOrder).param("by", by).update();
        return id;
    }

    boolean updateCategory(UUID businessId, UUID id, String name, int sortOrder, boolean active, UUID by) {
        return jdbc.sql("UPDATE menu_categories SET name = :name, sort_order = :sort, is_active = :active, updated_by = :by"
                        + " WHERE id = :id AND business_id = :b")
                .param("name", name).param("sort", sortOrder).param("active", active).param("by", by)
                .param("id", id).param("b", businessId).update() == 1;
    }

    UUID insertItem(UUID businessId, UUID categoryId, String name, String description, long price, int taxBp,
                    int sortOrder, UUID by) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO menu_items (id, business_id, category_id, name, description, price_minor, tax_rate_bp, sort_order,"
                        + " created_by, updated_by) VALUES (:id, :b, :c, :name, :d, :p, :t, :sort, :by, :by)")
                .param("id", id).param("b", businessId).param("c", categoryId).param("name", name).param("d", description)
                .param("p", price).param("t", taxBp).param("sort", sortOrder).param("by", by).update();
        return id;
    }

    boolean updateItem(UUID businessId, UUID id, UUID categoryId, String name, String description, long price, int taxBp,
                       boolean available, int sortOrder, UUID by) {
        return jdbc.sql("UPDATE menu_items SET category_id = :c, name = :name, description = :d, price_minor = :p,"
                        + " tax_rate_bp = :t, is_available = :a, sort_order = :sort, updated_by = :by"
                        + " WHERE id = :id AND business_id = :b")
                .param("c", categoryId).param("name", name).param("d", description).param("p", price).param("t", taxBp)
                .param("a", available).param("sort", sortOrder).param("by", by).param("id", id).param("b", businessId)
                .update() == 1;
    }

    private Item item(ResultSet rs, int n) throws SQLException {
        return new Item(rs.getObject("id", UUID.class), rs.getObject("category_id", UUID.class), rs.getString("name"),
                rs.getString("description"), rs.getLong("price_minor"), rs.getInt("tax_rate_bp"),
                rs.getBoolean("is_available"), rs.getInt("sort_order"));
    }
}
