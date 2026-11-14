# MISEVA

Everything in Place. Every Service in Control.

Restaurant operations SaaS, built restaurant-first as a modular monolith: Spring Boot 4 (Java 17) backend, Next.js 16 frontend, PostgreSQL 17 (`misva`) with row-level security per business.

## Status
Slice 0 (base): business registration (restaurant only), sign-in with rotating refresh tokens, branches with plan limits, staff invitations with roles and branch access, per-business data isolation.
Slice 1 (dine-in core): tables per branch, a business-wide menu (prices in minor units, per-item tax in basis points), table sessions with order lines that snapshot price and tax, a kitchen screen (new → preparing → ready → served, refreshes every 5 seconds), bills with per-line tax and split manual payments (cash, card, UPI, other). Bills, bill lines and payments are append-only for the application role.
Slice 2 (QR ordering): every table has a regenerable QR code (`/t/<token>`); guests see the menu, build a cart and send an order without signing in; staff accept or decline it from the Floor page, and accepted orders open the table or join its open order and go to the kitchen. At most 3 pending guest orders per table.
Slice 3 (ingredients): ingredients in g, ml or pcs with low-stock thresholds, per-dish recipes, and an append-only stock ledger per branch (purchase, waste, count correction, consumption). Paying a bill deducts recipe ingredients for every non-cancelled item in the same transaction; stock may go negative so shortfalls stay visible.

Note for this machine: Windows Smart App Control blocks PostgreSQL's unsigned `citext` extension, so emails are stored lowercase as plain text with a unique index on `lower(email)` (migration V0009).

Service flow: managers assign a waiter to each table; an Alerts button on every dashboard page (polls every 8 seconds, optional chime) shows new guest orders, food ready to serve, waiter calls and bill requests, targeted to the table's waiter (managers see everything). Guests see each item's progress and can call a waiter or ask for the bill.

## Phones on your Wi-Fi
Set `PUBLIC_APP_URL=http://<this PC's IP>:3001` and `DEV_ALLOWED_HOSTS=<this PC's IP>` in `frontend/.env.local`, restart the frontend, and allow inbound TCP 3001 in Windows Firewall for the private network. QR codes then point to that address.

## Local setup
1. `powershell -ExecutionPolicy Bypass -File scripts\db-init.ps1` creates the `misva` database, the `misva_owner` (migrations) and `misva_app` (runtime) roles, and a git-ignored `.env`.
2. Backend: `cd backend` then `gradlew.bat bootRun` (set `SERVER_PORT=8081` if 8080 is taken). Flyway applies `database/migrations` on start.
3. Frontend: `cd frontend`, create `.env.local` with `API_BASE_URL=http://localhost:8081`, then `npm install` and `npm run dev`.

Tests: `cd backend` then `gradlew.bat test` (runs against the local `misva` database and cleans up after itself).

Email delivery is not built. With `INVITATIONS_EXPOSE_LINK=true` (local only) the invitation link is shown to the person who sent it.
