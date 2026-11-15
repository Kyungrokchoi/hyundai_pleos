"use client";
import Link from "next/link";
import { useParams } from "next/navigation";
import { api } from "@/api/client";
import type { CharacterOut, CouponOut, HistoryItem } from "@/api/types";
import { FooterNotice } from "@/components/Disclosure";
import { Frame } from "@/components/Frame";
import { Moa } from "@/components/Moa";
import { Notice } from "@/components/Notice";
import { IconStar } from "@/components/icons";
import { SourceBadge } from "@/components/SourceBadge";
import { Empty, ErrorState, Loading } from "@/components/StateViews";
import { formatDate, formatInt, formatKm, formatKrw } from "@/lib/format";
import { stateLabel } from "@/lib/labels";
import { useLoad } from "@/lib/useLoad";
import { useSession } from "@/lib/useSession";
import { useSessionGuard } from "@/lib/useSessionGuard";

export default function GroupPage() {
  const { id } = useParams<{ id: string }>();
  const session = useSession();
  const token = session?.token ?? null;

  const ch = useLoad<CharacterOut>(token ? (s) => api.character(token, id, s) : null, [token, id]);
  const hi = useLoad<HistoryItem[]>(token ? (s) => api.history(token, id, s) : null, [token, id]);
  const co = useLoad<CouponOut[]>(token ? (s) => api.rewards(token, id, s) : null, [token, id]);
  useSessionGuard(ch.error, hi.error, co.error);

  if (session === undefined || (session && ch.loading && !ch.data)) return <Frame><Loading label="모아를 불러오고 있어요" /></Frame>;
  if (session === null) {
    return (
      <Frame>
        <Notice tone="alert" title="그룹을 보려면 참여가 필요해요">차량 화면의 QR로 참여한 사람만 우리 모아를 볼 수 있어요.</Notice>
      </Frame>
    );
  }
  if (!ch.data) return <Frame><ErrorState error={ch.error} onRetry={ch.reload} /></Frame>;
  const c = ch.data;
  const span = c.next_level_xp === null ? null : c.next_level_xp - c.level_xp_start;
  const pct = span && span > 0 ? Math.min(100, Math.max(0, Math.round(((c.xp - c.level_xp_start) / span) * 100))) : 100;


  const demo = Boolean(hi.data?.some((h) => h.data_mode === "DEMO"));
  return (
    <Frame demo={demo}>
      <div className="stack-lg">
        <section className="stack" aria-labelledby="moa-h" style={{ textAlign: "center" }}>
          <div style={{ display: "grid", placeItems: "center", paddingTop: "var(--s-4)" }}>
            <Moa mood={c.mood} level={c.level} size={168} live />
          </div>
          <h1 id="moa-h" className="display" data-testid="moa-level">{c.name} <span className="num">Lv.{c.level}</span></h1>
          <div className="stack-sm" style={{ textAlign: "left" }}>
            <div className="meter" role="progressbar" aria-valuemin={c.level_xp_start} aria-valuemax={c.next_level_xp ?? c.xp} aria-valuenow={c.xp} aria-label="경험치">
              {pct > 0 && <span style={{ width: `${pct}%` }} />}
            </div>
            <p className="small muted" data-testid="xp-line">
              <span className="num">{formatInt(c.xp)}</span> XP
              {c.next_level_xp !== null ? <> · 다음 레벨까지 <span className="num">{formatInt(c.next_level_xp - c.xp)}</span> XP</> : " · 최고 레벨이에요"}
              {" · "}함께 마친 이동 <span className="num">{c.completed_trips}</span>번
            </p>
          </div>
          <p className="muted small">{c.note_ko}</p>
        </section>

        <section className="stack-sm" aria-labelledby="badges">
          <h2 id="badges" className="subtitle">받은 배지</h2>
          {c.badges.length === 0 ? (
            <p className="muted" data-testid="no-badges">아직 배지가 없어요. 함께 정산을 마치면 첫 배지를 받아요.</p>
          ) : (
            <ul className="badge-grid" data-testid="badges">
              {c.badges.map((b) => (
                <li key={b.id}>
                  <span className="badge badge--ok" aria-hidden="true"><IconStar size={16} /></span>
                  <span><strong>{b.name_ko}</strong><span className="small muted" style={{ display: "block" }}>{b.description_ko} · {formatDate(b.earned_at)}</span></span>
                </li>
              ))}
            </ul>
          )}
        </section>

        <section className="stack-sm" aria-labelledby="hist">
          <h2 id="hist" className="subtitle">함께한 이동</h2>
          {hi.loading && !hi.data ? <Loading /> : hi.error && !hi.data ? <ErrorState error={hi.error} onRetry={hi.reload} />
            : !hi.data || hi.data.length === 0 ? <Empty title="아직 함께한 이동이 없어요">첫 이동을 마치면 여기에 기록이 쌓여요.</Empty>
            : (
              <ul className="history" data-testid="history">
                {hi.data.map((h) => (
                  <li key={h.trip_id}>
                    <span>
                      <Link href={`/trip/${h.trip_id}/bill`}><strong>{formatDate(h.started_at)}</strong></Link>
                      <span className="small muted" style={{ display: "block" }}>
                        <span className="num">{h.participant_count}</span>명 · <span className="num">{formatKm(h.distance_km)}</span>km · {stateLabel(h.state)}
                      </span>
                      <SourceBadge source={h.data_mode} />
                    </span>
                    <span style={{ textAlign: "right" }}>
                      <span className="num subtitle">{h.total_krw === null ? "-" : formatKrw(h.total_krw)}</span>
                      {h.xp_gained > 0 && <span className="small" style={{ display: "block" }}>+<span className="num">{h.xp_gained}</span> XP</span>}
                    </span>
                  </li>
                ))}
              </ul>
            )}
        </section>

        <section className="stack-sm" aria-labelledby="coupons">
          <h2 id="coupons" className="subtitle">데모 쿠폰</h2>
          <Notice tone="info" title="시연용 · 실제 교환 불가">
            앞으로 제휴 혜택이 어떤 모습일지 보여 주는 화면이에요. 돈이나 상품으로 바꿀 수 없어요.
          </Notice>
          {co.loading && !co.data ? <Loading /> : co.error && !co.data ? <ErrorState error={co.error} onRetry={co.reload} />
            : !co.data || co.data.length === 0 ? <p className="muted" data-testid="no-coupons">아직 데모 쿠폰이 없어요. 모아가 자라면 시연용 쿠폰이 나타나요.</p>
            : (
              <ul className="stack" data-testid="coupons">
                {co.data.map((k) => (
                  <li key={k.id} className="coupon">
                    <span className="coupon__tag">시연용 · 실제 교환 불가</span>
                    <p className="subtitle">{k.brand_label}</p>
                    <p className="small muted">{k.disclaimer_ko}</p>
                  </li>
                ))}
              </ul>
            )}
        </section>
      </div>
      <FooterNotice />
    </Frame>
  );
}
