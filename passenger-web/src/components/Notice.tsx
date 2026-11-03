import type { ReactNode } from "react";
import { IconAlert, IconCheck, IconInfo, IconOffline } from "./icons";

type Tone = "info" | "warn" | "alert" | "ok" | "offline";
const ICON = { info: IconInfo, warn: IconAlert, alert: IconAlert, ok: IconCheck, offline: IconOffline } as const;
const CLASS = { info: "", warn: "", alert: " banner--alert", ok: " banner--ok", offline: "" } as const;

// Status is never colour-only. `alert` is announced assertively.
export function Notice({ tone = "info", title, children }: { tone?: Tone; title?: string; children?: ReactNode }) {
  const Icon = ICON[tone];
  return (
    <div className={`banner${CLASS[tone]}`} role={tone === "alert" ? "alert" : "status"}>
      <Icon />
      <div>
        {title && <p className="banner__title">{title}</p>}
        {children && <div className="small">{children}</div>}
      </div>
    </div>
  );
}
