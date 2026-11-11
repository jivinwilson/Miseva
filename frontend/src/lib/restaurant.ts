import "server-only";
import { redirect } from "next/navigation";
import { ApiError, apiFetch, type BranchList, type Me } from "./api";
import { getAccessToken } from "./session";

export async function loadContext(next: string, requestedBranch?: string) {
  const token = await getAccessToken();
  if (!token) redirect(`/auth/refresh?next=${encodeURIComponent(next)}`);
  try {
    const [me, list] = await Promise.all([
      apiFetch<Me>("/me", { token }),
      apiFetch<BranchList>("/branches", { token }),
    ]);
    const active = list.items.filter((b) => b.status === "active");
    const branch = active.find((b) => b.id === requestedBranch) ?? active[0];
    return { token, me, branches: active, branch };
  } catch (e) {
    if (e instanceof ApiError && e.problem.status === 401) redirect(`/auth/refresh?next=${encodeURIComponent(next)}`);
    throw e;
  }
}

export function single(value: string | string[] | undefined) {
  return Array.isArray(value) ? value[0] : value;
}
