import type { DataSource } from "@/api/types";

export function groupSources(entries: ReadonlyArray<readonly [string, DataSource]>): { source: DataSource; what: string[] }[] {
  const out: { source: DataSource; what: string[] }[] = [];
  for (const [what, source] of entries) {
    const g = out.find((x) => x.source === source);
    if (g) g.what.push(what);
    else out.push({ source, what: [what] });
  }
  return out;
}
