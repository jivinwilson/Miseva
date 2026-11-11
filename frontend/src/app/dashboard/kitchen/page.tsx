import type { Metadata } from "next";
import { Suspense } from "react";
import { changeLineAction } from "@/app/actions/restaurant";
import { AutoRefresh, BranchPicker } from "@/components/restaurant-forms";
import { apiFetch, type LineStatus, type OrderLine } from "@/lib/api";
import { loadContext, single } from "@/lib/restaurant";

export const metadata: Metadata = { title: "Kitchen · MISEVA" };

const COLUMNS: { status: LineStatus; title: string; next: LineStatus; action: string }[] = [
  { status: "new", title: "New", next: "preparing", action: "Start" },
  { status: "preparing", title: "Preparing", next: "ready", action: "Ready" },
  { status: "ready", title: "Ready to serve", next: "served", action: "Served" },
];

export default function KitchenPage({ searchParams }: PageProps<"/dashboard/kitchen">) {
  return (
    <Suspense fallback={<p className="text-obsidian/60">Loading kitchen…</p>}>
      <Kitchen searchParams={searchParams} />
    </Suspense>
  );
}

function minutesAgo(iso: string) {
  const minutes = Math.max(0, Math.floor((Date.now() - new Date(iso).getTime()) / 60_000));
  return minutes === 0 ? "just now" : `${minutes} min ago`;
}

async function Kitchen({ searchParams }: { searchParams: PageProps<"/dashboard/kitchen">["searchParams"] }) {
  const requested = single((await searchParams).branch);
  const { token, me, branches, branch } = await loadContext("/dashboard/kitchen", requested);
  const allowed = me.permissions.includes("restaurant.kitchen.update") || me.permissions.includes("restaurant.order.take");
  if (!allowed) {
    return <p role="alert" className="rounded-lg border border-obsidian/15 bg-white px-4 py-3">You do not have access to the kitchen.</p>;
  }
  if (!branch) {
    return <p className="rounded-lg border border-obsidian/15 bg-white px-4 py-3">No active branch is available to you.</p>;
  }
  const lines = await apiFetch<OrderLine[]>(`/restaurant/branches/${branch.id}/kitchen`, { token });
  const back = `/dashboard/kitchen?branch=${branch.id}`;

  return (
    <div className="space-y-6">
      <AutoRefresh seconds={5} />
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="font-serif text-3xl text-obsidian">Kitchen</h1>
          <p className="mt-1 text-obsidian/70">{branch.name} · updates every few seconds</p>
        </div>
        <BranchPicker branches={branches} current={branch.id} basePath="/dashboard/kitchen" />
      </div>

      <div className="grid gap-4 lg:grid-cols-3">
        {COLUMNS.map((col) => {
          const items = lines.filter((l) => l.status === col.status);
          return (
            <section key={col.status} aria-labelledby={`col-${col.status}`} className="rounded-2xl border border-obsidian/10 bg-white p-4 shadow-sm">
              <h2 id={`col-${col.status}`} className="mb-3 flex items-center justify-between font-serif text-xl text-obsidian">
                {col.title}
                <span className="rounded-full bg-obsidian/5 px-2.5 py-0.5 font-sans text-xs text-obsidian/70">{items.length}</span>
              </h2>
              {items.length === 0 ? <p className="py-6 text-center text-sm text-obsidian/50">Nothing here.</p> : null}
              <ul className="space-y-3">
                {items.map((l) => (
                  <li key={l.id} className="rounded-xl border border-obsidian/10 bg-ivory p-3">
                    <div className="flex items-start justify-between gap-2">
                      <p className="font-medium text-obsidian">
                        {l.quantity} × {l.itemName}
                      </p>
                      <span className="shrink-0 rounded bg-obsidian px-2 py-0.5 text-xs font-medium text-ivory">{l.tableCode}</span>
                    </div>
                    {l.note ? <p className="mt-1 text-sm text-red-800">{l.note}</p> : null}
                    <div className="mt-2 flex items-center justify-between gap-2">
                      <span className="text-xs text-obsidian/60">{minutesAgo(l.createdAt)}</span>
                      <form action={changeLineAction}>
                        <input type="hidden" name="lineId" value={l.id} />
                        <input type="hidden" name="status" value={col.next} />
                        <input type="hidden" name="back" value={back} />
                        <button
                          type="submit"
                          className="rounded-lg bg-obsidian px-3 py-1.5 text-sm font-medium text-ivory transition hover:bg-emerald focus:outline-none focus-visible:ring-2 focus-visible:ring-gold"
                        >
                          {col.action}
                        </button>
                      </form>
                    </div>
                  </li>
                ))}
              </ul>
            </section>
          );
        })}
      </div>
    </div>
  );
}
