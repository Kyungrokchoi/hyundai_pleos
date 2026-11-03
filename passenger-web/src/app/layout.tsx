import type { Metadata, Viewport } from "next";
import type { ReactNode } from "react";
import "pretendard/dist/web/variable/pretendardvariable-dynamic-subset.css";
import "./globals.css";

export const metadata: Metadata = {
  title: { default: "모아타", template: "%s · 모아타" },
  description: "함께 탄 이동을 공평하게 나누는 모아타 동승자용 화면",
  referrer: "no-referrer",
  robots: { index: false, follow: false },
};

export const viewport: Viewport = { width: "device-width", initialScale: 1, themeColor: "#f3f6f8" };

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="ko">
      <body>
        <a className="skip-link" href="#main">본문으로 건너뛰기</a>
        <div id="page">{children}</div>
      </body>
    </html>
  );
}
