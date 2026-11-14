import type { Metadata } from "next";
import { Suspense } from "react";
import { ApiError, apiFetch, type PublicMenu } from "@/lib/api";
import { GuestMenu, ServiceButtons } from "./guest-menu";

export const metadata: Metadata = { title: "Order at your table", robots: { index: false, follow: false } };

export default function GuestTablePage({ params }: PageProps<"/t/[token]">) {
  return (
    <main className="min-h-screen bg-ivory px-4 py-6">
      <div className="mx-auto max-w-lg">
        <Suspense fallback={<p className="text-obsidian/60">Loading the menu…</p>}>
          <Table params={params} />
        </Suspense>
      </div>
    </main>
  );
}

async function Table({ params }: { params: PageProps<"/t/[token]">["params"] }) {
  const { token } = await params;
  let menu: PublicMenu | undefined;
  if (/^[0-9a-f]{64}$/.test(token)) {
    try {
      menu = await apiFetch<PublicMenu>(`/public/tables/${token}`);
    } catch (e) {
      if (!(e instanceof ApiError)) throw e;
    }
  }
  if (!menu) {
    return (
      <div className="rounded-2xl border border-obsidian/10 bg-white p-6 text-center">
        <p className="font-serif text-2xl text-obsidian">This code is not active</p>
        <p className="mt-2 text-obsidian/70">Please ask a member of staff to take your order.</p>
      </div>
    );
  }
  return (
    <>
      <header className="mb-6">
        <p className="text-xs uppercase tracking-[0.3em] text-gold">{menu.branchName}</p>
        <h1 className="mt-1 font-serif text-3xl text-obsidian">{menu.businessName}</h1>
        <p className="mt-1 text-obsidian/70">Table {menu.tableCode}</p>
      </header>
      <ServiceButtons token={token} />
      <GuestMenu token={token} menu={menu} />
    </>
  );
}
