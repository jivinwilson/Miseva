package com.neonicz.miseva.industry.restaurant;

import com.neonicz.miseva.industry.restaurant.MenuRepository.Category;
import com.neonicz.miseva.industry.restaurant.MenuRepository.Item;
import com.neonicz.miseva.industry.restaurant.MenuService.ItemData;
import com.neonicz.miseva.industry.restaurant.MenuService.Menu;
import com.neonicz.miseva.platform.security.MisevaPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
@RequestMapping("/api/v1/restaurant/menu")
class MenuController {

    record CategoryBody(@NotBlank @Size(max = 80) String name, @Min(0) @Max(9999) int sortOrder, Boolean active) {
    }

    record ItemBody(@NotNull UUID categoryId, @NotBlank @Size(max = 120) String name, @Size(max = 500) String description,
                    @Min(0) @Max(100_000_000) long priceMinor, @Min(0) @Max(10_000) int taxRateBp, Boolean available,
                    @Min(0) @Max(9999) int sortOrder) {

        ItemData data() {
            return new ItemData(categoryId, name, description, priceMinor, taxRateBp, available == null || available, sortOrder);
        }
    }

    private final MenuService menu;

    MenuController(MenuService menu) {
        this.menu = menu;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('restaurant.menu.read')")
    Menu get(@AuthenticationPrincipal MisevaPrincipal p) {
        return menu.get(RestaurantActor.of(p));
    }

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('restaurant.menu.manage')")
    Category createCategory(@AuthenticationPrincipal MisevaPrincipal p, @Valid @RequestBody CategoryBody b) {
        return menu.createCategory(RestaurantActor.of(p), b.name(), b.sortOrder());
    }

    @PutMapping("/categories/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('restaurant.menu.manage')")
    void updateCategory(@AuthenticationPrincipal MisevaPrincipal p, @PathVariable UUID id, @Valid @RequestBody CategoryBody b) {
        menu.updateCategory(RestaurantActor.of(p), id, b.name(), b.sortOrder(), b.active() == null || b.active());
    }

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('restaurant.menu.manage')")
    Item createItem(@AuthenticationPrincipal MisevaPrincipal p, @Valid @RequestBody ItemBody b) {
        return menu.createItem(RestaurantActor.of(p), b.data());
    }

    @PutMapping("/items/{id}")
    @PreAuthorize("hasAuthority('restaurant.menu.manage')")
    Item updateItem(@AuthenticationPrincipal MisevaPrincipal p, @PathVariable UUID id, @Valid @RequestBody ItemBody b) {
        return menu.updateItem(RestaurantActor.of(p), id, b.data());
    }
}
