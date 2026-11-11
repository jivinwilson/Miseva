package com.neonicz.miseva.industry.restaurant;

import com.neonicz.miseva.industry.restaurant.MenuRepository.Category;
import com.neonicz.miseva.industry.restaurant.MenuRepository.Item;
import com.neonicz.miseva.platform.audit.AuditService;
import com.neonicz.miseva.shared.error.ApiException;
import com.neonicz.miseva.shared.tenancy.TenantTx;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
class MenuService {

    record Menu(List<Category> categories, List<Item> items) {
    }

    record ItemData(UUID categoryId, String name, String description, long priceMinor, int taxRateBp, boolean available,
                    int sortOrder) {
    }

    private final TenantTx tx;
    private final MenuRepository menu;
    private final AuditService audit;

    MenuService(TenantTx tx, MenuRepository menu, AuditService audit) {
        this.tx = tx;
        this.menu = menu;
        this.audit = audit;
    }

    Menu get(RestaurantActor actor) {
        return tx.inContext(actor.businessId(), actor.userId(),
                () -> new Menu(menu.categories(actor.businessId()), menu.items(actor.businessId())));
    }

    Category createCategory(RestaurantActor actor, String name, int sortOrder) {
        return tx.inContext(actor.businessId(), actor.userId(), () -> {
            UUID id = unique(() -> menu.insertCategory(actor.businessId(), name.trim(), sortOrder, actor.userId()));
            audit.record(actor.businessId(), null, actor.userId(), "restaurant.menu_category.created", "menu_category", id);
            return new Category(id, name.trim(), sortOrder, true);
        });
    }

    void updateCategory(RestaurantActor actor, UUID id, String name, int sortOrder, boolean active) {
        tx.inContext(actor.businessId(), actor.userId(), () -> {
            if (!unique(() -> menu.updateCategory(actor.businessId(), id, name.trim(), sortOrder, active, actor.userId()))) {
                throw new ApiException(HttpStatus.NOT_FOUND, "CATEGORY_NOT_FOUND", "Category not found.");
            }
            audit.record(actor.businessId(), null, actor.userId(), "restaurant.menu_category.updated", "menu_category", id);
        });
    }

    Item createItem(RestaurantActor actor, ItemData d) {
        return tx.inContext(actor.businessId(), actor.userId(), () -> {
            requireCategory(actor, d.categoryId());
            UUID id = unique(() -> menu.insertItem(actor.businessId(), d.categoryId(), d.name().trim(), blankToNull(d.description()),
                    d.priceMinor(), d.taxRateBp(), d.sortOrder(), actor.userId()));
            audit.record(actor.businessId(), null, actor.userId(), "restaurant.menu_item.created", "menu_item", id);
            return menu.item(actor.businessId(), id).orElseThrow();
        });
    }

    Item updateItem(RestaurantActor actor, UUID id, ItemData d) {
        return tx.inContext(actor.businessId(), actor.userId(), () -> {
            requireCategory(actor, d.categoryId());
            boolean updated = unique(() -> menu.updateItem(actor.businessId(), id, d.categoryId(), d.name().trim(),
                    blankToNull(d.description()), d.priceMinor(), d.taxRateBp(), d.available(), d.sortOrder(), actor.userId()));
            if (!updated) {
                throw new ApiException(HttpStatus.NOT_FOUND, "ITEM_NOT_FOUND", "Menu item not found.");
            }
            audit.record(actor.businessId(), null, actor.userId(), "restaurant.menu_item.updated", "menu_item", id);
            return menu.item(actor.businessId(), id).orElseThrow();
        });
    }

    private void requireCategory(RestaurantActor actor, UUID categoryId) {
        if (!menu.categoryExists(actor.businessId(), categoryId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CATEGORY_NOT_FOUND", "Category not found.");
        }
    }

    private static <T> T unique(java.util.function.Supplier<T> work) {
        try {
            return work.get();
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "NAME_TAKEN", "That name is already used.");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
