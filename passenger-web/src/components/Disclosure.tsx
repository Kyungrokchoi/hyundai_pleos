"use client";
import { useEffect, type ReactNode } from "react";
import { rememberNotices } from "@/lib/notices";
import { useRecalledNotices } from "@/lib/useNotices";
import { IconChevron } from "./icons";


export function Accordion({ title, children, testId }: { title: string; children: ReactNode; testId?: string }) {
  return (
    <details className="acc" data-testid={testId}>
      <summary>
        <span>{title}</span>
        <IconChevron className="chev" />
      </summary>
      <div className="acc__body">{children}</div>
    </details>
  );
}


export function FineLine({ texts, remember = false, summary = "결제·송금은 하지 않아요" }: {
  texts: readonly string[]; remember?: boolean; summary?: string;
}) {
  const key = texts.join("\u0001");
  useEffect(() => { if (remember && key) rememberNotices(key.split("\u0001")); }, [remember, key]);
  return (
    <details className="fine">
      <summary>
        <span>{summary}</span>
        <span className="more">자세히 <IconChevron size={18} className="chev" /></span>
      </summary>
      <div className="fine__body">
        <ul data-testid="disclaimers">{texts.map((t) => <li key={t}>{t}</li>)}</ul>
      </div>
    </details>
  );
}


export function FooterNotice({ texts, remember = false }: { texts?: readonly string[]; remember?: boolean }) {
  const recalled = useRecalledNotices();
  const key = texts?.join("\u0001") ?? "";
  useEffect(() => { if (remember && key) rememberNotices(key.split("\u0001")); }, [remember, key]);
  const shown = texts && texts.length > 0 ? texts : recalled;
  if (shown.length === 0) return null;
  return (
    <details className="foot">
      <summary>안내 보기</summary>
      <div className="fine__body">
        <ul data-testid="disclaimers">{shown.map((t) => <li key={t}>{t}</li>)}</ul>
      </div>
    </details>
  );
}
