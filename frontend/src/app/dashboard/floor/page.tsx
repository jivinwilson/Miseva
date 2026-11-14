import type { Metadata } from "next";
import Link from "next/link";
import { Suspense } from "react";
import { decideGuestOrderAction, resolveRequestFormAction } from "@/app/actions/restaurant";
import { WaiterSelect } from "@/components/waiter-select";
import { AutoRefresh, BranchPicker, OpenTableForm, TableDialog } from "@/components/restaurant-forms";
import { apiFetch, type AlertView, type BusinessSummary, type PendingGuestOrder, type TableView, type Waiter } from "@/lib/api";
import { formatMoney } from "@/lib/money";
import { loadContext, single } from "@/lib/restaurant";

export const metadata: Metadata = { title: "Floor · MISEVA" };

const subtle =
  "rounded-lg border border-obsidian/20 px-3 py-1.5 text-sm text-obsidian transition hover:bg-champagne focus:outline-none focus-visible:ring-2 focus-visible:ring-gold";

const primary =
  "rounded-lg bg-obsidian px-4 py-2 text-sm font-medium text-ivory transition hover:bg-emerald focus:outline-none focus-visible:ring-2 focus-visible:ring-gold focus-visible:ring-offset-2";

export default function FloorPage({ searchParams }: PageProps<"/dashboard/floor">) {
  return (
    <Suspense fallback={<p className="text-obsidian/60">Loading floor…</p>}>
      <Floor searchParams={searchParams} />
    </Suspense>
  );
}

