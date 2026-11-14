package com.neonicz.miseva.industry.restaurant;

import com.neonicz.miseva.industry.restaurant.FloorRepository.Alert;
import com.neonicz.miseva.industry.restaurant.FloorRepository.RequestRef;
import com.neonicz.miseva.industry.restaurant.GuestOrderRepository.TableRef;
import com.neonicz.miseva.industry.restaurant.TableRepository.TableRow;
import com.neonicz.miseva.industry.restaurant.TableRepository.Waiter;
import com.neonicz.miseva.platform.audit.AuditService;
import com.neonicz.miseva.shared.error.ApiException;
import com.neonicz.miseva.shared.tenancy.TenantTx;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
class FloorService {

    record AlertView(String kind, UUID id, String tableCode, String detail, OffsetDateTime at, boolean mine) {
    }

    private final TenantTx tx;
    private final FloorRepository floor;
    private final TableRepository tables;
    private final TableService tableService;
    private final GuestOrderRepository guestOrders;
    private final BranchGuard guard;
    private final AuditService audit;

    FloorService(TenantTx tx, FloorRepository floor, TableRepository tables, TableService tableService,
                 GuestOrderRepository guestOrders, BranchGuard guard, AuditService audit) {
        this.tx = tx;
        this.floor = floor;
        this.tables = tables;
        this.tableService = tableService;
        this.guestOrders = guestOrders;
        this.guard = guard;
        this.audit = audit;
    }

    List<Waiter> waiters(RestaurantActor actor, UUID branchId) {
        return tx.inContext(actor.businessId(), actor.userId(), () -> {
            guard.require(actor, branchId);
            return tables.waiters(actor.businessId(), branchId);
        });
    }

    void assignWaiter(RestaurantActor actor, UUID tableId, UUID membershipId) {
        tx.inContext(actor.businessId(), actor.userId(), () -> {
            TableRow table = tableService.visible(actor, tableId);
            if (membershipId != null && tables.waiters(actor.businessId(), table.branchId()).stream()
                    .noneMatch(w -> w.membershipId().equals(membershipId))) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "NOT_A_WAITER",
                        "That person cannot take orders at this branch.");
            }
            tables.assignWaiter(actor.businessId(), tableId, membershipId, actor.userId());
            audit.record(actor.businessId(), table.branchId(), actor.userId(), "restaurant.table.waiter_assigned", "dining_table", tableId);
        });
    }

    List<AlertView> alerts(RestaurantActor actor, UUID branchId) {
        boolean seesAll = actor.can("restaurant.table.manage");
        return tx.inContext(actor.businessId(), actor.userId(), () -> {
            guard.require(actor, branchId);
            return floor.alerts(actor.businessId(), branchId).stream()
                    .filter(a -> seesAll || a.waiterMembershipId() == null || a.waiterMembershipId().equals(actor.membershipId()))
                    .map(a -> view(a, actor))
                    .toList();
        });
    }

    void resolveRequest(RestaurantActor actor, UUID id) {
        tx.inContext(actor.businessId(), actor.userId(), () -> {
            RequestRef ref = floor.request(actor.businessId(), id)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "REQUEST_NOT_FOUND", "Request not found."));
            try {
                guard.require(actor, ref.branchId());
            } catch (ApiException e) {
                throw new ApiException(HttpStatus.NOT_FOUND, "REQUEST_NOT_FOUND", "Request not found.");
            }
            floor.resolveRequest(actor.businessId(), id, actor.userId());
            audit.record(actor.businessId(), ref.branchId(), actor.userId(), "restaurant.service_request.done", "service_request", id);
        });
    }

    boolean guestRequest(String token, String type) {
        if (token == null || !token.matches("[0-9a-f]{64}")) {
            throw notFound();
        }
        TableRef ref = guestOrders.byToken(token).orElseThrow(FloorService::notFound);
        return tx.inContext(ref.businessId(), null, () -> {
            boolean created = floor.openRequest(ref.businessId(), ref.branchId(), ref.tableId(), type);
            if (created) {
                audit.record(ref.businessId(), ref.branchId(), null, "restaurant.service_request." + type, "dining_table", ref.tableId());
            }
            return created;
        });
    }

    private static AlertView view(Alert a, RestaurantActor actor) {
        return new AlertView(a.kind(), a.id(), a.tableCode(), a.detail(), a.at(),
                a.waiterMembershipId() != null && a.waiterMembershipId().equals(actor.membershipId()));
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "TABLE_NOT_FOUND", "This table code is not valid.");
    }
}
