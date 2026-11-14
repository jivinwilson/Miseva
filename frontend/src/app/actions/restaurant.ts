"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { ApiError, apiFetch, type AlertView, type BranchList, type Me, type RecipeLine, type SessionView } from "@/lib/api";
import { percentToBp, toMinor } from "@/lib/money";
import { getAccessToken } from "@/lib/session";

export interface ActionState {
  ok?: boolean;
  error?: string;
  fieldErrors?: Record<string, string>;
}

async function token(next: string) {
  const t = await getAccessToken();
  if (!t) redirect(`/auth/refresh?next=${encodeURIComponent(next)}`);
  return t;
}

function failure(e: unknown, next: string): ActionState {
  if (e instanceof ApiError) {
    if (e.problem.status === 401) redirect(`/auth/refresh?next=${encodeURIComponent(next)}`);
    if (e.problem.errors?.length) {
      return { fieldErrors: Object.fromEntries(e.problem.errors.map((x) => [x.field, x.message])) };
    }
    return { error: e.message };
  }
  return { error: "Cannot reach the server. Please try again." };
}

const text = (fd: FormData, k: string) => String(fd.get(k) ?? "").trim();

export async function createCategoryAction(_prev: ActionState, fd: FormData): Promise<ActionState> {
  const name = text(fd, "name");
  if (!name) return { fieldErrors: { name: "Enter a category name." } };
  try {
    await apiFetch("/restaurant/menu/categories", {
      method: "POST",
      token: await token("/dashboard/menu"),
      body: JSON.stringify({ name, sortOrder: Number(text(fd, "sortOrder") || "0") }),
    });
  } catch (e) {
    return failure(e, "/dashboard/menu");
  }
  revalidatePath("/dashboard/menu");
  return { ok: true };
}

export async function saveItemAction(_prev: ActionState, fd: FormData): Promise<ActionState> {
  const id = text(fd, "id");
  const priceMinor = toMinor(text(fd, "price"));
  const taxRateBp = percentToBp(text(fd, "tax"));
  const fieldErrors: Record<string, string> = {};
  if (!text(fd, "name")) fieldErrors.name = "Enter an item name.";
  if (!text(fd, "categoryId")) fieldErrors.categoryId = "Choose a category.";
  if (Number.isNaN(priceMinor)) fieldErrors.price = "Enter a price, for example 250 or 249.50.";
  if (Number.isNaN(taxRateBp)) fieldErrors.tax = "Enter a tax rate between 0 and 100.";
  if (Object.keys(fieldErrors).length) return { fieldErrors };

  try {
    await apiFetch(id ? `/restaurant/menu/items/${encodeURIComponent(id)}` : "/restaurant/menu/items", {
      method: id ? "PUT" : "POST",
      token: await token("/dashboard/menu"),
      body: JSON.stringify({
        categoryId: text(fd, "categoryId"),
        name: text(fd, "name"),
        description: text(fd, "description"),
        priceMinor,
        taxRateBp,
        available: fd.get("available") === "on",
        sortOrder: 0,
      }),
    });
  } catch (e) {
    return failure(e, "/dashboard/menu");
  }
  revalidatePath("/dashboard/menu");
  return { ok: true };
}

export async function createTableAction(_prev: ActionState, fd: FormData): Promise<ActionState> {
  const branchId = text(fd, "branchId");
  const next = `/dashboard/floor?branch=${branchId}`;
  if (!text(fd, "code")) return { fieldErrors: { code: "Enter a table code, for example T1." } };
  try {
    await apiFetch(`/restaurant/branches/${encodeURIComponent(branchId)}/tables`, {
      method: "POST",
      token: await token(next),
      body: JSON.stringify({ code: text(fd, "code"), seats: Number(text(fd, "seats") || "4"), area: text(fd, "area") }),
    });
  } catch (e) {
    return failure(e, next);
  }
  revalidatePath("/dashboard/floor");
  return { ok: true };
}

