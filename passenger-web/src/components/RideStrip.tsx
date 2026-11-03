import type { Lane, Strip } from "@/lib/ride";
import { pct } from "@/lib/ride";
import { formatKm } from "@/lib/format";

// The bars are aria-hidden; this text carries the same information.
export function laneText(l: Lane, live: boolean): string {
  const first = l.spans[0];
  const last = l.spans[l.spans.length - 1];
  if (!first || !last) return "탄 구간이 없어요";
  if (l.isDriver) return `${formatKm(0)}–${formatKm(first.to)}km 운전`;
  const parts = l.spans.map((s) => `${formatKm(s.from)}–${formatKm(s.to)}km`);
  const tail = last.exited ? ` · ${formatKm(last.to)}km에서 내림` : last.open && live ? " · 타는 중" : "";
  return `${parts.join(", ")} 탑승${tail}${l.byDriver ? " · 운전자가 기록" : ""}`;
}

export function RideStrip({ strip, ticks = [], testId = "ride-strip" }: { strip: Strip; ticks?: number[]; testId?: string }) {
  const { totalKm, live, lanes } = strip;
  return (
    <figure className="strip" data-testid={testId}>
      <figcaption className="sr-only">누가 어느 구간을 함께 탔는지</figcaption>
      <ul className="strip__lanes">
        {lanes.map((l) => (
          <li key={l.userId} className={`strip__lane${l.mine ? " strip__lane--me" : ""}`} data-testid="strip-lane" data-user={l.userId}>
            <p className="strip__head">
              <span className="strip__name">{l.nickname}{l.mine ? " (나)" : ""}{l.isDriver ? " · 운전자" : ""}</span>
              <span className="strip__meta" data-testid="strip-text">{laneText(l, live)}</span>
            </p>
            <span className="strip__track" aria-hidden="true">
              {ticks.map((t) => <span key={t} className="strip__tick" style={{ left: `${pct(t, totalKm)}%` }} />)}
              {l.spans.map((s, i) => (
                <span
                  key={i}
                  className={`strip__span${s.open && live ? " strip__span--open" : ""}`}
                  style={{ left: `${pct(s.from, totalKm)}%`, width: `${Math.max(pct(s.to, totalKm) - pct(s.from, totalKm), 0)}%` }}
                >
                  {s.exited && <span className="strip__pin" />}
                </span>
              ))}
            </span>
          </li>
        ))}
      </ul>
      <p className="strip__axis small" data-testid="strip-axis">
        <span>출발 <span className="num">0</span>km</span>
        <span>{live ? "지금" : "도착"} <span className="num">{formatKm(totalKm)}</span>km</span>
      </p>
    </figure>
  );
}
