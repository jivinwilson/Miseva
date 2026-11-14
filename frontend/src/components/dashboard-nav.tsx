"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

const LINKS = [
  { href: "/dashboard", label: "Overview" },
  { href: "/dashboard/floor", label: "Floor" },
  { href: "/dashboard/kitchen", label: "Kitchen" },
  { href: "/dashboard/menu", label: "Menu" },
  { href: "/dashboard/stock", label: "Stock" },
  { href: "/dashboard/branches", label: "Branches" },
  { href: "/dashboard/team", label: "Team" },
];

export function DashboardNav() {
  const pathname = usePathname();
  return (
    <nav aria-label="Main" className="-mx-1 flex max-w-full gap-1 overflow-x-auto px-1">
      {LINKS.map((l) => {
        const active = l.href === "/dashboard" ? pathname === l.href : pathname.startsWith(l.href);
        return (
          <Link
            key={l.href}
            href={l.href}
            aria-current={active ? "page" : undefined}
            className={`rounded-lg px-3.5 py-1.5 text-sm transition focus:outline-none focus-visible:ring-2 focus-visible:ring-gold ${
              active ? "bg-emerald text-ivory" : "text-champagne/80 hover:bg-emerald/50 hover:text-ivory"
            }`}
          >
            {l.label}
          </Link>
        );
      })}
    </nav>
  );
}
