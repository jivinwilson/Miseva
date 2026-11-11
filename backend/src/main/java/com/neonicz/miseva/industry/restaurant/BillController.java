package com.neonicz.miseva.industry.restaurant;

import com.neonicz.miseva.industry.restaurant.OrderService.SessionView;
import com.neonicz.miseva.platform.security.MisevaPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/restaurant")
class BillController {

    record PaymentBody(@NotNull @Pattern(regexp = "cash|card|upi|other") String method,
                       @Min(1) @Max(1_000_000_000) long amountMinor, @Size(max = 100) String reference) {
    }

    private final BillService bills;

    BillController(BillService bills) {
        this.bills = bills;
    }

    @PostMapping("/sessions/{id}/bill")
    @PreAuthorize("hasAuthority('restaurant.bill.manage')")
    SessionView issue(@AuthenticationPrincipal MisevaPrincipal p, @PathVariable UUID id) {
        return bills.issue(RestaurantActor.of(p), id);
    }

    @PostMapping("/bills/{id}/payments")
    @PreAuthorize("hasAuthority('restaurant.bill.manage')")
    SessionView pay(@AuthenticationPrincipal MisevaPrincipal p, @PathVariable UUID id, @Valid @RequestBody PaymentBody b) {
        return bills.pay(RestaurantActor.of(p), id, b.method(), b.amountMinor(), b.reference());
    }
}
