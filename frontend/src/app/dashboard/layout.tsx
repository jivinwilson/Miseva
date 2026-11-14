import { Suspense } from "react";
import { logoutAction } from "@/app/actions/auth";
import { DashboardNav } from "@/components/dashboard-nav";
import { ServiceAlerts } from "@/components/service-alerts";

export default function DashboardLayout({ children }: LayoutProps<"/dashboard">) {
  return (
    <div className="min-h-screen bg-ivory">
      <header className="flex flex-wrap items-center justify-between gap-3 bg-obsidian px-6 py-3 text-champagne print:hidden">
        <div className="flex min-w-0 flex-wrap items-center gap-x-8 gap-y-2">
          <span className="font-serif text-xl tracking-wide text-ivory">MISEVA</span>
          <Suspense fallback={<div className="h-8" />}>
            <DashboardNav />
          </Suspense>
        </div>
        <div className="flex items-center gap-2">
        <ServiceAlerts />
        <form action={logoutAction}>
          <button
            type="submit"
            className="rounded-lg border border-gold/50 px-4 py-1.5 text-sm text-champagne transition hover:bg-emerald focus:outline-none focus-visible:ring-2 focus-visible:ring-gold"
          >
            Sign out
          </button>
        </form>
        </div>
      </header>
      <main className="mx-auto max-w-5xl px-6 py-10">{children}</main>
    </div>
  );
}
