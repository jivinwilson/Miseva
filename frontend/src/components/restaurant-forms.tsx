"use client";

import * as Dialog from "@radix-ui/react-dialog";
import { useRouter } from "next/navigation";
import { useActionState, useEffect, useState, type ReactNode } from "react";
import {
  addLineAction,
  createCategoryAction,
  createTableAction,
  issueBillAction,
  openTableAction,
  recordPaymentAction,
  saveItemAction,
  type ActionState,
} from "@/app/actions/restaurant";
import { Field, FormError, SelectField, SubmitButton } from "@/components/form-controls";
import type { BranchView, MenuCategory, MenuItem } from "@/lib/api";

const initial: ActionState = {};
const overlay = "fixed inset-0 z-40 bg-obsidian/60 backdrop-blur-sm";
const content =
  "fixed left-1/2 top-1/2 z-50 max-h-[90vh] w-[min(92vw,32rem)] -translate-x-1/2 -translate-y-1/2 overflow-y-auto rounded-2xl bg-ivory p-6 shadow-2xl focus:outline-none";
const cancelButton =
  "rounded-lg border border-obsidian/20 px-4 py-2.5 text-sm text-obsidian transition hover:bg-champagne focus:outline-none focus-visible:ring-2 focus-visible:ring-gold";

function FormDialog({ trigger, triggerClassName, title, children }: {
  trigger: string;
  triggerClassName: string;
  title: string;
  children: (close: () => void) => ReactNode;
}) {
  const [open, setOpen] = useState(false);
  const [key, setKey] = useState(0);
  return (
    <Dialog.Root
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (next) setKey((k) => k + 1);
      }}
    >
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

function useCloseOnOk(state: ActionState, close: () => void) {
  useEffect(() => {
    if (state.ok) close();
  }, [state.ok, close]);
}

function Actions({ label, pending }: { label: string; pending: string }) {
  return (
    <div className="flex justify-end gap-3 pt-2">
      <Dialog.Close className={cancelButton}>Cancel</Dialog.Close>
      <div className="w-40">
        <SubmitButton pendingLabel={pending}>{label}</SubmitButton>
      </div>
    </div>
  );
}

export function BranchPicker({ branches, current, basePath }: { branches: BranchView[]; current: string; basePath: string }) {
  const router = useRouter();
  if (branches.length < 2) return null;
  return (
    <SelectField
      label="Branch"
      className="w-56"
      value={current}
      onChange={(e) => router.push(`${basePath}?branch=${e.target.value}`)}
    >
      {branches.map((b) => (
        <option key={b.id} value={b.id}>{b.name}</option>
      ))}
    </SelectField>
  );
}

export function AutoRefresh({ seconds }: { seconds: number }) {
  const router = useRouter();
  useEffect(() => {
    const id = setInterval(() => router.refresh(), seconds * 1000);
    return () => clearInterval(id);
  }, [router, seconds]);
  return null;
}

export function CategoryDialog({ triggerClassName }: { triggerClassName: string }) {
  return (
    <FormDialog trigger="Add category" triggerClassName={triggerClassName} title="Add a category">
      {(close) => <CategoryForm close={close} />}
    </FormDialog>
  );
}

function CategoryForm({ close }: { close: () => void }) {
  const [state, action] = useActionState(createCategoryAction, initial);
  useCloseOnOk(state, close);
  return (
    <form action={action} className="mt-6 space-y-4" noValidate>
      <FormError message={state.error} />
      <Field label="Name" name="name" required placeholder="Starters" error={state.fieldErrors?.name} />
      <Field label="Position" name="sortOrder" type="number" min={0} defaultValue={0} hint="Lower numbers show first." />
      <Actions label="Add category" pending="Adding…" />
    </form>
  );
}

export function ItemDialog({ categories, item, triggerLabel, triggerClassName }: {
  categories: MenuCategory[];
  item?: MenuItem;
  triggerLabel: string;
  triggerClassName: string;
}) {
  return (
    <FormDialog trigger={triggerLabel} triggerClassName={triggerClassName} title={item ? `Edit ${item.name}` : "Add a menu item"}>
      {(close) => <ItemForm categories={categories} item={item} close={close} />}
    </FormDialog>
  );
}

