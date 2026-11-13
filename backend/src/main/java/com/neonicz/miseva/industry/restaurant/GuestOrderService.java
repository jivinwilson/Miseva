package com.neonicz.miseva.industry.restaurant;

import com.neonicz.miseva.industry.restaurant.GuestOrderRepository.GuestLine;
import com.neonicz.miseva.industry.restaurant.GuestOrderRepository.GuestOrder;
import com.neonicz.miseva.industry.restaurant.GuestOrderRepository.PublicTable;
import com.neonicz.miseva.industry.restaurant.GuestOrderRepository.TableRef;
import com.neonicz.miseva.industry.restaurant.MenuRepository.Category;
import com.neonicz.miseva.industry.restaurant.MenuRepository.Item;
import com.neonicz.miseva.platform.audit.AuditService;
import com.neonicz.miseva.shared.error.ApiException;
import com.neonicz.miseva.shared.tenancy.TenantTx;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
class GuestOrderService {

    record PublicMenu(String businessName, String branchName, String tableCode, String currency,
                      List<Category> categories, List<Item> items) {
    }

    record NewGuestLine(UUID menuItemId, int quantity, String note) {
    }

    record GuestOrderStatus(UUID id, String status) {
    }

    record PendingOrder(GuestOrder order, List<GuestLine> lines) {
    }

    static final int MAX_PENDING_PER_TABLE = 3;

    private final TenantTx tx;
    private final GuestOrderRepository guestOrders;
    private final MenuRepository menu;
    private final OrderRepository orders;
    private final BranchGuard guard;
    private final AuditService audit;

    GuestOrderService(TenantTx tx, GuestOrderRepository guestOrders, MenuRepository menu, OrderRepository orders,
                      BranchGuard guard, AuditService audit) {
        this.tx = tx;
        this.guestOrders = guestOrders;
        this.menu = menu;
        this.orders = orders;
        this.guard = guard;
        this.audit = audit;
    }

    PublicMenu publicMenu(String token) {
        TableRef ref = resolve(token);
        return tx.inContext(ref.businessId(), null, () -> {
            PublicTable table = guestOrders.publicTable(ref);
            List<Category> categories = menu.categories(ref.businessId()).stream().filter(Category::active).toList();
            Set<UUID> activeIds = categories.stream().map(Category::id).collect(Collectors.toSet());
            List<Item> items = menu.items(ref.businessId()).stream()
                    .filter(i -> i.available() && activeIds.contains(i.categoryId())).toList();
            return new PublicMenu(table.businessName(), table.branchName(), table.tableCode(), table.currency(), categories, items);
        });
    }

    GuestOrderStatus submit(String token, int guests, String note, List<NewGuestLine> lines) {
        TableRef ref = resolve(token);
        return tx.inContext(ref.businessId(), null, () -> {
            guestOrders.lockTable(ref.businessId(), ref.tableId());
            if (guestOrders.pendingForTable(ref.businessId(), ref.tableId()) >= MAX_PENDING_PER_TABLE) {
                throw new ApiException(HttpStatus.CONFLICT, "TOO_MANY_PENDING",
                        "A few orders from this table are still waiting for staff. Please wait a moment.");
            }
            Map<UUID, Item> items = orderable(ref.businessId(), lines.stream().map(NewGuestLine::menuItemId).toList());
            UUID id = guestOrders.insertOrder(ref, guests, blankToNull(note));
            for (NewGuestLine l : lines) {
                Item item = items.get(l.menuItemId());
                guestOrders.insertLine(ref.businessId(), id, item.id(), item.name(), item.priceMinor(), item.taxRateBp(),
                        l.quantity(), blankToNull(l.note()));
            }
            audit.record(ref.businessId(), ref.branchId(), null, "restaurant.guest_order.submitted", "guest_order", id);
            return new GuestOrderStatus(id, "pending");
        });
    }

