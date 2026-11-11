package com.neonicz.miseva.industry.restaurant;

import com.neonicz.miseva.industry.restaurant.OrderRepository.Line;
import com.neonicz.miseva.industry.restaurant.OrderService.NewLine;
import com.neonicz.miseva.industry.restaurant.OrderService.SessionView;
import com.neonicz.miseva.platform.security.MisevaPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/restaurant")
class OrderController {

    record OpenBody(@Min(1) @Max(100) int guests) {
    }

    record LineBody(@NotNull UUID menuItemId, @Min(1) @Max(999) int quantity, @Size(max = 200) String note) {
    }

    record LinesBody(@NotEmpty @Size(max = 50) List<@Valid LineBody> lines) {
    }

    record StatusBody(@NotNull @Pattern(regexp = "preparing|ready|served|cancelled") String status) {
    }

    private final OrderService orders;

    OrderController(OrderService orders) {
        this.orders = orders;
    }

    @PostMapping("/tables/{tableId}/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('restaurant.order.take')")
    SessionView open(@AuthenticationPrincipal MisevaPrincipal p, @PathVariable UUID tableId, @Valid @RequestBody OpenBody b) {
        return orders.open(RestaurantActor.of(p), tableId, b.guests());
    }

    @GetMapping("/sessions/{id}")
    @PreAuthorize("hasAuthority('restaurant.table.read')")
    SessionView get(@AuthenticationPrincipal MisevaPrincipal p, @PathVariable UUID id) {
        return orders.get(RestaurantActor.of(p), id);
    }

    @PostMapping("/sessions/{id}/lines")
    @PreAuthorize("hasAuthority('restaurant.order.take')")
    SessionView addLines(@AuthenticationPrincipal MisevaPrincipal p, @PathVariable UUID id, @Valid @RequestBody LinesBody b) {
        return orders.addLines(RestaurantActor.of(p), id,
                b.lines().stream().map(l -> new NewLine(l.menuItemId(), l.quantity(), l.note())).toList());
    }

    @PostMapping("/lines/{id}/status")
    @PreAuthorize("hasAnyAuthority('restaurant.kitchen.update', 'restaurant.order.take')")
    Line changeLine(@AuthenticationPrincipal MisevaPrincipal p, @PathVariable UUID id, @Valid @RequestBody StatusBody b) {
        return orders.changeLine(RestaurantActor.of(p), id, b.status());
    }

    @GetMapping("/branches/{branchId}/kitchen")
    @PreAuthorize("hasAnyAuthority('restaurant.kitchen.update', 'restaurant.order.take')")
    List<Line> kitchen(@AuthenticationPrincipal MisevaPrincipal p, @PathVariable UUID branchId) {
        return orders.kitchen(RestaurantActor.of(p), branchId);
    }
}
