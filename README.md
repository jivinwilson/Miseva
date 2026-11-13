# MISEVA

Everything in Place. Every Service in Control.

Restaurant operations SaaS, built restaurant-first as a modular monolith: Spring Boot 4 (Java 17) backend, Next.js 16 frontend, PostgreSQL 17 (`misva`) with row-level security per business.

## Status
Slice 0 (base): business registration (restaurant only), sign-in with rotating refresh tokens, branches with plan limits, staff invitations with roles and branch access, per-business data isolation.
Slice 1 (dine-in core): tables per branch, a business-wide menu (prices in minor units, per-item tax in basis points), table sessions with order lines that snapshot price and tax, a kitchen screen (new → preparing → ready → served, refreshes every 5 seconds), bills with per-line tax and split manual payments (cash, card, UPI, other). Bills, bill lines and payments are append-only for the application role.
Slice 2 (QR ordering): every table has a regenerable QR code (`/t/<token>`); guests see the menu, build a cart and send an order without signing in; staff accept or decline it from the Floor page, and accepted orders open the table or join its open order and go to the kitchen. At most 3 pending guest orders per table.
Next: slice 3 (recipes and ingredient stock).

Note for this machine: Windows Smart App Control blocks PostgreSQL's unsigned `citext` extension, so emails are stored lowercase as plain text with a unique index on `lower(email)` (migration V0009).

## Local setup
1. `powershell -ExecutionPolicy Bypass -File scripts\db-init.ps1` creates the `misva` database, the `misva_owner` (migrations) and `misva_app` (runtime) roles, and a git-ignored `.env`.
2. Backend: `cd backend` then `gradlew.bat bootRun` (set `SERVER_PORT=8081` if 8080 is taken). Flyway applies `database/migrations` on start.
3. Frontend: `cd frontend`, create `.env.local` with `API_BASE_URL=http://localhost:8081`, then `npm install` and `npm run dev`.

Tests: `cd backend` then `gradlew.bat test` (runs against the local `misva` database and cleans up after itself).

Email delivery is not built. With `INVITATIONS_EXPOSE_LINK=true` (local only) the invitation link is shown to the person who sent it.
