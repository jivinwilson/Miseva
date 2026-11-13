package com.neonicz.miseva.industry.restaurant;

import com.neonicz.miseva.industry.restaurant.TableRepository.TableRow;
import com.neonicz.miseva.industry.restaurant.TableRepository.TableView;
import com.neonicz.miseva.platform.security.MisevaPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/restaurant")
class TableController {

    record CreateTable(@NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9-]{0,9}", message = "Use 1-10 letters, digits or hyphens") String code,
                       @Min(1) @Max(100) int seats, @Size(max = 60) String area) {
    }

    record UpdateTable(@Min(1) @Max(100) int seats, @Size(max = 60) String area, boolean active) {
    }

    private final TableService tables;

    TableController(TableService tables) {
        this.tables = tables;
    }

    @GetMapping("/branches/{branchId}/tables")
    @PreAuthorize("hasAuthority('restaurant.table.read')")
    List<TableView> list(@AuthenticationPrincipal MisevaPrincipal p, @PathVariable UUID branchId) {
        return tables.list(RestaurantActor.of(p), branchId);
    }

    @PostMapping("/branches/{branchId}/tables")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('restaurant.table.manage')")
    TableRow create(@AuthenticationPrincipal MisevaPrincipal p, @PathVariable UUID branchId, @Valid @RequestBody CreateTable b) {
        return tables.create(RestaurantActor.of(p), branchId, b.code(), b.seats(), b.area());
    }

    @PostMapping("/tables/{id}/qr/regenerate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('restaurant.table.manage')")
    void regenerateQr(@AuthenticationPrincipal MisevaPrincipal p, @PathVariable UUID id) {
        tables.regenerateQr(RestaurantActor.of(p), id);
    }

    @PutMapping("/tables/{id}")
    @PreAuthorize("hasAuthority('restaurant.table.manage')")
    TableRow update(@AuthenticationPrincipal MisevaPrincipal p, @PathVariable UUID id, @Valid @RequestBody UpdateTable b) {
        return tables.update(RestaurantActor.of(p), id, b.seats(), b.area(), b.active());
    }
}
