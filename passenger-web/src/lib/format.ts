

const KRW = new Intl.NumberFormat("ko-KR", { maximumFractionDigits: 0 });

export function formatKrw(n: number): string {
  return `${KRW.format(n)}원`;
}

export function formatInt(n: number): string {
  return KRW.format(n);
}


export function formatKm(value: string | number | null | undefined, maxFraction = 1): string {
  if (value === null || value === undefined || value === "") return "-";
  const n = typeof value === "number" ? value : Number(value);
  if (!Number.isFinite(n)) return "-";
  return new Intl.NumberFormat("ko-KR", { maximumFractionDigits: maxFraction }).format(n);
}


export function kmBetween(startKm: string | null, endKm: string | null): string | null {
  if (startKm === null || endKm === null) return null;
  const a = Number(startKm);
  const b = Number(endKm);
  if (!Number.isFinite(a) || !Number.isFinite(b) || b < a) return null;
  return String(Math.round((b - a) * 1000) / 1000);
}

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return "-";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "-";
  return new Intl.DateTimeFormat("ko-KR", { month: "long", day: "numeric", weekday: "short", hour: "numeric", minute: "2-digit" }).format(d);
}

export function formatDate(iso: string | null | undefined): string {
  if (!iso) return "-";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "-";
  return new Intl.DateTimeFormat("ko-KR", { month: "long", day: "numeric", weekday: "short" }).format(d);
}

export function formatClock(iso: string | number | Date | null | undefined): string {
  if (iso === null || iso === undefined) return "-";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "-";
  return new Intl.DateTimeFormat("ko-KR", { hour: "numeric", minute: "2-digit", second: "2-digit" }).format(d);
}

export function formatAge(seconds: number): string {
  if (seconds < 5) return "방금";
  if (seconds < 60) return `${Math.round(seconds)}초 전`;
  if (seconds < 3600) return `${Math.round(seconds / 60)}분 전`;
  return `${Math.round(seconds / 3600)}시간 전`;
}
