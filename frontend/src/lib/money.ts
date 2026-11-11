export function formatMoney(minor: number, currency: string) {
  return new Intl.NumberFormat("en-IN", { style: "currency", currency }).format(minor / 100);
}

export function toMinor(input: string) {
  const value = Number(input.replace(/,/g, "").trim());
  return Number.isFinite(value) && value >= 0 ? Math.round(value * 100) : NaN;
}

export function percentToBp(input: string) {
  const value = Number(input.trim() || "0");
  return Number.isFinite(value) && value >= 0 && value <= 100 ? Math.round(value * 100) : NaN;
}
