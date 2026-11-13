package com.neonicz.miseva.industry.restaurant;

import com.neonicz.miseva.industry.restaurant.GuestOrderService.GuestOrderStatus;
import com.neonicz.miseva.industry.restaurant.GuestOrderService.NewGuestLine;
import com.neonicz.miseva.industry.restaurant.GuestOrderService.PendingOrder;
import com.neonicz.miseva.industry.restaurant.GuestOrderService.PublicMenu;
import com.neonicz.miseva.platform.security.MisevaPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class GuestOrderController {

    record GuestLineBody(@NotNull UUID menuItemId, @Min(1) @Max(20) int quantity, @Size(max = 200) String note) {
    }

    record GuestOrderBody(@Min(1) @Max(50) int guests, @Size(max = 300) String note,
                          @NotEmpty @Size(max = 20) List<@Valid GuestLineBody> lines) {
    }

    private final GuestOrderService service;

    GuestOrderController(GuestOrderService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/public/tables/{token}")
    PublicMenu menu(@PathVariable String token) {
        return service.publicMenu(token);
    }

    @PostMapping("/api/v1/public/tables/{token}/orders")
    @ResponseStatus(HttpStatus.CREATED)
    GuestOrderStatus submit(@PathVariable String token, @Valid @RequestBody GuestOrderBody b) {
        return service.submit(token, b.guests(), b.note(),
                b.lines().stream().map(l -> new NewGuestLine(l.menuItemId(), l.quantity(), l.note())).toList());
    }

    @GetMapping("/api/v1/public/tables/{token}/orders/{orderId}")
    GuestOrderStatus status(@PathVariable String token, @PathVariable UUID orderId) {
        return service.status(token, orderId);
    }

    @GetMapping("/api/v1/restaurant/branches/{branchId}/guest-orders")
    @PreAuthorize("hasAuthority('restaurant.order.take')")
    List<PendingOrder> pending(@AuthenticationPrincipal MisevaPrincipal p, @PathVariable UUID branchId) {
        return service.pending(RestaurantActor.of(p), branchId);
    }

    @PostMapping("/api/v1/restaurant/guest-orders/{id}/accept")
    @PreAuthorize("hasAuthority('restaurant.order.take')")
    Map<String, UUID> accept(@AuthenticationPrincipal MisevaPrincipal p, @PathVariable UUID id) {
        return Map.of("sessionId", service.accept(RestaurantActor.of(p), id));
    }

    @PostMapping("/api/v1/restaurant/guest-orders/{id}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('restaurant.order.take')")
    void reject(@AuthenticationPrincipal MisevaPrincipal p, @PathVariable UUID id) {
        service.reject(RestaurantActor.of(p), id);
    }
}