export async function openTableAction(_prev: ActionState, fd: FormData): Promise<ActionState> {
  const tableId = text(fd, "tableId");
  let view: SessionView;
  try {
    view = await apiFetch<SessionView>(`/restaurant/tables/${encodeURIComponent(tableId)}/sessions`, {
      method: "POST",
      token: await token("/dashboard/floor"),
      body: JSON.stringify({ guests: Number(text(fd, "guests") || "2") }),
    });
  } catch (e) {
    return failure(e, "/dashboard/floor");
  }
  redirect(`/dashboard/orders/${view.session.id}`);
}

export async function addLineAction(_prev: ActionState, fd: FormData): Promise<ActionState> {
  const sessionId = text(fd, "sessionId");
  const next = `/dashboard/orders/${sessionId}`;
  try {
    await apiFetch(`/restaurant/sessions/${encodeURIComponent(sessionId)}/lines`, {
      method: "POST",
      token: await token(next),
      body: JSON.stringify({
        lines: [{ menuItemId: text(fd, "menuItemId"), quantity: Number(text(fd, "quantity") || "1"), note: text(fd, "note") }],
      }),
    });
  } catch (e) {
    return failure(e, next);
  }
  revalidatePath(next);
  return { ok: true };
}

export async function changeLineAction(fd: FormData) {
  const back = text(fd, "back") || "/dashboard/kitchen";
  try {
    await apiFetch(`/restaurant/lines/${encodeURIComponent(text(fd, "lineId"))}/status`, {
      method: "POST",
      token: await token(back),
      body: JSON.stringify({ status: text(fd, "status") }),
    });
  } catch (e) {
    if (e instanceof ApiError && e.problem.status === 401) redirect(`/auth/refresh?next=${encodeURIComponent(back)}`);
  }
  revalidatePath(back.split("?")[0]);
}

export async function issueBillAction(_prev: ActionState, fd: FormData): Promise<ActionState> {
  const sessionId = text(fd, "sessionId");
  const next = `/dashboard/orders/${sessionId}`;
  try {
    await apiFetch(`/restaurant/sessions/${encodeURIComponent(sessionId)}/bill`, { method: "POST", token: await token(next) });
  } catch (e) {
    return failure(e, next);
  }
  revalidatePath(next);
  return { ok: true };
}

export async function recordPaymentAction(_prev: ActionState, fd: FormData): Promise<ActionState> {
  const sessionId = text(fd, "sessionId");
  const next = `/dashboard/orders/${sessionId}`;
  const amountMinor = toMinor(text(fd, "amount"));
  if (Number.isNaN(amountMinor) || amountMinor <= 0) return { fieldErrors: { amount: "Enter the amount received." } };
  try {
    await apiFetch(`/restaurant/bills/${encodeURIComponent(text(fd, "billId"))}/payments`, {
      method: "POST",
      token: await token(next),
      body: JSON.stringify({ method: text(fd, "method"), amountMinor, reference: text(fd, "reference") }),
    });
  } catch (e) {
    return failure(e, next);
  }
  revalidatePath(next);
  return { ok: true };
}

export async function decideGuestOrderAction(fd: FormData) {
  const back = text(fd, "back") || "/dashboard/floor";
  const decision = text(fd, "decision") === "accept" ? "accept" : "reject";
  try {
    await apiFetch(`/restaurant/guest-orders/${encodeURIComponent(text(fd, "id"))}/${decision}`, {
      method: "POST",
      token: await token(back),
    });
  } catch (e) {
    if (e instanceof ApiError && e.problem.status === 401) redirect(`/auth/refresh?next=${encodeURIComponent(back)}`);
  }
  revalidatePath("/dashboard/floor");
}

export async function regenerateQrAction(fd: FormData) {
  const tableId = text(fd, "tableId");
  const back = text(fd, "back");
  try {
    await apiFetch(`/restaurant/tables/${encodeURIComponent(tableId)}/qr/regenerate`, { method: "POST", token: await token(back) });
  } catch (e) {
    if (e instanceof ApiError && e.problem.status === 401) redirect(`/auth/refresh?next=${encodeURIComponent(back)}`);
  }
  revalidatePath("/dashboard/tables");
}

