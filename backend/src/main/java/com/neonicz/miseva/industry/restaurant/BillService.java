package com.neonicz.miseva.industry.restaurant;

import com.neonicz.miseva.industry.restaurant.BillRepository.Bill;
import com.neonicz.miseva.industry.restaurant.BillRepository.BillLine;
import com.neonicz.miseva.industry.restaurant.OrderRepository.Line;
import com.neonicz.miseva.industry.restaurant.OrderRepository.Session;
import com.neonicz.miseva.industry.restaurant.OrderService.SessionView;
import com.neonicz.miseva.platform.audit.AuditService;
import com.neonicz.miseva.shared.error.ApiException;
import com.neonicz.miseva.shared.tenancy.TenantTx;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
class BillService {

    private final TenantTx tx;
    private final BillRepository bills;
    private final OrderRepository orders;
    private final OrderService orderService;
    private final BranchGuard guard;
    private final AuditService audit;

    BillService(TenantTx tx, BillRepository bills, OrderRepository orders, OrderService orderService, BranchGuard guard,
                AuditService audit) {
        this.tx = tx;
        this.bills = bills;
        this.orders = orders;
        this.orderService = orderService;
        this.guard = guard;
        this.audit = audit;
    }

    SessionView issue(RestaurantActor actor, UUID sessionId) {
        return tx.inContext(actor.businessId(), actor.userId(), () -> {
            orderService.visible(actor, sessionId);
            bills.lockBusiness(actor.businessId());
            Session session = orders.lockSession(actor.businessId(), sessionId);
            if (!"open".equals(session.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "SESSION_NOT_OPEN", "This order is already billed or closed.");
            }
            List<Line> lines = orders.lines(actor.businessId(), sessionId).stream()
                    .filter(l -> !"cancelled".equals(l.status())).toList();
            if (lines.isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, "NOTHING_TO_BILL", "There is nothing to bill on this order.");
            }
            List<BillLine> billLines = lines.stream().map(l -> {
                long sub = l.unitPriceMinor() * l.quantity();
                return new BillLine(l.itemName(), l.quantity(), l.unitPriceMinor(), l.taxRateBp(), sub, Money.tax(sub, l.taxRateBp()));
            }).toList();
            long subtotal = billLines.stream().mapToLong(BillLine::lineSubtotal).sum();
            long tax = billLines.stream().mapToLong(BillLine::lineTax).sum();
            UUID billId = bills.insertBill(actor.businessId(), session.branchId(), sessionId,
                    bills.nextBillNumber(actor.businessId()), bills.currency(actor.businessId()), subtotal, tax, actor.userId());
            billLines.forEach(l -> bills.insertBillLine(actor.businessId(), billId, l));
            orders.transitionSession(actor.businessId(), sessionId, "open", "billed");
            audit.record(actor.businessId(), session.branchId(), actor.userId(), "restaurant.bill.issued", "bill", billId);
            return orderService.view(actor, sessionId);
        });
    }

    SessionView pay(RestaurantActor actor, UUID billId, String method, long amountMinor, String reference) {
        return tx.inContext(actor.businessId(), actor.userId(), () -> {
            Bill bill = bills.lock(actor.businessId(), billId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "BILL_NOT_FOUND", "Bill not found."));
            try {
                guard.require(actor, bill.branchId());
            } catch (ApiException e) {
                throw new ApiException(HttpStatus.NOT_FOUND, "BILL_NOT_FOUND", "Bill not found.");
            }
            if (!"issued".equals(bill.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "BILL_ALREADY_PAID", "This bill is already paid.");
            }
            long paid = bills.payments(actor.businessId(), billId).stream().mapToLong(BillRepository.Payment::amountMinor).sum();
            long due = bill.totalMinor() - paid;
            if (amountMinor > due) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "OVERPAYMENT", "The amount is more than what is due.")
                        .with("dueMinor", due);
            }
            bills.insertPayment(actor.businessId(), billId, method, amountMinor, blankToNull(reference), actor.userId());
            audit.record(actor.businessId(), bill.branchId(), actor.userId(), "restaurant.payment.recorded", "bill", billId);
            if (amountMinor == due) {
                bills.markPaid(actor.businessId(), billId);
                orders.transitionSession(actor.businessId(), bill.sessionId(), "billed", "closed");
                audit.record(actor.businessId(), bill.branchId(), actor.userId(), "restaurant.bill.paid", "bill", billId);
            }
            return orderService.view(actor, bill.sessionId());
        });
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