    GuestOrderStatus status(String token, UUID orderId) {
        TableRef ref = resolve(token);
        return tx.inContext(ref.businessId(), null, () -> guestOrders.find(ref.businessId(), orderId)
                .filter(o -> o.tableId().equals(ref.tableId()))
                .map(o -> new GuestOrderStatus(o.id(), o.status()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "GUEST_ORDER_NOT_FOUND", "Order not found.")));
    }

    List<PendingOrder> pending(RestaurantActor actor, UUID branchId) {
        return tx.inContext(actor.businessId(), actor.userId(), () -> {
            guard.require(actor, branchId);
            return guestOrders.pending(actor.businessId(), branchId).stream()
                    .map(o -> new PendingOrder(o, guestOrders.lines(actor.businessId(), o.id()))).toList();
        });
    }

    UUID accept(RestaurantActor actor, UUID id) {
        return tx.inContext(actor.businessId(), actor.userId(), () -> {
            GuestOrder order = pendingVisible(actor, id);
            List<GuestLine> lines = guestOrders.lines(actor.businessId(), id);
            orderable(actor.businessId(), lines.stream().map(GuestLine::menuItemId).toList());

            Optional<UUID> live = orders.liveSessionOfTable(actor.businessId(), order.tableId());
            UUID sessionId;
            if (live.isPresent()) {
                sessionId = live.get();
                if (!"open".equals(orders.session(actor.businessId(), sessionId).orElseThrow().status())) {
                    throw new ApiException(HttpStatus.CONFLICT, "SESSION_NOT_OPEN",
                            "This table's bill is already issued. Settle it before accepting new orders.");
                }
            } else {
                guard.requireActive(actor, order.branchId());
                sessionId = orders.openSession(actor.businessId(), order.branchId(), order.tableId(), order.guests(), actor.userId());
            }
            for (GuestLine l : lines) {
                orders.insertLine(actor.businessId(), order.branchId(), sessionId, l.menuItemId(), l.itemName(),
                        l.unitPriceMinor(), l.taxRateBp(), l.quantity(), l.note(), actor.userId());
            }
            guestOrders.decide(actor.businessId(), id, "accepted", sessionId, actor.userId());
            audit.record(actor.businessId(), order.branchId(), actor.userId(), "restaurant.guest_order.accepted", "guest_order", id);
            return sessionId;
        });
    }

    void reject(RestaurantActor actor, UUID id) {
        tx.inContext(actor.businessId(), actor.userId(), () -> {
            GuestOrder order = pendingVisible(actor, id);
            guestOrders.decide(actor.businessId(), id, "rejected", null, actor.userId());
            audit.record(actor.businessId(), order.branchId(), actor.userId(), "restaurant.guest_order.rejected", "guest_order", id);
        });
    }

    private GuestOrder pendingVisible(RestaurantActor actor, UUID id) {
        GuestOrder order = guestOrders.lock(actor.businessId(), id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "GUEST_ORDER_NOT_FOUND", "Guest order not found."));
        try {
            guard.require(actor, order.branchId());
        } catch (ApiException e) {
            throw new ApiException(HttpStatus.NOT_FOUND, "GUEST_ORDER_NOT_FOUND", "Guest order not found.");
        }
        if (!"pending".equals(order.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "GUEST_ORDER_DECIDED", "This guest order was already handled.");
        }
        return order;
    }

    private Map<UUID, Item> orderable(UUID businessId, List<UUID> requested) {
        List<UUID> ids = requested.stream().distinct().toList();
        Map<UUID, Item> items = menu.orderable(businessId, ids).stream().collect(Collectors.toMap(Item::id, Function.identity()));
        if (items.size() != ids.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ITEM_UNAVAILABLE", "One of the items is no longer available.");
        }
        return items;
    }

    private TableRef resolve(String token) {
        if (token == null || !token.matches("[0-9a-f]{64}")) {
            throw notFound();
        }
        return guestOrders.byToken(token).orElseThrow(GuestOrderService::notFound);
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "TABLE_NOT_FOUND", "This table code is not valid.");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
