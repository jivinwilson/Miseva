const API_BASE = process.env.API_BASE_URL ?? "http://localhost:8080";

export interface ProblemDetail {
  status: number;
  title: string;
  detail?: string;
  code?: string;
  traceId?: string;
  errors?: { field: string; message: string }[];
  businesses?: BusinessRef[];
}

export interface BusinessRef {
  businessId: string;
  name: string;
  slug: string;
}

export class ApiError extends Error {
  constructor(public readonly problem: ProblemDetail) {
    super(problem.detail ?? problem.title);
  }
}

export async function apiFetch<T>(path: string, init?: RequestInit & { token?: string }): Promise<T> {
  const { token, headers, ...rest } = init ?? {};
  const res = await fetch(`${API_BASE}/api/v1${path}`, {
    ...rest,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...headers,
    },
    cache: "no-store",
  });
  if (!res.ok) {
    const problem = (await res.json().catch(() => null)) as ProblemDetail | null;
    throw new ApiError(problem ?? { status: res.status, title: res.statusText });
  }
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

export interface HealthResponse {
  status: string;
  service: string;
  env: string;
}

export interface TokenPair {
  accessToken: string;
  refreshToken: string;
  expiresInSeconds: number;
}

export interface Registration {
  businessId: string;
  slug: string;
  headOfficeBranchId: string;
  tokens: TokenPair;
}

export interface Me {
  userId: string;
  businessId: string | null;
  permissions: string[];
}

export interface BusinessSummary {
  id: string;
  name: string;
  slug: string;
  industry: string;
  country: string;
  currency: string;
  timezone: string;
  status: string;
}

export interface BranchView {
  id: string;
  code: string;
  name: string;
  status: string;
  headOffice: boolean;
  addressLine1?: string;
  addressLine2?: string;
  city?: string;
  region?: string;
  postalCode?: string;
  countryCode: string;
  timezone: string;
  version: number;
}

export interface BranchList {
  items: BranchView[];
  maxBranches?: number;
  used: number;
  restricted: boolean;
}

export interface RoleView {
  id: string;
  code: string;
  name: string;
  system: boolean;
}

export interface EmployeeView {
  membershipId: string;
  status: "active" | "invited" | "suspended";
  email: string;
  firstName?: string;
  lastName?: string;
  jobTitle?: string;
  employeeCode?: string;
  allBranches: boolean;
  invitationExpiresAt?: string;
  roles: string[];
  branches: string[];
}

export interface InviteResult {
  membershipId: string;
  email: string;
  expiresAt: string;
  inviteUrl?: string;
}

export interface InvitationPreview {
  businessName: string;
  email: string;
  firstName?: string;
  existingAccount: boolean;
}

export const getHealth = () => apiFetch<HealthResponse>("/health");

export interface TableView {
  id: string;
  code: string;
  seats: number;
  area?: string;
  active: boolean;
  sessionId?: string;
  sessionStatus?: "open" | "billed";
  guests?: number;
  openedAt?: string;
  openLines: number;
  amountMinor: number;
}

export interface MenuCategory {
  id: string;
  name: string;
  sortOrder: number;
  active: boolean;
}

export interface MenuItem {
  id: string;
  categoryId: string;
  name: string;
  description?: string;
  priceMinor: number;
  taxRateBp: number;
  available: boolean;
  sortOrder: number;
}

export interface Menu {
  categories: MenuCategory[];
  items: MenuItem[];
}

export type LineStatus = "new" | "preparing" | "ready" | "served" | "cancelled";

export interface OrderLine {
  id: string;
  sessionId: string;
  branchId: string;
  tableCode: string;
  itemName: string;
  unitPriceMinor: number;
  taxRateBp: number;
  quantity: number;
  note?: string;
  status: LineStatus;
  createdAt: string;
  statusChangedAt: string;
}

export interface SessionView {
  session: {
    id: string;
    branchId: string;
    tableId: string;
    tableCode: string;
    status: "open" | "billed" | "closed";
    guests: number;
    openedAt: string;
    closedAt?: string;
  };
  lines: OrderLine[];
  subtotalMinor: number;
  taxMinor: number;
  totalMinor: number;
  currency: string;
  bill?: {
    bill: {
      id: string;
      billNumber: string;
      currency: string;
      subtotalMinor: number;
      taxMinor: number;
      totalMinor: number;
      status: "issued" | "paid";
      issuedAt: string;
      paidAt?: string;
    };
    lines: { itemName: string; quantity: number; unitPriceMinor: number; taxRateBp: number; lineSubtotal: number; lineTax: number }[];
    payments: { id: string; method: string; amountMinor: number; reference?: string; receivedAt: string }[];
    paidMinor: number;
    dueMinor: number;
  };
}
