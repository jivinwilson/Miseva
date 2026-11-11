package com.neonicz.miseva.industry.restaurant;

import com.neonicz.miseva.platform.business.BranchScopeResolver;
import com.neonicz.miseva.shared.error.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class BranchGuard {

    private final BranchScopeResolver scopes;
    private final JdbcClient jdbc;

    BranchGuard(BranchScopeResolver scopes, JdbcClient jdbc) {
        this.scopes = scopes;
        this.jdbc = jdbc;
    }

    void require(RestaurantActor actor, UUID branchId) {
        boolean exists = jdbc.sql("SELECT count(*) FROM branches WHERE business_id = :b AND id = :id")
                .param("b", actor.businessId()).param("id", branchId).query(Integer.class).single() > 0;
        boolean allowed = scopes.restrictedBranchIds(actor.membershipId()).map(ids -> ids.contains(branchId)).orElse(true);
        if (!exists || !allowed) {
            throw new ApiException(HttpStatus.NOT_FOUND, "BRANCH_NOT_FOUND", "Branch not found.");
        }
    }

    void requireActive(RestaurantActor actor, UUID branchId) {
        require(actor, branchId);
        boolean active = jdbc.sql("SELECT status = 'active' FROM branches WHERE business_id = :b AND id = :id")
                .param("b", actor.businessId()).param("id", branchId).query(Boolean.class).single();
        if (!active) {
            throw new ApiException(HttpStatus.CONFLICT, "BRANCH_INACTIVE", "This branch is not active.");
        }
    }
}
