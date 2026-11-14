package com.neonicz.miseva.industry.restaurant;

import com.neonicz.miseva.industry.restaurant.FloorService.AlertView;
import com.neonicz.miseva.industry.restaurant.TableRepository.Waiter;
import com.neonicz.miseva.platform.security.MisevaPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class FloorController {

    record WaiterBody(UUID membershipId) {
    }

    record GuestRequestBody(@NotNull @Pattern(regexp = "call_waiter|request_bill") String type) {
    }

    private final FloorService floor;

    FloorController(FloorService floor) {
        this.floor = floor;
    }

    @GetMapping("/api/v1/restaurant/branches/{branchId}/waiters")
    @PreAuthorize("hasAuthority('restaurant.table.manage')")
    List<Waiter> waiters(@AuthenticationPrincipal MisevaPrincipal p, @PathVariable UUID branchId) {
        return floor.waiters(RestaurantActor.of(p), branchId);
    }

    @PutMapping("/api/v1/restaurant/tables/{id}/waiter")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('restaurant.table.manage')")
    void assign(@AuthenticationPrincipal MisevaPrincipal p, @PathVariable UUID id, @RequestBody WaiterBody b) {
        floor.assignWaiter(RestaurantActor.of(p), id, b.membershipId());
    }

    @GetMapping("/api/v1/restaurant/branches/{branchId}/alerts")
    @PreAuthorize("hasAuthority('restaurant.order.take')")
    List<AlertView> alerts(@AuthenticationPrincipal MisevaPrincipal p, @PathVariable UUID branchId) {
        return floor.alerts(RestaurantActor.of(p), branchId);
    }

    @PostMapping("/api/v1/restaurant/service-requests/{id}/done")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('restaurant.order.take')")
    void done(@AuthenticationPrincipal MisevaPrincipal p, @PathVariable UUID id) {
        floor.resolveRequest(RestaurantActor.of(p), id);
    }

    @PostMapping("/api/v1/public/tables/{token}/requests")
    Map<String, Object> guestRequest(@PathVariable String token, @Valid @RequestBody GuestRequestBody b) {
        return Map.of("type", b.type(), "status", "open", "created", floor.guestRequest(token, b.type()));
    }
}
