import type { DataSource } from "@/api/types";
import { DATA_SOURCE } from "@/lib/labels";


export function SourceBadge({ source, testId = "source-badge" }: { source: DataSource; testId?: string }) {
  if (source === "PLEOS") return null;
  const d = DATA_SOURCE[source];
  return (
    <span className={`badge${source === "DEMO" ? " badge--demo" : ""}`} data-testid={testId} data-source={source}>
      <span className="badge__code">{d.code}</span> · {d.label}
    </span>
  );
}


export function DemoBar() {
  return (
    <div className="demo-bar" role="note" data-testid="demo-bar">
      DEMO / 가상 데이터 <span>· 연습용이에요</span>
      <span className="sr-only">. 실제 차량 측정값이 아니에요.</span>
    </div>
  );
}
