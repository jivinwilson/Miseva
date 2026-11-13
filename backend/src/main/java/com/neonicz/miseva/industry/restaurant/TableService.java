package com.neonicz.miseva.industry.restaurant;

import com.neonicz.miseva.industry.restaurant.TableRepository.TableRow;
import com.neonicz.miseva.industry.restaurant.TableRepository.TableView;
import com.neonicz.miseva.platform.audit.AuditService;
import com.neonicz.miseva.shared.error.ApiException;
import com.neonicz.miseva.shared.tenancy.TenantTx;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
class TableService {

    private final TenantTx tx;
    private final TableRepository tables;
    private final BranchGuard guard;
    private final AuditService audit;

    TableService(TenantTx tx, TableRepository tables, BranchGuard guard, AuditService audit) {
        this.tx = tx;
        this.tables = tables;
        this.guard = guard;
        this.audit = audit;
    }

    List<TableView> list(RestaurantActor actor, UUID branchId) {
        return tx.inContext(actor.businessId(), actor.userId(), () -> {
            guard.require(actor, branchId);
            return tables.listWithSessions(actor.businessId(), branchId);
        });
    }

    TableRow create(RestaurantActor actor, UUID branchId, String code, int seats, String area) {
        return tx.inContext(actor.businessId(), actor.userId(), () -> {
            guard.require(actor, branchId);
            UUID id;
            try {
                id = tables.insert(actor.businessId(), branchId, code.trim().toUpperCase(Locale.ROOT), seats, blankToNull(area), actor.userId());
            } catch (DuplicateKeyException e) {
                throw new ApiException(HttpStatus.CONFLICT, "TABLE_CODE_TAKEN", "A table with this code already exists in this branch.");
            }
            audit.record(actor.businessId(), branchId, actor.userId(), "restaurant.table.created", "dining_table", id);
            return tables.find(actor.businessId(), id).orElseThrow();
        });
    }

    TableRow update(RestaurantActor actor, UUID id, int seats, String area, boolean active) {
        return tx.inContext(actor.businessId(), actor.userId(), () -> {
            TableRow table = visible(actor, id);
            tables.update(actor.businessId(), id, seats, blankToNull(area), active, actor.userId());
            audit.record(actor.businessId(), table.branchId(), actor.userId(), "restaurant.table.updated", "dining_table", id);
            return tables.find(actor.businessId(), id).orElseThrow();
        });
    }

    void regenerateQr(RestaurantActor actor, UUID id) {
        tx.inContext(actor.businessId(), actor.userId(), () -> {
            TableRow table = visible(actor, id);
            tables.regenerateQr(actor.businessId(), id, actor.userId());
            audit.record(actor.businessId(), table.branchId(), actor.userId(), "restaurant.table.qr_regenerated", "dining_table", id);
        });
    }

    TableRow visible(RestaurantActor actor, UUID id) {
        TableRow table = tables.find(actor.businessId(), id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TABLE_NOT_FOUND", "Table not found."));
        try {
            guard.require(actor, table.branchId());
        } catch (ApiException e) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TABLE_NOT_FOUND", "Table not found.");
        }
        return table;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
