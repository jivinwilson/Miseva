package com.neonicz.miseva.platform.business;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface BranchScopeResolver {

    Optional<Set<UUID>> restrictedBranchIds(UUID membershipId);
}
