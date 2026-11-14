import type { Metadata } from "next";
import { Suspense } from "react";
import { RecipeDialog } from "@/components/inventory-forms";
import { CategoryDialog, ItemDialog } from "@/components/restaurant-forms";
import { apiFetch, type BusinessSummary, type Ingredient, type Menu } from "@/lib/api";
import { formatMoney } from "@/lib/money";
import { loadContext } from "@/lib/restaurant";

export const metadata: Metadata = { title: "Menu · MISEVA" };

const primary =
  "rounded-lg bg-obsidian px-4 py-2 text-sm font-medium text-ivory transition hover:bg-emerald focus:outline-none focus-visible:ring-2 focus-visible:ring-gold focus-visible:ring-offset-2";
const subtle =
  "rounded-lg border border-obsidian/20 px-3 py-1.5 text-sm text-obsidian transition hover:bg-champagne focus:outline-none focus-visible:ring-2 focus-visible:ring-gold";

export default function MenuPage() {
  return (
    <Suspense fallback={<p className="text-obsidian/60">Loading menu…</p>}>
      <MenuView />
    </Suspense>
  );
}

async function MenuView() {
  const { token, me } = await loadContext("/dashboard/menu");
  if (!me.permissions.includes("restaurant.menu.read")) {
    return <p role="alert" className="rounded-lg border border-obsidian/15 bg-white px-4 py-3">You do not have access to the menu.</p>;
  }
  const [menu, business] = await Promise.all([
    apiFetch<Menu>("/restaurant/menu", { token }),
    apiFetch<BusinessSummary>("/businesses/current", { token }),
  ]);
  const canManage = me.permissions.includes("restaurant.menu.manage");
  const canSeeRecipes = me.permissions.includes("restaurant.inventory.read");
  const canEditRecipes = me.permissions.includes("restaurant.inventory.manage");
  const ingredients = canSeeRecipes
    ? (await apiFetch<Ingredient[]>("/restaurant/ingredients", { token })).filter((i) => i.active)
    : [];

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="font-serif text-3xl text-obsidian">Menu</h1>
          <p className="mt-1 text-obsidian/70">
            {menu.items.length} {menu.items.length === 1 ? "item" : "items"} in {menu.categories.length}{" "}
            {menu.categories.length === 1 ? "category" : "categories"}. Prices are before tax.
          </p>
        </div>
        {canManage ? (
          <div className="flex gap-2">
            <CategoryDialog triggerClassName={subtle} />
            {menu.categories.length ? <ItemDialog categories={menu.categories} triggerLabel="Add item" triggerClassName={primary} /> : null}
          </div>
        ) : null}
      </div>

      {menu.categories.length === 0 ? (
        <p className="rounded-2xl border border-dashed border-obsidian/20 bg-white px-6 py-10 text-center text-obsidian/70">
          Your menu is empty. Start by adding a category, such as Starters or Mains.
        </p>
      ) : null}

      {menu.categories.map((c) => {
        const items = menu.items.filter((i) => i.categoryId === c.id);
        return (
          <section key={c.id} aria-labelledby={`cat-${c.id}`} className="rounded-2xl border border-obsidian/10 bg-white shadow-sm">
            <h2 id={`cat-${c.id}`} className="border-b border-obsidian/10 px-6 py-4 font-serif text-xl text-obsidian">{c.name}</h2>
            {items.length === 0 ? <p className="px-6 py-4 text-sm text-obsidian/60">No items yet.</p> : null}
            <ul className="divide-y divide-obsidian/10">
              {items.map((i) => (
                <li key={i.id} className="flex flex-wrap items-center justify-between gap-4 px-6 py-3">
                  <div className="min-w-0">
                    <p className="flex flex-wrap items-center gap-2">
                      <span className="font-medium text-obsidian">{i.name}</span>
                      {i.available ? null : (
                        <span className="rounded-full bg-obsidian/10 px-2.5 py-0.5 text-xs text-obsidian/70">Unavailable</span>
                      )}
                    </p>
                    {i.description ? <p className="mt-0.5 truncate text-sm text-obsidian/60">{i.description}</p> : null}
                  </div>
                  <div className="flex items-center gap-4">
                    <span className="text-right tabular-nums text-obsidian">
                      {formatMoney(i.priceMinor, business.currency)}
                      <span className="block text-xs text-obsidian/60">+{i.taxRateBp / 100}% tax</span>
                    </span>
                    {canSeeRecipes ? (
                      <RecipeDialog itemId={i.id} itemName={i.name} ingredients={ingredients} canManage={canEditRecipes} triggerClassName={subtle} />
                    ) : null}
                    {canManage ? <ItemDialog categories={menu.categories} item={i} triggerLabel="Edit" triggerClassName={subtle} /> : null}
                  </div>
                </li>
              ))}
            </ul>
          </section>
        );
      })}
    </div>
  );
}