export async function createIngredientAction(_prev: ActionState, fd: FormData): Promise<ActionState> {
  const name = text(fd, "name");
  const threshold = Number(text(fd, "threshold") || "0");
  if (!name) return { fieldErrors: { name: "Enter an ingredient name." } };
  if (!Number.isFinite(threshold) || threshold < 0) return { fieldErrors: { threshold: "Enter 0 or more." } };
  try {
    await apiFetch("/restaurant/ingredients", {
      method: "POST",
      token: await token("/dashboard/stock"),
      body: JSON.stringify({ name, unit: text(fd, "unit"), lowStockThreshold: threshold }),
    });
  } catch (e) {
    return failure(e, "/dashboard/stock");
  }
  revalidatePath("/dashboard/stock");
  return { ok: true };
}

export async function recordStockAction(_prev: ActionState, fd: FormData): Promise<ActionState> {
  const branchId = text(fd, "branchId");
  const next = `/dashboard/stock?branch=${branchId}`;
  const quantity = Number(text(fd, "quantity"));
  if (!Number.isFinite(quantity) || quantity === 0) return { fieldErrors: { quantity: "Enter a quantity other than zero." } };
  try {
    await apiFetch(`/restaurant/branches/${encodeURIComponent(branchId)}/stock/movements`, {
      method: "POST",
      token: await token(next),
      body: JSON.stringify({ ingredientId: text(fd, "ingredientId"), reason: text(fd, "reason"), quantity, note: text(fd, "note") }),
    });
  } catch (e) {
    return failure(e, next);
  }
  revalidatePath("/dashboard/stock");
  return { ok: true };
}

export async function loadRecipeAction(menuItemId: string): Promise<RecipeLine[]> {
  return apiFetch<RecipeLine[]>(`/restaurant/menu/items/${encodeURIComponent(menuItemId)}/recipe`, {
    token: await token("/dashboard/menu"),
  });
}

export async function saveRecipeAction(menuItemId: string, lines: { ingredientId: string; quantity: number }[]): Promise<ActionState> {
  try {
    await apiFetch(`/restaurant/menu/items/${encodeURIComponent(menuItemId)}/recipe`, {
      method: "PUT",
      token: await token("/dashboard/menu"),
      body: JSON.stringify({ lines }),
    });
  } catch (e) {
    return failure(e, "/dashboard/menu");
  }
  return { ok: true };
}

export interface AlertsResult {
  branchId: string;
  branchName: string;
  alerts: AlertView[];
}

export async function fetchAlertsAction(): Promise<AlertsResult | null> {
  const t = await getAccessToken();
  if (!t) return null;
  try {
    const me = await apiFetch<Me>("/me", { token: t });
    if (!me.permissions.includes("restaurant.order.take")) return null;
    const list = await apiFetch<BranchList>("/branches", { token: t });
    const results = await Promise.all(
      list.items
        .filter((b) => b.status === "active")
        .map(async (b) => ({ branch: b, alerts: await apiFetch<AlertView[]>(`/restaurant/branches/${b.id}/alerts`, { token: t }) })),
    );
    const first = results[0];
    if (!first) return null;
    return {
      branchId: first.branch.id,
      branchName: results.length > 1 ? "All branches" : first.branch.name,
      alerts: results.flatMap((r) => r.alerts).sort((a, b) => a.at.localeCompare(b.at)),
    };
  } catch {
    return null;
  }
}

export async function resolveRequestAction(id: string) {
  const t = await getAccessToken();
  if (!t) return;
  await apiFetch(`/restaurant/service-requests/${encodeURIComponent(id)}/done`, { method: "POST", token: t }).catch(() => undefined);
  revalidatePath("/dashboard/floor");
}

export async function resolveRequestFormAction(fd: FormData) {
  await resolveRequestAction(text(fd, "id"));
}

export async function assignWaiterAction(tableId: string, membershipId: string | null): Promise<ActionState> {
  try {
    await apiFetch(`/restaurant/tables/${encodeURIComponent(tableId)}/waiter`, {
      method: "PUT",
      token: await token("/dashboard/floor"),
      body: JSON.stringify({ membershipId }),
    });
  } catch (e) {
    return failure(e, "/dashboard/floor");
  }
  revalidatePath("/dashboard/floor");
  return { ok: true };
}
