package com.neonicz.miseva.industry.restaurant;

import com.neonicz.miseva.industry.restaurant.BillRepository.Bill;
import com.neonicz.miseva.industry.restaurant.BillRepository.BillLine;
import com.neonicz.miseva.industry.restaurant.BillRepository.Payment;
import com.neonicz.miseva.industry.restaurant.MenuRepository.Item;
import com.neonicz.miseva.industry.restaurant.OrderRepository.Line;
import com.neonicz.miseva.industry.restaurant.OrderRepository.Session;
import com.neonicz.miseva.industry.restaurant.TableRepository.TableRow;
import com.neonicz.miseva.platform.audit.AuditService;
import com.neonicz.miseva.shared.error.ApiException;
import com.neonicz.miseva.shared.tenancy.TenantTx;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
class OrderService {

    record NewLine(UUID menuItemId, int quantity, String note) {
    }

    record BillView(Bill bill, List<BillLine> lines, List<Payment> payments, long paidMinor, long dueMinor) {
    }

    record SessionView(Session session, List<Line> lines, long subtotalMinor, long taxMinor, long totalMinor,
                       String currency, BillView bill) {
    }

    private static final Map<String, String> NEXT = Map.of("new", "preparing", "preparing", "ready", "ready", "served");

    private final TenantTx tx;
    private final OrderRepository orders;
    private final MenuRepository menu;
    private final BillRepository bills;
    private final TableService tables;
    private final BranchGuard guard;
    private final AuditService audit;

    OrderService(TenantTx tx, OrderRepository orders, MenuRepository menu, BillRepository bills, TableService tables,
                 BranchGuard guard, AuditService audit) {
        this.tx = tx;
        this.orders = orders;
        this.menu = menu;
        this.bills = bills;
        this.tables = tables;
        this.guard = guard;
        this.audit = audit;
    }

    SessionView open(RestaurantActor actor, UUID tableId, int guests) {
        return tx.inContext(actor.businessId(), actor.userId(), () -> {
            TableRow table = tables.visible(actor, tableId);
            guard.requireActive(actor, table.branchId());
            if (!table.active()) {
                throw new ApiException(HttpStatus.CONFLICT, "TABLE_INACTIVE", "This table is not in use.");
            }
            UUID id;
            try {
                id = orders.openSession(actor.businessId(), table.branchId(), tableId, guests, actor.userId());
            } catch (DuplicateKeyException e) {
                throw new ApiException(HttpStatus.CONFLICT, "TABLE_OCCUPIED", "This table already has an open order.");
            }
            audit.record(actor.businessId(), table.branchId(), actor.userId(), "restaurant.session.opened", "dining_session", id);
            return view(actor, id);
        });
    }

    SessionView get(RestaurantActor actor, UUID sessionId) {
        return tx.inContext(actor.businessId(), actor.userId(), () -> {
            visible(actor, sessionId);
            return view(actor, sessionId);
        });
    }

    SessionView addLines(RestaurantActor actor, UUID sessionId, List<NewLine> lines) {
        return tx.inContext(actor.businessId(), actor.userId(), () -> {
            visible(actor, sessionId);
            Session session = orders.lockSession(actor.businessId(), sessionId);
            if (!"open".equals(session.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "SESSION_NOT_OPEN", "This order is already billed or closed.");
            }
            List<UUID> ids = lines.stream().map(NewLine::menuItemId).distinct().toList();
            Map<UUID, Item> items = menu.orderable(actor.businessId(), ids).stream()
                    .collect(Collectors.toMap(Item::id, Function.identity()));
            if (items.size() != ids.size()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ITEM_UNAVAILABLE", "One of the items is not available.");
            }
            for (NewLine l : lines) {
                Item item = items.get(l.menuItemId());
                orders.insertLine(actor.businessId(), session.branchId(), sessionId, item.id(), item.name(), item.priceMinor(),
                        item.taxRateBp(), l.quantity(), blankToNull(l.note()), actor.userId(), null);
            }
            audit.record(actor.businessId(), session.branchId(), actor.userId(), "restaurant.order.lines_added", "dining_session", sessionId);
            return view(actor, sessionId);
        });
    }

    Line changeLine(RestaurantActor actor, UUID lineId, String target) {
        return tx.inContext(actor.businessId(), actor.userId(), () -> {
            Line line = orders.line(actor.businessId(), lineId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "LINE_NOT_FOUND", "Order line not found."));
            try {
                guard.require(actor, line.branchId());
            } catch (ApiException e) {
                throw new ApiException(HttpStatus.NOT_FOUND, "LINE_NOT_FOUND", "Order line not found.");
            }
            boolean allowed;
            if ("cancelled".equals(target)) {
                Session session = orders.session(actor.businessId(), line.sessionId()).orElseThrow();
                if (!actor.can("restaurant.order.take")) {
                    throw new ApiException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "You do not have permission to do this.");
                }
                allowed = "new".equals(line.status()) && "open".equals(session.status());
            } else {
                allowed = target.equals(NEXT.get(line.status()));
            }
            if (!allowed || !orders.transitionLine(actor.businessId(), lineId, line.status(), target)) {
                throw new ApiException(HttpStatus.CONFLICT, "INVALID_LINE_STATUS",
                        "This item cannot move from " + line.status() + " to " + target + ".");
            }
            audit.record(actor.businessId(), line.branchId(), actor.userId(), "restaurant.order.line_" + target, "order_line", lineId);
            return orders.line(actor.businessId(), lineId).orElseThrow();
        });
    }

    List<Line> kitchen(RestaurantActor actor, UUID branchId) {
        return tx.inContext(actor.businessId(), actor.userId(), () -> {
            guard.require(actor, branchId);
            return orders.kitchen(actor.businessId(), branchId);
        });
    }

    Session visible(RestaurantActor actor, UUID sessionId) {
        Session session = orders.session(actor.businessId(), sessionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "Order not found."));
        try {
            guard.require(actor, session.branchId());
        } catch (ApiException e) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "Order not found.");
        }
        return session;
    }

    SessionView view(RestaurantActor actor, UUID sessionId) {
        Session session = orders.session(actor.businessId(), sessionId).orElseThrow();
        List<Line> lines = orders.lines(actor.businessId(), sessionId);
        long subtotal = 0;
        long tax = 0;
        for (Line l : lines) {
            if (!"cancelled".equals(l.status())) {
                long lineSubtotal = l.unitPriceMinor() * l.quantity();
                subtotal += lineSubtotal;
                tax += Money.tax(lineSubtotal, l.taxRateBp());
            }
        }
        BillView billView = bills.bySession(actor.businessId(), sessionId).map(b -> {
            List<Payment> payments = bills.payments(actor.businessId(), b.id());
            long paid = payments.stream().mapToLong(Payment::amountMinor).sum();
            return new BillView(b, bills.lines(actor.businessId(), b.id()), payments, paid, b.totalMinor() - paid);
        }).orElse(null);
        return new SessionView(session, lines, subtotal, tax, subtotal + tax, bills.currency(actor.businessId()), billView);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
