"use client";

import { useEffect, useState, useTransition } from "react";
import { guestOrderStatus, submitGuestOrder } from "@/app/actions/guest";
import type { GuestOrderStatus, PublicMenu } from "@/lib/api";
import { formatMoney } from "@/lib/money";

const STATUS_TEXT: Record<GuestOrderStatus["status"], { title: string; body: string }> = {
  pending: { title: "Order sent", body: "A member of staff will confirm it shortly." },
  accepted: { title: "Order confirmed", body: "Your order is with the kitchen." },
  rejected: { title: "Order not accepted", body: "Please ask a member of staff for help." },
};

export function GuestMenu({ token, menu }: { token: string; menu: PublicMenu }) {
  const [cart, setCart] = useState<Record<string, number>>({});
  const [guests, setGuests] = useState(2);
  const [note, setNote] = useState("");
  const [error, setError] = useState<string>();
  const [order, setOrder] = useState<GuestOrderStatus>();
  const [pending, startTransition] = useTransition();

  useEffect(() => {
    if (!order || order.status !== "pending") return;
    const id = setInterval(async () => {
      const next = await guestOrderStatus(token, order.id);
      if (next) setOrder(next);
    }, 5000);
    return () => clearInterval(id);
  }, [order, token]);

  const money = (m: number) => formatMoney(m, menu.currency);
  const lines = Object.entries(cart).filter(([, q]) => q > 0);
  const count = lines.reduce((n, [, q]) => n + q, 0);
  const subtotal = lines.reduce((sum, [id, q]) => sum + (menu.items.find((i) => i.id === id)?.priceMinor ?? 0) * q, 0);

  function change(id: string, delta: number) {
    setCart((c) => ({ ...c, [id]: Math.min(20, Math.max(0, (c[id] ?? 0) + delta)) }));
  }

  function send() {
    setError(undefined);
    startTransition(async () => {
      const result = await submitGuestOrder(token, guests, note, lines.map(([menuItemId, quantity]) => ({ menuItemId, quantity })));
      if (result.order) {
        setOrder(result.order);
        setCart({});
        setNote("");
      } else {
        setError(result.error);
      }
    });
  }

  if (order) {
    const text = STATUS_TEXT[order.status];
    return (
      <div role="status" className="rounded-2xl border border-gold/50 bg-white p-6 text-center shadow-sm">
        <p className="font-serif text-2xl text-obsidian">{text.title}</p>
        <p className="mt-2 text-obsidian/70">{text.body}</p>
        <button
          type="button"
          onClick={() => setOrder(undefined)}
          className="mt-6 rounded-lg border border-obsidian/20 px-4 py-2.5 text-sm text-obsidian transition hover:bg-champagne focus:outline-none focus-visible:ring-2 focus-visible:ring-gold"
        >
          Order something else
        </button>
      </div>
    );
  }

  return (
    <div className="space-y-6 pb-40">
      {menu.categories.map((c) => {
        const items = menu.items.filter((i) => i.categoryId === c.id);
        if (items.length === 0) return null;
        return (
          <section key={c.id} aria-labelledby={`c-${c.id}`}>
            <h2 id={`c-${c.id}`} className="mb-3 font-serif text-xl text-obsidian">{c.name}</h2>
            <ul className="divide-y divide-obsidian/10 rounded-2xl border border-obsidian/10 bg-white">
              {items.map((i) => {
                const q = cart[i.id] ?? 0;
                return (
                  <li key={i.id} className="flex items-center justify-between gap-3 px-4 py-3">
                    <div className="min-w-0">
                      <p className="font-medium text-obsidian">{i.name}</p>
                      {i.description ? <p className="text-sm text-obsidian/60">{i.description}</p> : null}
                      <p className="mt-0.5 text-sm tabular-nums text-obsidian">{money(i.priceMinor)}</p>
                    </div>
                    <div className="flex shrink-0 items-center gap-2">
                      {q > 0 ? (
                        <>
                          <button type="button" aria-label={`Remove one ${i.name}`} onClick={() => change(i.id, -1)}
                            className="h-9 w-9 rounded-full border border-obsidian/20 text-lg text-obsidian">−</button>
                          <span className="w-5 text-center tabular-nums" aria-live="polite">{q}</span>
                        </>
                      ) : null}
                      <button type="button" aria-label={`Add one ${i.name}`} onClick={() => change(i.id, 1)}
                        className="h-9 w-9 rounded-full bg-obsidian text-lg text-ivory">+</button>
                    </div>
                  </li>
                );
              })}
            </ul>
          </section>
        );
      })}

      {menu.items.length === 0 ? <p className="text-center text-obsidian/70">The menu is not available right now.</p> : null}

      {count > 0 ? (
        <div className="fixed inset-x-0 bottom-0 border-t border-obsidian/10 bg-ivory/95 px-4 pb-[max(1rem,env(safe-area-inset-bottom))] pt-4 shadow-2xl backdrop-blur">
          <div className="mx-auto max-w-lg space-y-3">
            {error ? <p role="alert" className="text-sm text-red-800">{error}</p> : null}
            <div className="flex gap-3">
              <label className="text-sm text-obsidian">
                Guests
                <input type="number" min={1} max={50} value={guests} onChange={(e) => setGuests(Number(e.target.value) || 1)}
                  className="mt-1 block w-20 rounded-lg border border-obsidian/20 bg-white px-3 py-2 text-base" />
              </label>
              <label className="flex-1 text-sm text-obsidian">
                Note for the kitchen
                <input value={note} maxLength={300} onChange={(e) => setNote(e.target.value)} placeholder="Allergies, spice level…"
                  className="mt-1 block w-full rounded-lg border border-obsidian/20 bg-white px-3 py-2 text-base" />
              </label>
            </div>
            <button type="button" onClick={send} disabled={pending}
              className="flex w-full items-center justify-between rounded-lg bg-obsidian px-4 py-3 font-medium text-ivory disabled:opacity-60">
              <span>{pending ? "Sending…" : `Send order · ${count} ${count === 1 ? "item" : "items"}`}</span>
              <span className="tabular-nums">{money(subtotal)}</span>
            </button>
            <p className="text-center text-xs text-obsidian/60">Prices exclude tax. Staff confirm every order.</p>
          </div>
        </div>
      ) : null}
    </div>
  );
}
