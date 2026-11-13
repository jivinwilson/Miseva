"use server";

import { ApiError, apiFetch, type GuestOrderStatus } from "@/lib/api";

export interface GuestCartLine {
  menuItemId: string;
  quantity: number;
  note?: string;
}

export interface GuestSubmitResult {
  order?: GuestOrderStatus;
  error?: string;
}

const TOKEN = /^[0-9a-f]{64}$/;

export async function submitGuestOrder(token: string, guests: number, note: string, lines: GuestCartLine[]): Promise<GuestSubmitResult> {
  if (!TOKEN.test(token)) return { error: "This table code is not valid." };
  if (lines.length === 0) return { error: "Add at least one item." };
  try {
    const order = await apiFetch<GuestOrderStatus>(`/public/tables/${token}/orders`, {
      method: "POST",
      body: JSON.stringify({ guests, note, lines }),
    });
    return { order };
  } catch (e) {
    return { error: e instanceof ApiError ? e.message : "We could not send your order. Please ask a member of staff." };
  }
}

export async function guestOrderStatus(token: string, orderId: string): Promise<GuestOrderStatus | null> {
  if (!TOKEN.test(token)) return null;
  try {
    return await apiFetch<GuestOrderStatus>(`/public/tables/${token}/orders/${encodeURIComponent(orderId)}`);
  } catch {
    return null;
  }
}
