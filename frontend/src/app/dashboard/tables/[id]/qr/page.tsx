import type { Metadata } from "next";
import Link from "next/link";
import { headers } from "next/headers";
import QRCode from "qrcode";
import { Suspense } from "react";
import { regenerateQrAction } from "@/app/actions/restaurant";
import { PrintButton } from "@/components/print-button";
import { apiFetch, type TableView } from "@/lib/api";
import { loadContext, single } from "@/lib/restaurant";

export const metadata: Metadata = { title: "Table QR · MISEVA" };

export default function TableQrPage({ params, searchParams }: PageProps<"/dashboard/tables/[id]/qr">) {
  return (
    <Suspense fallback={<p className="text-obsidian/60">Loading QR code…</p>}>
      <TableQr params={params} searchParams={searchParams} />
    </Suspense>
  );
}

async function TableQr({ params, searchParams }: {
  params: PageProps<"/dashboard/tables/[id]/qr">["params"];
  searchParams: PageProps<"/dashboard/tables/[id]/qr">["searchParams"];
}) {
  const { id } = await params;
  const requested = single((await searchParams).branch);
  const back = `/dashboard/tables/${id}/qr?branch=${requested ?? ""}`;
  const { token, me, branch } = await loadContext(back, requested);
  const table = branch
    ? (await apiFetch<TableView[]>(`/restaurant/branches/${branch.id}/tables`, { token })).find((t) => t.id === id)
    : undefined;
  if (!branch || !table) {
    return <p role="alert" className="rounded-lg border border-obsidian/15 bg-white px-4 py-3">This table could not be found.</p>;
  }

  const h = await headers();
  const host = h.get("x-forwarded-host") ?? h.get("host");
  const proto = h.get("x-forwarded-proto") ?? (host?.startsWith("localhost") ? "http" : "https");
  const url = `${proto}://${host}/t/${table.qrToken}`;
  const qr = await QRCode.toDataURL(url, { margin: 1, width: 512, errorCorrectionLevel: "M", color: { dark: "#080F14", light: "#FFFFFF" } });

  return (
    <div className="space-y-6">
      <div className="print:hidden">
        <Link href={`/dashboard/floor?branch=${branch.id}`} className="text-sm text-emerald underline underline-offset-4">← Floor</Link>
        <h1 className="mt-2 font-serif text-3xl text-obsidian">QR code for table {table.code}</h1>
        <p className="mt-1 text-obsidian/70">Guests scan this to see the menu and send an order for staff to confirm.</p>
      </div>

      <div className="mx-auto w-full max-w-sm rounded-2xl border border-obsidian/10 bg-white p-8 text-center shadow-sm print:border-0 print:shadow-none">
        <p className="font-serif text-2xl text-obsidian">Table {table.code}</p>
        <p className="mt-1 text-sm text-obsidian/70">Scan to order</p>
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img src={qr} alt={`QR code for table ${table.code}`} width={256} height={256} className="mx-auto mt-4" />
        <p className="mt-4 break-all text-xs text-obsidian/50">{url}</p>
      </div>

      <div className="flex flex-wrap justify-center gap-3 print:hidden">
        <PrintButton />
        {me.permissions.includes("restaurant.table.manage") ? (
          <form action={regenerateQrAction}>
            <input type="hidden" name="tableId" value={table.id} />
            <input type="hidden" name="back" value={back} />
            <button
              type="submit"
              className="rounded-lg border border-obsidian/20 px-4 py-2.5 text-sm text-obsidian transition hover:bg-champagne focus:outline-none focus-visible:ring-2 focus-visible:ring-gold"
            >
              Replace code (old prints stop working)
            </button>
          </form>
        ) : null}
      </div>
    </div>
  );
}
