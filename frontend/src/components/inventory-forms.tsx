"use client";

import * as Dialog from "@radix-ui/react-dialog";
import { useActionState, useEffect, useState, useTransition } from "react";
import {
  createIngredientAction,
  loadRecipeAction,
  recordStockAction,
  saveRecipeAction,
  type ActionState,
} from "@/app/actions/restaurant";
import { Field, FormError, SelectField, SubmitButton } from "@/components/form-controls";
import type { Ingredient } from "@/lib/api";

const initial: ActionState = {};
const overlay = "fixed inset-0 z-40 bg-obsidian/60 backdrop-blur-sm";
const content =
  "fixed left-1/2 top-1/2 z-50 max-h-[90vh] w-[min(92vw,32rem)] -translate-x-1/2 -translate-y-1/2 overflow-y-auto rounded-2xl bg-ivory p-6 shadow-2xl focus:outline-none";
const cancelButton =
  "rounded-lg border border-obsidian/20 px-4 py-2.5 text-sm text-obsidian transition hover:bg-champagne focus:outline-none focus-visible:ring-2 focus-visible:ring-gold";

function Shell({ trigger, triggerClassName, title, children }: {
  trigger: string;
  triggerClassName: string;
  title: string;
  children: (close: () => void) => React.ReactNode;
}) {
  const [open, setOpen] = useState(false);
  const [key, setKey] = useState(0);
  return (
    <Dialog.Root open={open} onOpenChange={(next) => { setOpen(next); if (next) setKey((k) => k + 1); }}>
      <Dialog.Trigger className={triggerClassName}>{trigger}</Dialog.Trigger>
      <Dialog.Portal>
        <Dialog.Overlay className={overlay} />
        <Dialog.Content className={content} aria-describedby={undefined}>
          <Dialog.Title className="font-serif text-2xl text-obsidian">{title}</Dialog.Title>
          <div key={key}>{children(() => setOpen(false))}</div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

function Buttons({ label, pending }: { label: string; pending: string }) {
  return (
    <div className="flex justify-end gap-3 pt-2">
      <Dialog.Close className={cancelButton}>Cancel</Dialog.Close>
      <div className="w-40"><SubmitButton pendingLabel={pending}>{label}</SubmitButton></div>
    </div>
  );
}

export function IngredientDialog({ triggerClassName }: { triggerClassName: string }) {
  return (
    <Shell trigger="Add ingredient" triggerClassName={triggerClassName} title="Add an ingredient">
      {(close) => <IngredientForm close={close} />}
    </Shell>
  );
}

function IngredientForm({ close }: { close: () => void }) {
  const [state, action] = useActionState(createIngredientAction, initial);
  useEffect(() => { if (state.ok) close(); }, [state.ok, close]);
  return (
    <form action={action} className="mt-6 space-y-4" noValidate>
      <FormError message={state.error} />
      <Field label="Name" name="name" required placeholder="Basmati rice" error={state.fieldErrors?.name} />
      <div className="grid gap-4 sm:grid-cols-2">
        <SelectField label="Unit" name="unit" defaultValue="g">
          <option value="g">Grams (g)</option>
          <option value="ml">Millilitres (ml)</option>
          <option value="pcs">Pieces (pcs)</option>
        </SelectField>
        <Field label="Low stock below" name="threshold" inputMode="decimal" defaultValue="0" error={state.fieldErrors?.threshold}
          hint="Same unit. 0 turns the alert off." />
      </div>
      <Buttons label="Add ingredient" pending="Adding…" />
    </form>
  );
}

export function StockDialog({ branchId, ingredients, triggerClassName }: {
  branchId: string;
  ingredients: Ingredient[];
  triggerClassName: string;
}) {
  return (
    <Shell trigger="Record stock" triggerClassName={triggerClassName} title="Record stock">
      {(close) => <StockForm branchId={branchId} ingredients={ingredients} close={close} />}
    </Shell>
  );
}

function StockForm({ branchId, ingredients, close }: { branchId: string; ingredients: Ingredient[]; close: () => void }) {
  const [state, action] = useActionState(recordStockAction, initial);
  const [reason, setReason] = useState("purchase");
  useEffect(() => { if (state.ok) close(); }, [state.ok, close]);
  return (
    <form action={action} className="mt-6 space-y-4" noValidate>
      <FormError message={state.error} />
      <input type="hidden" name="branchId" value={branchId} />
      <SelectField label="Ingredient" name="ingredientId">
        {ingredients.map((i) => <option key={i.id} value={i.id}>{i.name} ({i.unit})</option>)}
      </SelectField>
      <div className="grid gap-4 sm:grid-cols-2">
        <SelectField label="Type" name="reason" value={reason} onChange={(e) => setReason(e.target.value)}>
          <option value="purchase">Purchase (adds)</option>
          <option value="waste">Waste (removes)</option>
          <option value="adjustment">Count correction (+ or −)</option>
        </SelectField>
        <Field label="Quantity" name="quantity" inputMode="decimal" required error={state.fieldErrors?.quantity}
          hint={reason === "adjustment" ? "Use a minus sign to reduce." : undefined} />
      </div>
      <Field label="Note" name="note" placeholder="Supplier invoice, reason…" maxLength={200} />
      <Buttons label="Save" pending="Saving…" />
    </form>
  );
}

export function RecipeDialog({ itemId, itemName, ingredients, canManage, triggerClassName }: {
  itemId: string;
  itemName: string;
  ingredients: Ingredient[];
  canManage: boolean;
  triggerClassName: string;
}) {
  return (
    <Shell trigger="Recipe" triggerClassName={triggerClassName} title={`Recipe: ${itemName}`}>
      {(close) => <RecipeEditor itemId={itemId} ingredients={ingredients} canManage={canManage} close={close} />}
    </Shell>
  );
}

type Row = { ingredientId: string; quantity: string };

function RecipeEditor({ itemId, ingredients, canManage, close }: {
  itemId: string;
  ingredients: Ingredient[];
  canManage: boolean;
  close: () => void;
}) {
  const [rows, setRows] = useState<Row[] | null>(null);
  const [error, setError] = useState<string>();
  const [pending, start] = useTransition();
  const unit = (id: string) => ingredients.find((i) => i.id === id)?.unit ?? "";

  useEffect(() => {
    loadRecipeAction(itemId)
      .then((lines) => setRows(lines.map((l) => ({ ingredientId: l.ingredientId, quantity: String(l.quantity) }))))
      .catch(() => setError("Could not load the recipe."));
  }, [itemId]);

  function save() {
    if (!rows) return;
    const lines = rows.map((r) => ({ ingredientId: r.ingredientId, quantity: Number(r.quantity) }));
    if (lines.some((l) => !(l.quantity > 0))) {
      setError("Every quantity must be more than zero.");
      return;
    }
    setError(undefined);
    start(async () => {
      const result = await saveRecipeAction(itemId, lines);
      if (result.ok) close();
      else setError(result.error ?? Object.values(result.fieldErrors ?? {})[0]);
    });
  }

  if (!rows) return <p className="mt-6 text-sm text-obsidian/60">{error ?? "Loading…"}</p>;

  return (
    <div className="mt-6 space-y-4">
      <p className="text-sm text-obsidian/70">How much of each ingredient one portion uses. Stock is deducted when the bill is paid.</p>
      <FormError message={error} />
      {rows.length === 0 ? <p className="text-sm text-obsidian/60">No ingredients yet.</p> : null}
      <ul className="space-y-2">
        {rows.map((r, i) => (
          <li key={i} className="flex items-end gap-2">
            <SelectField label="Ingredient" className="flex-1" value={r.ingredientId} disabled={!canManage}
              onChange={(e) => setRows(rows.map((x, j) => (j === i ? { ...x, ingredientId: e.target.value } : x)))}>
              {ingredients.map((ing) => <option key={ing.id} value={ing.id}>{ing.name}</option>)}
            </SelectField>
            <Field label={`Per portion (${unit(r.ingredientId)})`} className="w-36" inputMode="decimal" value={r.quantity} disabled={!canManage}
              onChange={(e) => setRows(rows.map((x, j) => (j === i ? { ...x, quantity: e.target.value } : x)))} />
            {canManage ? (
              <button type="button" aria-label="Remove ingredient" onClick={() => setRows(rows.filter((_, j) => j !== i))}
                className="mb-1 h-10 w-10 rounded-lg border border-obsidian/20 text-obsidian hover:bg-champagne">×</button>
            ) : null}
          </li>
        ))}
      </ul>
      {canManage ? (
        <div className="flex flex-wrap justify-between gap-3 pt-2">
          <button type="button" disabled={ingredients.length === 0}
            onClick={() => setRows([...rows, { ingredientId: ingredients[0]?.id ?? "", quantity: "" }])}
            className={cancelButton}>
            Add ingredient
          </button>
          <div className="flex gap-3">
            <Dialog.Close className={cancelButton}>Cancel</Dialog.Close>
            <button type="button" onClick={save} disabled={pending}
              className="rounded-lg bg-obsidian px-5 py-2.5 text-sm font-medium text-ivory transition hover:bg-emerald disabled:opacity-60">
              {pending ? "Saving…" : "Save recipe"}
            </button>
          </div>
        </div>
      ) : null}
      {canManage && ingredients.length === 0 ? (
        <p className="text-sm text-obsidian/60">Add ingredients on the Stock page first.</p>
      ) : null}
    </div>
  );
}
