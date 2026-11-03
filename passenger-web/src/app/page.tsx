"use client";
import Link from "next/link";
import { FineLine } from "@/components/Disclosure";
import { Frame } from "@/components/Frame";
import { Moa } from "@/components/Moa";
import { useCurrentTrip } from "@/lib/membership";
import { signOut } from "@/lib/signout";
import { useSession } from "@/lib/useSession";

const STEPS = [
  ["QR로 참여해요", "차량 화면의 QR을 스캔하고 닉네임을 정해요. 운전자가 승인하면 입장해요."],
  ["타고 내릴 때 눌러요", "차에 타면 ‘탔어요’, 내릴 때 ‘내렸어요’만 누르면 탄 구간이 기록돼요."],
  ["내 몫을 확인해요", "얼마인지, 왜 그런지 보고 승인하거나 이의를 남겨요."],
] as const;

// App copy only; the server's legal notices appear on the trip and bill screens.
const NOTES = [
  "정산 금액은 거리와 연비·전비로 계산한 추정치예요. 실제 연료비나 전기요금과 다를 수 있어요.",
  "이 앱은 결제나 송금을 하지 않아요. 계산 결과만 보여 줘요.",
  "저장하는 정보는 닉네임과 탑승 구간의 주행거리뿐이에요. 위치, 연락처, 차량번호는 저장하지 않아요.",
];

export default function Home() {
  const session = useSession();
  const current = useCurrentTrip(session);
  const tripId = current ?? session?.tripId ?? null;
  const primary = session?.pending ? "pending" : current ? "trip" : session?.groupId ? "group" : tripId ? "trip" : null;
  return (
    <Frame>
      <div className="stack-lg">
        <section className="stack" aria-labelledby="hero">
          <div style={{ display: "grid", placeItems: "center", padding: "var(--s-3) 0 0" }}><Moa mood="HAPPY" size={132} live /></div>
          <h1 id="hero" className="display">같이 타고,<br />공평하게 나눠요</h1>
          <p className="lede">차량 화면의 QR을 스캔하면 이동 기록과 정산을 이 휴대폰에서 바로 확인할 수 있어요.</p>
          <FineLine texts={NOTES} />
        </section>

        {session && (
          <section className="stack" aria-labelledby="resume" data-testid="resume">
            <h2 id="resume" className="subtitle">{session.nickname}님, 이어서 볼까요?</h2>
            {session.pending && <Link className={`btn${primary === "pending" ? " btn--primary" : ""}`} href="/join">운전자 승인 기다리는 중</Link>}
            {tripId && <Link className={`btn${primary === "trip" ? " btn--primary" : ""}`} href={`/trip/${tripId}`}>{current ? "오늘 이동 보기" : "지난 이동 보기"}</Link>}
            {session.groupId && <Link className={`btn${primary === "group" ? " btn--primary" : ""}`} href={`/group/${session.groupId}`}>우리 모아 보기</Link>}
            <div><button className="btn btn--text" type="button" onClick={() => void signOut(session.token)}>이 기기에서 나가기</button></div>
          </section>
        )}

        <section className="stack" aria-labelledby="how">
          <h2 id="how" className="subtitle">이렇게 쓰여요</h2>
          <ol className="stack">
            {STEPS.map(([t, d], i) => (
              <li key={t} style={{ display: "grid", gridTemplateColumns: "2.25rem 1fr", gap: "var(--s-3)" }}>
                <span className="num subtitle" aria-hidden="true">{i + 1}</span>
                <span><strong>{t}</strong><br /><span className="muted">{d}</span></span>
              </li>
            ))}
          </ol>
        </section>
      </div>
    </Frame>
  );
}
