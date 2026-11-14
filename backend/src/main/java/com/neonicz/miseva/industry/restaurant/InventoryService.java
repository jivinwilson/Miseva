package com.neonicz.miseva.industry.restaurant;

import com.neonicz.miseva.industry.restaurant.InventoryRepository.Ingredient;
import com.neonicz.miseva.industry.restaurant.InventoryRepository.Movement;
import com.neonicz.miseva.industry.restaurant.InventoryRepository.RecipeLine;
import com.neonicz.miseva.industry.restaurant.InventoryRepository.StockLevel;
import com.neonicz.miseva.platform.audit.AuditService;
import com.neonicz.miseva.shared.error.ApiException;
import com.neonicz.miseva.shared.tenancy.TenantTx;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
class InventoryService {

    record StockView(List<StockLevel> levels, List<Movement> recent) {
    }

    private final TenantTx tx;
    private final InventoryRepository inventory;
    private final BranchGuard guard;
    private final AuditService audit;

    InventoryService(TenantTx tx, InventoryRepository inventory, BranchGuard guard, AuditService audit) {
        this.tx = tx;
        this.inventory = inventory;
        this.guard = guard;
        this.audit = audit;
    }

    List<Ingredient> ingredients(RestaurantActor actor) {
        return tx.inContext(actor.businessId(), actor.userId(), () -> inventory.ingredients(actor.businessId()));
    }

    Ingredient createIngredient(RestaurantActor actor, String name, String unit, BigDecimal threshold) {
        return tx.inContext(actor.businessId(), actor.userId(), () -> {
            UUID id;
            try {
                id = inventory.insertIngredient(actor.businessId(), name.trim(), unit, threshold, actor.userId());
            } catch (DuplicateKeyException e) {
                throw new ApiException(HttpStatus.CONFLICT, "NAME_TAKEN", "An ingredient with this name already exists.");
            }
            audit.record(actor.businessId(), null, actor.userId(), "restaurant.ingredient.created", "ingredient", id);
            return new Ingredient(id, name.trim(), unit, threshold, true);
        });
    }

    void updateIngredient(RestaurantActor actor, UUID id, String name, BigDecimal threshold, boolean active) {
        tx.inContext(actor.businessId(), actor.userId(), () -> {
            boolean updated;
            try {
                updated = inventory.updateIngredient(actor.businessId(), id, name.trim(), threshold, active, actor.userId());
            } catch (DuplicateKeyException e) {
                throw new ApiException(HttpStatus.CONFLICT, "NAME_TAKEN", "An ingredient with this name already exists.");
            }
            if (!updated) {
                throw new ApiException(HttpStatus.NOT_FOUND, "INGREDIENT_NOT_FOUND", "Ingredient not found.");
            }
            audit.record(actor.businessId(), null, actor.userId(), "restaurant.ingredient.updated", "ingredient", id);
        });
    }

    List<RecipeLine> recipe(RestaurantActor actor, UUID menuItemId) {
        return tx.inContext(actor.businessId(), actor.userId(), () -> {
            requireItem(actor, menuItemId);
            return inventory.recipe(actor.businessId(), menuItemId);
        });
    }

    List<RecipeLine> saveRecipe(RestaurantActor actor, UUID menuItemId, List<RecipeLine> lines) {
        return tx.inContext(actor.businessId(), actor.userId(), () -> {
            requireItem(actor, menuItemId);
            if (lines.stream().map(RecipeLine::ingredientId).distinct().count() != lines.size()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "DUPLICATE_INGREDIENT", "Each ingredient can appear once in a recipe.");
            }
            for (RecipeLine l : lines) {
                if (!inventory.ingredientExists(actor.businessId(), l.ingredientId())) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "INGREDIENT_NOT_FOUND", "One of the ingredients does not exist.");
                }
            }
            inventory.replaceRecipe(actor.businessId(), menuItemId, lines, actor.userId());
            audit.record(actor.businessId(), null, actor.userId(), "restaurant.recipe.saved", "menu_item", menuItemId);
            return inventory.recipe(actor.businessId(), menuItemId);
        });
    }

    StockView stock(RestaurantActor actor, UUID branchId) {
        return tx.inContext(actor.businessId(), actor.userId(), () -> {
            guard.require(actor, branchId);
            return new StockView(inventory.levels(actor.businessId(), branchId),
                    inventory.recentMovements(actor.businessId(), branchId, 30));
        });
    }

    StockView record(RestaurantActor actor, UUID branchId, UUID ingredientId, String reason, BigDecimal amount, String note) {
        return tx.inContext(actor.businessId(), actor.userId(), () -> {
            guard.require(actor, branchId);
            if (!inventory.ingredientExists(actor.businessId(), ingredientId)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INGREDIENT_NOT_FOUND", "Ingredient not found.");
            }
            BigDecimal signed = switch (reason) {
                case "purchase" -> amount.abs();
                case "waste" -> amount.abs().negate();
                default -> amount;
            };
            if (signed.signum() == 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ZERO_QUANTITY", "Enter a quantity other than zero.");
            }
            inventory.insertMovement(actor.businessId(), branchId, ingredientId, signed, reason, blankToNull(note), actor.userId());
            audit.record(actor.businessId(), branchId, actor.userId(), "restaurant.stock." + reason, "ingredient", ingredientId);
            return new StockView(inventory.levels(actor.businessId(), branchId),
                    inventory.recentMovements(actor.businessId(), branchId, 30));
        });
    }

    private void requireItem(RestaurantActor actor, UUID menuItemId) {
        if (inventory.findItem(actor.businessId(), menuItemId).isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ITEM_NOT_FOUND", "Menu item not found.");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
