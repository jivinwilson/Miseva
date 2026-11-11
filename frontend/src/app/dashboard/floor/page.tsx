import type { Metadata } from "next";
import Link from "next/link";
import { Suspense } from "react";
import { BranchPicker, OpenTableForm, TableDialog } from "@/components/restaurant-forms";
import { apiFetch, type BusinessSummary, type TableView } from "@/lib/api";
import { formatMoney } from "@/lib/money";
import { loadContext, single } from "@/lib/restaurant";

export const metadata: Metadata = { title: "Floor · MISEVA" };

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

  return (
    <div className="space-y-6">
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
                </div>
                <span className="rounded-full bg-obsidian/5 px-2.5 py-0.5 text-xs text-obsidian/80">{state}</span>
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
            </li>
          );
        })}
      </ul>
    </div>
  );
}
