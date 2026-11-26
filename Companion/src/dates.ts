// The ledger's days are local day indexes: days since 1970-01-01 in the computer's own time zone. A day index maps
// to a calendar date with UTC arithmetic (no time zone involved once the index exists).

const DAY_MS = 86_400_000;

export function todayIndex(now = new Date()): number {
  return Math.floor((now.getTime() - now.getTimezoneOffset() * 60_000) / DAY_MS);
}

export interface Ymd {
  y: number;
  m: number; // 1–12
  d: number;
  w: number; // 0 = Sunday
}

export function ymd(day: number): Ymd {
  const date = new Date(day * DAY_MS);
  return { y: date.getUTCFullYear(), m: date.getUTCMonth() + 1, d: date.getUTCDate(), w: date.getUTCDay() };
}

export function dayOf(y: number, m: number, d: number): number {
  return Math.round(Date.UTC(y, m - 1, d) / DAY_MS);
}

export const daysInMonth = (y: number, m: number): number => new Date(Date.UTC(y, m, 0)).getUTCDate();

export const iso = (day: number): string => {
  const { y, m, d } = ymd(day);
  return `${y}-${String(m).padStart(2, "0")}-${String(d).padStart(2, "0")}`;
};