function ItemForm({ categories, item, close }: { categories: MenuCategory[]; item?: MenuItem; close: () => void }) {
  const [state, action] = useActionState(saveItemAction, initial);
  const e = state.fieldErrors ?? {};
  useCloseOnOk(state, close);
  return (
    <form action={action} className="mt-6 space-y-4" noValidate>
      <FormError message={state.error} />
      {item ? <input type="hidden" name="id" value={item.id} /> : null}
      <Field label="Name" name="name" required defaultValue={item?.name} error={e.name} />
      <SelectField label="Category" name="categoryId" defaultValue={item?.categoryId ?? categories[0]?.id} error={e.categoryId}>
        {categories.map((c) => (
          <option key={c.id} value={c.id}>{c.name}</option>
        ))}
      </SelectField>
      <Field label="Description" name="description" defaultValue={item?.description} />
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Price" name="price" inputMode="decimal" required
          defaultValue={item ? (item.priceMinor / 100).toFixed(2) : ""} error={e.price} hint="Before tax." />
        <Field label="Tax %" name="tax" inputMode="decimal" defaultValue={item ? String(item.taxRateBp / 100) : "5"} error={e.tax} />
      </div>
      <label className="flex items-center gap-3 text-sm">
        <input type="checkbox" name="available" defaultChecked={item?.available ?? true} className="accent-emerald" />
        Available to order
      </label>
      <Actions label={item ? "Save item" : "Add item"} pending="Saving…" />
    </form>
  );
}

export function TableDialog({ branchId, triggerClassName }: { branchId: string; triggerClassName: string }) {
  return (
    <FormDialog trigger="Add table" triggerClassName={triggerClassName} title="Add a table">
      {(close) => <TableForm branchId={branchId} close={close} />}
    </FormDialog>
  );
}

function TableForm({ branchId, close }: { branchId: string; close: () => void }) {
  const [state, action] = useActionState(createTableAction, initial);
  const e = state.fieldErrors ?? {};
  useCloseOnOk(state, close);
  return (
    <form action={action} className="mt-6 space-y-4" noValidate>
      <FormError message={state.error} />
      <input type="hidden" name="branchId" value={branchId} />
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Table code" name="code" required maxLength={10} placeholder="T1" error={e.code} />
        <Field label="Seats" name="seats" type="number" min={1} max={100} defaultValue={4} error={e.seats} />
      </div>
      <Field label="Area" name="area" placeholder="Terrace" error={e.area} />
      <Actions label="Add table" pending="Adding…" />
    </form>
  );
}

export function OpenTableForm({ tableId }: { tableId: string }) {
  const [state, action] = useActionState(openTableAction, initial);
  return (
    <form action={action} className="mt-3 flex items-end gap-2">
      <input type="hidden" name="tableId" value={tableId} />
      <Field label="Guests" name="guests" type="number" min={1} max={100} defaultValue={2} className="w-20" />
      <div className="w-28">
        <SubmitButton pendingLabel="Opening…">Open</SubmitButton>
      </div>
      {state.error ? <p role="alert" className="text-xs text-red-700">{state.error}</p> : null}
    </form>
  );
}

export function AddLineForm({ sessionId, items }: { sessionId: string; items: MenuItem[] }) {
  const [state, action] = useActionState(addLineAction, initial);
  return (
    <form action={action} className="grid gap-3 sm:grid-cols-[minmax(0,1fr)_5rem_minmax(0,1fr)_8rem] sm:items-end">
      <input type="hidden" name="sessionId" value={sessionId} />
      <SelectField label="Item" name="menuItemId" required>
        {items.map((i) => (
          <option key={i.id} value={i.id}>{i.name}</option>
        ))}
      </SelectField>
      <Field label="Qty" name="quantity" type="number" min={1} max={999} defaultValue={1} />
      <Field label="Note" name="note" placeholder="No onions" maxLength={200} />
      <SubmitButton pendingLabel="Sending…">Send to kitchen</SubmitButton>
      <div className="sm:col-span-4">
        <FormError message={state.error} />
      </div>
    </form>
  );
}

export function IssueBillButton({ sessionId }: { sessionId: string }) {
  const [state, action] = useActionState(issueBillAction, initial);
  return (
    <form action={action} className="space-y-2">
      <input type="hidden" name="sessionId" value={sessionId} />
      <FormError message={state.error} />
      <SubmitButton pendingLabel="Issuing…">Issue bill</SubmitButton>
    </form>
  );
}

export function PaymentForm({ sessionId, billId, dueMajor }: { sessionId: string; billId: string; dueMajor: string }) {
  const [state, action] = useActionState(recordPaymentAction, initial);
  return (
    <form action={action} key={dueMajor} className="space-y-3">
      <input type="hidden" name="sessionId" value={sessionId} />
      <input type="hidden" name="billId" value={billId} />
      <FormError message={state.error} />
      <div className="grid gap-3 sm:grid-cols-3">
        <SelectField label="Method" name="method" defaultValue="cash">
          <option value="cash">Cash</option>
          <option value="card">Card</option>
          <option value="upi">UPI</option>
          <option value="other">Other</option>
        </SelectField>
        <Field label="Amount" name="amount" inputMode="decimal" defaultValue={dueMajor} error={state.fieldErrors?.amount} />
        <Field label="Reference" name="reference" placeholder="Optional" />
      </div>
      <SubmitButton pendingLabel="Recording…">Record payment</SubmitButton>
    </form>
  );
}
