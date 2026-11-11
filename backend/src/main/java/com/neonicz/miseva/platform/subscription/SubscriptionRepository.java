package com.neonicz.miseva.platform.subscription;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SubscriptionRepository {

    public record Entitlement(boolean included, Integer limit) {

        public boolean allows(long used) {
            return included && (limit == null || used < limit);
        }
    }

    private static final int TRIAL_DAYS = 14;

    private final JdbcClient jdbc;

    public SubscriptionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void startTrial(UUID businessId, String planCode, UUID createdBy) {
        int rows = jdbc.sql("INSERT INTO subscriptions (business_id, plan_id, status, current_period_start, current_period_end,"
                        + " trial_ends_at, created_by)"
                        + " SELECT :business, id, 'trialing', now(), now() + make_interval(days => :days),"
                        + " now() + make_interval(days => :days), :by FROM plans WHERE code = :plan AND is_active")
                .param("business", businessId).param("days", TRIAL_DAYS).param("plan", planCode).param("by", createdBy)
                .update();
        if (rows != 1) {
            throw new IllegalStateException("Plan not found: " + planCode);
        }
    }

    public Entitlement entitlement(UUID businessId, String featureKey) {
        List<Integer> limits = jdbc.sql("SELECT pe.limit_value FROM subscriptions s"
                        + " JOIN plan_entitlements pe ON pe.plan_id = s.plan_id"
                        + " WHERE s.business_id = :business AND s.status IN ('trialing', 'active', 'past_due')"
                        + " AND pe.feature_key = :key")
                .param("business", businessId).param("key", featureKey)
                .query((rs, n) -> rs.getObject("limit_value", Integer.class)).list();
        return limits.isEmpty() ? new Entitlement(false, 0) : new Entitlement(true, limits.get(0));
    }
}
