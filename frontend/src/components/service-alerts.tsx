"use client";

import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import { fetchAlertsAction, resolveRequestAction, type AlertsResult } from "@/app/actions/restaurant";
import type { AlertView } from "@/lib/api";

const LABEL: Record<AlertView["kind"], string> = {
  guest_order: "New guest order",
  ready: "Ready to serve",
  call_waiter: "Calling a waiter",
  request_bill: "Asking for the bill",
};

const SOUND_KEY = "miseva.alertSound";

function chime() {
  try {
    const ctx = new AudioContext();
    [880, 1320].forEach((freq, i) => {
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.frequency.value = freq;
      gain.gain.setValueAtTime(0.0001, ctx.currentTime + i * 0.18);
      gain.gain.exponentialRampToValueAtTime(0.25, ctx.currentTime + i * 0.18 + 0.02);
      gain.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + i * 0.18 + 0.3);
      osc.connect(gain).connect(ctx.destination);
      osc.start(ctx.currentTime + i * 0.18);
      osc.stop(ctx.currentTime + i * 0.18 + 0.32);
    });
  } catch {
    // audio unavailable
  }
}

export function ServiceAlerts() {
  const [data, setData] = useState<AlertsResult | null>(null);
  const [open, setOpen] = useState(false);
  const [sound, setSound] = useState(() => {
    if (typeof window === "undefined") return false;
    try {
      return localStorage.getItem(SOUND_KEY) === "on";
    } catch {
      return false;
    }
  });
  const [fresh, setFresh] = useState(0);
  const seen = useRef<Set<string> | null>(null);

  const poll = useCallback(async () => {
    const result = await fetchAlertsAction();
    setData(result);
    if (!result) return;
    const ids = new Set(result.alerts.map((a) => `${a.kind}:${a.id}`));
    if (seen.current) {
      const added = [...ids].filter((id) => !seen.current!.has(id)).length;
      if (added > 0) {
        setFresh((n) => n + added);
        if (sound) chime();
      }
    }
    seen.current = ids;
  }, [sound]);

  useEffect(() => {
    const first = setTimeout(poll, 0);
    const id = setInterval(poll, 8000);
    return () => {
      clearTimeout(first);
      clearInterval(id);
    };
  }, [poll]);

  if (!data) return null;
  const count = data.alerts.length;

  function toggleSound() {
    const next = !sound;
    setSound(next);
    try {
      localStorage.setItem(SOUND_KEY, next ? "on" : "off");
    } catch {
      // storage unavailable
    }
    if (next) chime();
  }

  return (
    <div className="relative">
      <button
        type="button"
        onClick={() => { setOpen(!open); setFresh(0); }}
        aria-expanded={open}
        aria-label={`Alerts: ${count}`}
        className={`relative rounded-lg border px-3 py-1.5 text-sm transition focus:outline-none focus-visible:ring-2 focus-visible:ring-gold ${
          fresh > 0 ? "animate-pulse border-gold bg-gold text-obsidian" : "border-gold/50 text-champagne hover:bg-emerald"
        }`}
      >
        Alerts
        {count > 0 ? (
          <span className="ml-2 rounded-full bg-ivory px-1.5 py-0.5 text-xs font-semibold text-obsidian">{count}</span>
        ) : null}
      </button>
      {open ? (
        <div
          role="dialog"
          aria-label="Alerts"
          className="absolute right-0 z-50 mt-2 w-[min(92vw,22rem)] rounded-2xl border border-obsidian/10 bg-ivory p-4 text-obsidian shadow-2xl"
        >
          <div className="flex items-center justify-between gap-2">
            <p className="font-serif text-lg">{data.branchName}</p>
            <button type="button" onClick={toggleSound} className="rounded-lg border border-obsidian/20 px-2.5 py-1 text-xs hover:bg-champagne">
              {sound ? "Sound on" : "Enable sound"}
            </button>
          </div>
          {count === 0 ? <p className="py-6 text-center text-sm text-obsidian/60">All caught up.</p> : null}
          <ul className="mt-3 max-h-[60vh] space-y-2 overflow-y-auto">
            {data.alerts.map((a) => (
              <li key={`${a.kind}:${a.id}`} className={`rounded-xl border p-3 text-sm ${a.mine ? "border-gold bg-white" : "border-obsidian/10 bg-white/60"}`}>
                <div className="flex items-start justify-between gap-2">
                  <p>
                    <span className="mr-2 rounded bg-obsidian px-1.5 py-0.5 text-xs font-medium text-ivory">{a.tableCode}</span>
                    <span className="font-medium">{LABEL[a.kind]}</span>
                    {a.mine ? <span className="ml-1 text-xs text-emerald">· your table</span> : null}
                  </p>
                  <span className="shrink-0 text-xs text-obsidian/50">
                    {new Date(a.at).toLocaleTimeString("en-IN", { hour: "2-digit", minute: "2-digit" })}
                  </span>
                </div>
                {a.detail ? <p className="mt-1 text-obsidian/70">{a.detail}</p> : null}
                <div className="mt-2 flex gap-2">
                  {a.kind === "call_waiter" || a.kind === "request_bill" ? (
                    <button type="button" onClick={async () => { await resolveRequestAction(a.id); poll(); }}
                      className="rounded-lg bg-obsidian px-3 py-1 text-xs font-medium text-ivory hover:bg-emerald">
                      Done
                    </button>
                  ) : null}
                  <Link href={a.kind === "ready" ? "/dashboard/kitchen" : "/dashboard/floor"} onClick={() => setOpen(false)}
                    className="rounded-lg border border-obsidian/20 px-3 py-1 text-xs hover:bg-champagne">
                    {a.kind === "ready" ? "Open kitchen" : "Open floor"}
                  </Link>
                </div>
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </div>
  );
}
