"use client";
import Link from "next/link";
import type { ReactNode } from "react";
import { describeError } from "@/lib/errors";
import { Moa } from "./Moa";
import { Notice } from "./Notice";

export function Loading({ label = "불러오는 중이에요" }: { label?: string }) {
  return (
    <div aria-busy="true" aria-live="polite" className="stack" data-testid="loading">
      <p className="sr-only">{label}</p>
      <div className="skeleton" style={{ width: "40%" }} />
      <div className="skeleton skeleton--block" />
      <div className="skeleton" style={{ width: "70%" }} />
      <div className="skeleton skeleton--block" />
    </div>
  );
}

export function Empty({ title, children, mood = "SLEEPY" }: { title: string; children?: ReactNode; mood?: "SLEEPY" | "HAPPY" }) {
  return (
    <div className="stack" style={{ textAlign: "center", padding: "var(--s-5) 0" }} data-testid="empty">
      <div style={{ display: "grid", placeItems: "center" }}><Moa mood={mood} size={88} /></div>
      <p className="subtitle">{title}</p>
      {children && <div className="muted">{children}</div>}
    </div>
  );
}

export function ErrorState({ error, onRetry, invite = false }: { error: unknown; onRetry?: () => void; invite?: boolean }) {
  const v = describeError(error, { invite });
  const tone = v.kind === "offline" ? "offline" : "alert";
  return (
    <div className="stack" data-testid="error-state" data-kind={v.kind}>
      <Notice tone={tone} title={v.title}>{v.body}</Notice>
      {v.retryable && onRetry && <button className="btn" type="button" onClick={onRetry}>다시 시도</button>}
      {(v.kind === "auth" || v.kind === "forbidden") && (
        <Link className="btn btn--text" href="/">처음 화면으로</Link>
      )}
    </div>
  );
}


export function StaleNotice({ offline, lastUpdated, onRetry }: { offline: boolean; lastUpdated: number | null; onRetry: () => void }) {
  const when = lastUpdated ? new Date(lastUpdated).toLocaleTimeString("ko-KR", { hour: "numeric", minute: "2-digit", second: "2-digit" }) : null;
  return (
    <div className="stack-sm" data-testid="stale-notice">
      <Notice tone="offline" title={offline ? "인터넷 연결이 불안정해요" : "최신 정보를 가져오지 못했어요"}>
        {when ? `마지막으로 갱신된 시각은 ${when}이에요. 지금 보이는 내용은 이전 정보일 수 있어요.` : "지금 보이는 내용은 이전 정보일 수 있어요."}
      </Notice>
      <button className="btn btn--text" type="button" onClick={onRetry}>지금 다시 불러오기</button>
    </div>
  );
}
