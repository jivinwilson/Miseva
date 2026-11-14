"use client";

import { useState, useTransition } from "react";
import { assignWaiterAction } from "@/app/actions/restaurant";
import type { Waiter } from "@/lib/api";

export function WaiterSelect({ tableId, tableCode, current, waiters }: {
  tableId: string;
  tableCode: string;
  current?: string;
  waiters: Waiter[];
}) {
  const [value, setValue] = useState(current ?? "");
  const [error, setError] = useState<string>();
  const [pending, start] = useTransition();

  return (
    <div className="mt-3">
      <label className="text-xs text-obsidian/60">
        Waiter
        <select
          value={value}
          disabled={pending}
          aria-label={`Waiter for table ${tableCode}`}
          onChange={(e) => {
            const next = e.target.value;
            setValue(next);
            start(async () => {
              const result = await assignWaiterAction(tableId, next || null);
              setError(result.ok ? undefined : result.error);
            });
          }}
          className="mt-1 block w-full rounded-lg border border-obsidian/20 bg-white px-2.5 py-1.5 text-sm text-obsidian"
        >
          <option value="">Anyone</option>
          {waiters.map((w) => (
            <option key={w.membershipId} value={w.membershipId}>{w.name}</option>
          ))}
        </select>
      </label>
      {error ? <p role="alert" className="mt-1 text-xs text-red-700">{error}</p> : null}
    </div>
  );
}
