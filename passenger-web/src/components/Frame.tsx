import Link from "next/link";
import type { ReactNode } from "react";
import { Moa } from "./Moa";
import { DemoBar } from "./SourceBadge";


export function Frame({ children, bar, demo = false }: { children: ReactNode; bar?: ReactNode; demo?: boolean }) {
  return (
    <>
      {demo && <DemoBar />}
      <div className="shell">
        <header className="site-header">
          <Link className="wordmark" href="/" aria-label="모아타 처음 화면">
            <Moa size={32} label="" />
            모아타
          </Link>
        </header>
        <main id="main" tabIndex={-1}>{children}</main>
        {bar ? <div className="action-bar" role="region" aria-label="내가 할 수 있는 동작" data-testid="action-bar">{bar}</div> : null}
      </div>
    </>
  );
}