async function Floor({ searchParams }: { searchParams: PageProps<"/dashboard/floor">["searchParams"] }) {
  const requested = single((await searchParams).branch);
  const { token, me, branches, branch } = await loadContext("/dashboard/floor", requested);
  if (!me.permissions.includes("restaurant.table.read")) {
    return <p role="alert" className="rounded-lg border border-obsidian/15 bg-white px-4 py-3">You do not have access to the floor.</p>;
  }
  if (!branch) {
    return <p className="rounded-lg border border-obsidian/15 bg-white px-4 py-3">No active branch is available to you.</p>;
  }
  const [tables, business] = await Promise.all([
    apiFetch<TableView[]>(`/restaurant/branches/${branch.id}/tables`, { token }),
    apiFetch<BusinessSummary>("/businesses/current", { token }),
  ]);
  const canTake = me.permissions.includes("restaurant.order.take");
  const guestOrders = canTake
    ? await apiFetch<PendingGuestOrder[]>(`/restaurant/branches/${branch.id}/guest-orders`, { token })
    : [];
  const back = `/dashboard/floor?branch=${branch.id}`;
  const canManageTables = me.permissions.includes("restaurant.table.manage");
  const [waiters, alerts] = await Promise.all([
    canManageTables ? apiFetch<Waiter[]>(`/restaurant/branches/${branch.id}/waiters`, { token }) : Promise.resolve([] as Waiter[]),
    canTake ? apiFetch<AlertView[]>(`/restaurant/branches/${branch.id}/alerts`, { token }) : Promise.resolve([] as AlertView[]),
  ]);
  const requests = alerts.filter((a) => a.kind === "call_waiter" || a.kind === "request_bill");

  return (
    <div className="space-y-6">
      <AutoRefresh seconds={10} />
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="font-serif text-3xl text-obsidian">Floor</h1>
          <p className="mt-1 text-obsidian/70">
            {branch.name} · {tables.filter((t) => t.sessionId).length} of {tables.length} tables occupied
          </p>
        </div>
        <div className="flex flex-wrap items-end gap-3">
          <BranchPicker branches={branches} current={branch.id} basePath="/dashboard/floor" />
          {me.permissions.includes("restaurant.table.manage") ? <TableDialog branchId={branch.id} triggerClassName={primary} /> : null}
        </div>
      </div>

      {requests.length ? (
        <section aria-labelledby="requests" className="rounded-2xl border border-gold/60 bg-white p-5 shadow-sm">
          <h2 id="requests" className="font-serif text-xl text-obsidian">Tables asking for you</h2>
          <ul className="mt-3 flex flex-wrap gap-2">
            {requests.map((r) => (
              <li key={r.id} className="flex items-center gap-3 rounded-xl border border-obsidian/10 bg-ivory px-3 py-2 text-sm">
                <span className="rounded bg-obsidian px-1.5 py-0.5 text-xs font-medium text-ivory">{r.tableCode}</span>
                {r.kind === "call_waiter" ? "Calling a waiter" : "Wants the bill"}
                <form action={resolveRequestFormAction}>
                  <input type="hidden" name="id" value={r.id} />
                  <button type="submit" className={subtle}>Done</button>
                </form>
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      {guestOrders.length ? (
        <section aria-labelledby="guest-orders" className="rounded-2xl border border-gold/60 bg-white p-5 shadow-sm">
          <h2 id="guest-orders" className="font-serif text-xl text-obsidian">
            Guest orders waiting <span className="font-sans text-sm text-obsidian/60">({guestOrders.length})</span>
          </h2>
          <ul className="mt-4 grid gap-3 md:grid-cols-2">
            {guestOrders.map(({ order, lines }) => (
              <li key={order.id} className="rounded-xl border border-obsidian/10 bg-ivory p-4">
                <div className="flex items-start justify-between gap-2">
                  <p className="font-medium text-obsidian">Table {order.tableCode} · {order.guests} guests</p>
                  <span className="text-xs text-obsidian/60">
                    {new Date(order.createdAt).toLocaleTimeString("en-IN", { hour: "2-digit", minute: "2-digit" })}
                  </span>
                </div>
                <ul className="mt-2 space-y-0.5 text-sm text-obsidian/80">
                  {lines.map((l) => (
                    <li key={l.id}>
                      {l.quantity} × {l.itemName}
                      {l.note ? <span className="text-red-800"> · {l.note}</span> : null}
                    </li>
                  ))}
                </ul>
                {order.note ? <p className="mt-2 text-sm italic text-obsidian/70">“{order.note}”</p> : null}
                <div className="mt-3 flex gap-2">
                  <form action={decideGuestOrderAction}>
                    <input type="hidden" name="id" value={order.id} />
                    <input type="hidden" name="decision" value="accept" />
                    <input type="hidden" name="back" value={back} />
                    <button type="submit" className={primary}>Accept and send to kitchen</button>
                  </form>
                  <form action={decideGuestOrderAction}>
                    <input type="hidden" name="id" value={order.id} />
                    <input type="hidden" name="decision" value="reject" />
                    <input type="hidden" name="back" value={back} />
                    <button type="submit" className={subtle}>Decline</button>
                  </form>
                </div>
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      {tables.length === 0 ? (
        <p className="rounded-2xl border border-dashed border-obsidian/20 bg-white px-6 py-10 text-center text-obsidian/70">
          No tables yet. Add your first table to start taking orders.
        </p>
      ) : null}

      <ul className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {tables.map((t) => {
          const state = !t.active ? "Not in use" : t.sessionStatus === "billed" ? "Bill issued" : t.sessionId ? "Occupied" : "Free";
          return (
            <li
              key={t.id}
              className={`rounded-2xl border p-5 shadow-sm ${t.sessionId ? "border-gold/50 bg-champagne/40" : "border-obsidian/10 bg-white"}`}
            >
              <div className="flex items-start justify-between gap-3">
                <div>
                  <p className="font-serif text-2xl text-obsidian">{t.code}</p>
                  <p className="text-sm text-obsidian/60">
                    {t.seats} seats{t.area ? ` · ${t.area}` : ""}
                  </p>
                  {!canManageTables && t.waiterName ? <p className="text-xs text-obsidian/60">Waiter: {t.waiterName}</p> : null}
                </div>
                <div className="flex flex-col items-end gap-1">
                  <span className="rounded-full bg-obsidian/5 px-2.5 py-0.5 text-xs text-obsidian/80">{state}</span>
                  {t.openRequests ? (
                    <span className="rounded-full bg-red-100 px-2.5 py-0.5 text-xs font-medium text-red-900">
                      {t.openRequests} {t.openRequests === 1 ? "request" : "requests"}
                    </span>
                  ) : null}
                  {t.pendingGuestOrders ? (
                    <span className="rounded-full bg-gold px-2.5 py-0.5 text-xs font-medium text-obsidian">
                      {t.pendingGuestOrders} guest {t.pendingGuestOrders === 1 ? "order" : "orders"}
                    </span>
                  ) : null}
                  <Link href={`/dashboard/tables/${t.id}/qr?branch=${branch.id}`} className="text-xs text-emerald underline underline-offset-4">
                    QR code
                  </Link>
                </div>
              </div>
              {t.sessionId ? (
                <div className="mt-4 flex items-end justify-between gap-3">
                  <p className="text-sm text-obsidian/70">
                    {t.guests} guests · {t.openLines} in kitchen
                    <span className="block font-medium tabular-nums text-obsidian">{formatMoney(t.amountMinor, business.currency)} before tax</span>
                  </p>
                  <Link href={`/dashboard/orders/${t.sessionId}`} className={primary}>
                    View order
                  </Link>
                </div>
              ) : t.active && canTake ? (
                <OpenTableForm tableId={t.id} />
              ) : null}
              {canManageTables ? (
                <WaiterSelect tableId={t.id} tableCode={t.code} current={t.waiterMembershipId} waiters={waiters} />
              ) : null}
            </li>
          );
        })}
      </ul>
    </div>
  );
}
