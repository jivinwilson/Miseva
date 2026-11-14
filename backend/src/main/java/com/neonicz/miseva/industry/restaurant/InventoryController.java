package com.neonicz.miseva.industry.restaurant;

import com.neonicz.miseva.industry.restaurant.InventoryRepository.Ingredient;
import com.neonicz.miseva.industry.restaurant.InventoryRepository.RecipeLine;
import com.neonicz.miseva.industry.restaurant.InventoryService.StockView;
import com.neonicz.miseva.platform.security.MisevaPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/restaurant")
class InventoryController {

    record IngredientBody(@NotBlank @Size(max = 80) String name, @Pattern(regexp = "g|ml|pcs") String unit,
                          @NotNull @DecimalMin("0") @Digits(integer = 11, fraction = 3) BigDecimal lowStockThreshold,
                          Boolean active) {
    }

    record RecipeLineBody(@NotNull UUID ingredientId,
                          @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 11, fraction = 3) BigDecimal quantity) {
    }

    record RecipeBody(@NotNull @Size(max = 40) List<@Valid RecipeLineBody> lines) {
    }

    record MovementBody(@NotNull UUID ingredientId, @NotNull @Pattern(regexp = "purchase|waste|adjustment") String reason,
                        @NotNull @DecimalMin("-100000000") @DecimalMax("100000000") @Digits(integer = 11, fraction = 3) BigDecimal quantity,
                        @Size(max = 200) String note) {
    }

    private final InventoryService inventory;

    InventoryController(InventoryService inventory) {
        this.inventory = inventory;
    }

    @GetMapping("/ingredients")
    @PreAuthorize("hasAuthority('restaurant.inventory.read')")
    List<Ingredient> ingredients(@AuthenticationPrincipal MisevaPrincipal p) {
        return inventory.ingredients(RestaurantActor.of(p));
    }

    @PostMapping("/ingredients")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('restaurant.inventory.manage')")
    Ingredient create(@AuthenticationPrincipal MisevaPrincipal p, @Valid @RequestBody IngredientBody b) {
        return inventory.createIngredient(RestaurantActor.of(p), b.name(), b.unit() == null ? "g" : b.unit(), b.lowStockThreshold());
    }

    @PutMapping("/ingredients/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('restaurant.inventory.manage')")
    void update(@AuthenticationPrincipal MisevaPrincipal p, @PathVariable UUID id, @Valid @RequestBody IngredientBody b) {
        inventory.updateIngredient(RestaurantActor.of(p), id, b.name(), b.lowStockThreshold(), b.active() == null || b.active());
    }

    @GetMapping("/menu/items/{id}/recipe")
    @PreAuthorize("hasAuthority('restaurant.inventory.read')")
    List<RecipeLine> recipe(@AuthenticationPrincipal MisevaPrincipal p, @PathVariable UUID id) {
        return inventory.recipe(RestaurantActor.of(p), id);
    }

    @PutMapping("/menu/items/{id}/recipe")
    @PreAuthorize("hasAuthority('restaurant.inventory.manage')")
    List<RecipeLine> saveRecipe(@AuthenticationPrincipal MisevaPrincipal p, @PathVariable UUID id, @Valid @RequestBody RecipeBody b) {
        return inventory.saveRecipe(RestaurantActor.of(p), id,
                b.lines().stream().map(l -> new RecipeLine(l.ingredientId(), null, null, l.quantity())).toList());
    }

    @GetMapping("/branches/{branchId}/stock")
    @PreAuthorize("hasAuthority('restaurant.inventory.read')")
    StockView stock(@AuthenticationPrincipal MisevaPrincipal p, @PathVariable UUID branchId) {
        return inventory.stock(RestaurantActor.of(p), branchId);
    }

    @PostMapping("/branches/{branchId}/stock/movements")
    @PreAuthorize("hasAuthority('restaurant.inventory.manage')")
    StockView record(@AuthenticationPrincipal MisevaPrincipal p, @PathVariable UUID branchId, @Valid @RequestBody MovementBody b) {
        return inventory.record(RestaurantActor.of(p), branchId, b.ingredientId(), b.reason(), b.quantity(), b.note());
    }
}
