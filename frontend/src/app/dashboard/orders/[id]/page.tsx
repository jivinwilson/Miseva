import type { Metadata } from "next";
import Link from "next/link";
import { Suspense } from "react";
import { changeLineAction } from "@/app/actions/restaurant";
import { AddLineForm, IssueBillButton, PaymentForm } from "@/components/restaurant-forms";
import { ApiError, apiFetch, type Menu, type SessionView } from "@/lib/api";
import { formatMoney } from "@/lib/money";
import { loadContext } from "@/lib/restaurant";

export const metadata: Metadata = { title: "Order · MISEVA" };

const STATUS_LABEL: Record<string, string> = {
  new: "Sent to kitchen",
  preparing: "Preparing",
  ready: "Ready",
  served: "Served",
  cancelled: "Cancelled",
};

const subtle =
  "rounded-lg border border-obsidian/20 px-3 py-1.5 text-sm text-obsidian transition hover:bg-champagne focus:outline-none focus-visible:ring-2 focus-visible:ring-gold";

export default function OrderPage({ params }: PageProps<"/dashboard/orders/[id]">) {
  return (
    <Suspense fallback={<p className="text-obsidian/60">Loading order…</p>}>
      <Order params={params} />
    </Suspense>
  );
}

async function Order({ params }: { params: PageProps<"/dashboard/orders/[id]">["params"] }) {
  const { id } = await params;
  const back = `/dashboard/orders/${id}`;
  const { token, me } = await loadContext(back);
  let view: SessionView | undefined;
  try {
    view = await apiFetch<SessionView>(`/restaurant/sessions/${encodeURIComponent(id)}`, { token });
  } catch (e) {
    if (!(e instanceof ApiError)) throw e;
  }
  if (!view) {
    return <p role="alert" className="rounded-lg border border-obsidian/15 bg-white px-4 py-3">This order could not be found.</p>;
  }

  const can = (p: string) => me.permissions.includes(p);
  const { session, lines, currency, bill } = view;
  const money = (m: number) => formatMoney(m, currency);
  const items = session.status === "open" && can("restaurant.order.take")
    ? (await apiFetch<Menu>("/restaurant/menu", { token })).items.filter((i) => i.available)
    : [];

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <Link href={`/dashboard/floor?branch=${session.branchId}`} className="text-sm text-emerald underline underline-offset-4">
            ← Floor
          </Link>
          <h1 className="mt-2 font-serif text-3xl text-obsidian">Table {session.tableCode}</h1>
          <p className="mt-1 text-obsidian/70">
            {session.guests} guests · opened {new Date(session.openedAt).toLocaleTimeString("en-IN", { hour: "2-digit", minute: "2-digit" })}
            {" · "}
            {session.status === "open" ? "Open" : session.status === "billed" ? "Bill issued" : "Closed"}
          </p>
        </div>
      </div>

      {items.length ? (
        <section aria-labelledby="add" className="rounded-2xl border border-obsidian/10 bg-white p-6 shadow-sm">
          <h2 id="add" className="mb-4 font-serif text-xl text-obsidian">Add to order</h2>
          <AddLineForm sessionId={session.id} items={items} />
        </section>
      ) : null}

      <section aria-labelledby="lines" className="rounded-2xl border border-obsidian/10 bg-white shadow-sm">
        <h2 id="lines" className="border-b border-obsidian/10 px-6 py-4 font-serif text-xl text-obsidian">Order</h2>
        {lines.length === 0 ? <p className="px-6 py-4 text-sm text-obsidian/60">Nothing ordered yet.</p> : null}
        <ul className="divide-y divide-obsidian/10">
          {lines.map((l) => (
            <li key={l.id} className={`flex flex-wrap items-center justify-between gap-3 px-6 py-3 ${l.status === "cancelled" ? "opacity-50" : ""}`}>
              <div className="min-w-0">
                <p className="font-medium text-obsidian">
                  {l.quantity} × {l.itemName}
                </p>
                <p className="text-sm text-obsidian/60">
                  {STATUS_LABEL[l.status]}
                  {l.note ? ` · ${l.note}` : ""}
                </p>
              </div>
              <div className="flex items-center gap-3">
                <span className="tabular-nums text-obsidian">{money(l.unitPriceMinor * l.quantity)}</span>
                {l.status === "new" && session.status === "open" && can("restaurant.order.take") ? (
                  <form action={changeLineAction}>
                    <input type="hidden" name="lineId" value={l.id} />
                    <input type="hidden" name="status" value="cancelled" />
                    <input type="hidden" name="back" value={back} />
                    <button type="submit" className={subtle}>Cancel</button>
                  </form>
                ) : null}
                {l.status === "ready" ? (
                  <form action={changeLineAction}>
                    <input type="hidden" name="lineId" value={l.id} />
                    <input type="hidden" name="status" value="served" />
                    <input type="hidden" name="back" value={back} />
                    <button type="submit" className={subtle}>Mark served</button>
                  </form>
                ) : null}
              </div>
            </li>
          ))}
        </ul>
        <dl className="space-y-1 border-t border-obsidian/10 px-6 py-4 text-sm">
          <div className="flex justify-between"><dt className="text-obsidian/70">Subtotal</dt><dd className="tabular-nums">{money(view.subtotalMinor)}</dd></div>
          <div className="flex justify-between"><dt className="text-obsidian/70">Tax</dt><dd className="tabular-nums">{money(view.taxMinor)}</dd></div>
          <div className="flex justify-between text-base font-medium"><dt>Total</dt><dd className="tabular-nums">{money(view.totalMinor)}</dd></div>
        </dl>
      </section>

      {session.status === "open" && can("restaurant.bill.manage") && lines.some((l) => l.status !== "cancelled") ? (
        <div className="max-w-xs">
          <IssueBillButton sessionId={session.id} />
        </div>
      ) : null}

      {bill ? (
        <section aria-labelledby="bill" className="rounded-2xl border border-gold/50 bg-white p-6 shadow-sm">
          <div className="flex flex-wrap items-baseline justify-between gap-2">
            <h2 id="bill" className="font-serif text-xl text-obsidian">Bill {bill.bill.billNumber}</h2>
            <span className="rounded-full bg-champagne px-3 py-1 text-xs font-medium text-emerald">
              {bill.bill.status === "paid" ? "Paid" : `${money(bill.dueMinor)} due`}
            </span>
          </div>
          <dl className="mt-4 space-y-1 text-sm">
            <div className="flex justify-between"><dt className="text-obsidian/70">Total</dt><dd className="tabular-nums">{money(bill.bill.totalMinor)}</dd></div>
            {bill.payments.map((p) => (
              <div key={p.id} className="flex justify-between">
                <dt className="text-obsidian/70">Paid by {p.method.toUpperCase()}{p.reference ? ` (${p.reference})` : ""}</dt>
                <dd className="tabular-nums">−{money(p.amountMinor)}</dd>
              </div>
            ))}
          </dl>
          {bill.bill.status === "issued" && can("restaurant.bill.manage") ? (
            <div className="mt-6">
              <PaymentForm sessionId={session.id} billId={bill.bill.id} dueMajor={(bill.dueMinor / 100).toFixed(2)} />
            </div>
          ) : null}
        </section>
      ) : null}
    </div>
  );
}
