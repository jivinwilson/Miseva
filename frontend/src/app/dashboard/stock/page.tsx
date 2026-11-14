import type { Metadata } from "next";
import { Suspense } from "react";
import { IngredientDialog, StockDialog } from "@/components/inventory-forms";
import { BranchPicker } from "@/components/restaurant-forms";
import { apiFetch, type Ingredient, type StockView } from "@/lib/api";
import { formatQty } from "@/lib/money";
import { loadContext, single } from "@/lib/restaurant";

export const metadata: Metadata = { title: "Stock · MISEVA" };

const primary =
  "rounded-lg bg-obsidian px-4 py-2 text-sm font-medium text-ivory transition hover:bg-emerald focus:outline-none focus-visible:ring-2 focus-visible:ring-gold focus-visible:ring-offset-2";
const subtle =
  "rounded-lg border border-obsidian/20 px-3 py-2 text-sm text-obsidian transition hover:bg-champagne focus:outline-none focus-visible:ring-2 focus-visible:ring-gold";

const REASON: Record<string, string> = { purchase: "Purchase", waste: "Waste", adjustment: "Count correction", consumption: "Sold" };

export default function StockPage({ searchParams }: PageProps<"/dashboard/stock">) {
  return (
    <Suspense fallback={<p className="text-obsidian/60">Loading stock…</p>}>
      <Stock searchParams={searchParams} />
    </Suspense>
  );
}

async function Stock({ searchParams }: { searchParams: PageProps<"/dashboard/stock">["searchParams"] }) {
  const requested = single((await searchParams).branch);
  const { token, me, branches, branch } = await loadContext("/dashboard/stock", requested);
  if (!me.permissions.includes("restaurant.inventory.read")) {
    return <p role="alert" className="rounded-lg border border-obsidian/15 bg-white px-4 py-3">You do not have access to stock.</p>;
  }
  if (!branch) {
    return <p className="rounded-lg border border-obsidian/15 bg-white px-4 py-3">No active branch is available to you.</p>;
  }
  const [stock, ingredients] = await Promise.all([
    apiFetch<StockView>(`/restaurant/branches/${branch.id}/stock`, { token }),
    apiFetch<Ingredient[]>("/restaurant/ingredients", { token }),
  ]);
  const canManage = me.permissions.includes("restaurant.inventory.manage");
  const active = ingredients.filter((i) => i.active);
  const low = stock.levels.filter((l) => l.low && l.lowStockThreshold > 0);

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="font-serif text-3xl text-obsidian">Stock</h1>
          <p className="mt-1 text-obsidian/70">{branch.name} · {stock.levels.length} {stock.levels.length === 1 ? "ingredient" : "ingredients"}</p>
        </div>
        <div className="flex flex-wrap items-end gap-2">
          <BranchPicker branches={branches} current={branch.id} basePath="/dashboard/stock" />
          {canManage ? <IngredientDialog triggerClassName={subtle} /> : null}
          {canManage && active.length ? <StockDialog branchId={branch.id} ingredients={active} triggerClassName={primary} /> : null}
        </div>
      </div>

      {low.length ? (
        <p role="status" className="rounded-xl border border-red-700/30 bg-red-50 px-4 py-3 text-sm text-red-900">
          Running low: {low.map((l) => l.name).join(", ")}
        </p>
      ) : null}

      {stock.levels.length === 0 ? (
        <p className="rounded-2xl border border-dashed border-obsidian/20 bg-white px-6 py-10 text-center text-obsidian/70">
          No ingredients yet. Add the ingredients you buy, then link them to dishes from the Menu page.
        </p>
      ) : (
        <div className="overflow-x-auto rounded-2xl border border-obsidian/10 bg-white shadow-sm">
          <table className="w-full text-left text-sm">
            <thead className="border-b border-obsidian/10 text-obsidian/60">
              <tr>
                <th scope="col" className="px-6 py-3 font-medium">Ingredient</th>
                <th scope="col" className="px-6 py-3 text-right font-medium">On hand</th>
                <th scope="col" className="px-6 py-3 text-right font-medium">Alert below</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-obsidian/10">
              {stock.levels.map((l) => (
                <tr key={l.ingredientId}>
                  <th scope="row" className="px-6 py-3 font-medium text-obsidian">
                    {l.name}
                    {l.low && l.lowStockThreshold > 0 ? (
                      <span className="ml-2 rounded-full bg-red-100 px-2 py-0.5 text-xs font-medium text-red-900">Low</span>
                    ) : null}
                  </th>
                  <td className={`px-6 py-3 text-right tabular-nums ${l.onHand < 0 ? "text-red-800" : "text-obsidian"}`}>
                    {formatQty(l.onHand, l.unit)}
                  </td>
                  <td className="px-6 py-3 text-right tabular-nums text-obsidian/60">
                    {l.lowStockThreshold > 0 ? formatQty(l.lowStockThreshold, l.unit) : "—"}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {stock.recent.length ? (
        <section aria-labelledby="recent" className="rounded-2xl border border-obsidian/10 bg-white shadow-sm">
          <h2 id="recent" className="border-b border-obsidian/10 px-6 py-4 font-serif text-xl text-obsidian">Recent movements</h2>
          <ul className="divide-y divide-obsidian/10 text-sm">
            {stock.recent.map((m) => (
              <li key={m.id} className="flex flex-wrap items-center justify-between gap-2 px-6 py-2.5">
                <span>
                  <span className="font-medium text-obsidian">{m.ingredientName}</span>
                  <span className="text-obsidian/60"> · {REASON[m.reason] ?? m.reason}{m.note ? ` · ${m.note}` : ""}</span>
                </span>
                <span className="flex items-center gap-4">
                  <span className={`tabular-nums ${m.quantity < 0 ? "text-red-800" : "text-emerald"}`}>
                    {m.quantity > 0 ? "+" : ""}{formatQty(m.quantity, m.unit)}
                  </span>
                  <span className="text-xs text-obsidian/50">
                    {new Date(m.createdAt).toLocaleString("en-IN", { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" })}
                  </span>
                </span>
              </li>
            ))}
          </ul>
        </section>
      ) : null}
    </div>
  );
}
