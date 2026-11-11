package com.neonicz.miseva.industry.restaurant;

import com.neonicz.miseva.platform.security.MisevaPrincipal;
import com.neonicz.miseva.shared.error.ApiException;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;

public record RestaurantActor(UUID businessId, UUID userId, UUID membershipId, Set<String> permissions) {

    static RestaurantActor of(MisevaPrincipal p) {
        if (p.businessId() == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NO_ACTIVE_BUSINESS", "No business selected.");
        }
        return new RestaurantActor(p.businessId(), p.userId(), p.membershipId(), p.permissions());
    }

    boolean can(String permission) {
        return permissions.contains(permission);
    }
}
