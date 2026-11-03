"use client";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useRef, useState, type ReactNode } from "react";
import { api } from "@/api/client";
import type { ApprovalState, SettlementOut, TripSummary } from "@/api/types";
import { Avatar } from "@/components/Avatar";
import { Accordion, FineLine } from "@/components/Disclosure";
import { Frame } from "@/components/Frame";
import { IconAlert, IconArrowLeft, IconCheck, IconClock, IconCopy } from "@/components/icons";
import { Moa } from "@/components/Moa";
import { Notice } from "@/components/Notice";
import { RideStrip } from "@/components/RideStrip";
import { SourceBadge } from "@/components/SourceBadge";
import { Empty, ErrorState, Loading, StaleNotice } from "@/components/StateViews";
import { itemLabel, itemReason, payersOf, ridingSentence, sinceStart, splitIssues } from "@/lib/bill";
import { formatInt, formatKm, formatKrw } from "@/lib/format";
import { ALLOCATION, APPROVAL, DATA_SOURCE, ENERGY_POLICY, ITEM_KIND } from "@/lib/labels";
import { mergeNotices } from "@/lib/notices";
import { mySegments, rideStrip, segmentTicks } from "@/lib/ride";
import { groupSources } from "@/lib/sources";
import type { Session } from "@/lib/session";
import { buildShareText } from "@/lib/share";
import { useLoad } from "@/lib/useLoad";
import { usePolling } from "@/lib/usePolling";
import { useSession } from "@/lib/useSession";
import { useSessionGuard } from "@/lib/useSessionGuard";

const etagOf = (t: TripSummary) => `"${t.revision}"`;

interface Live { stale: boolean; offline: boolean; lastUpdated: number | null; refresh: () => void }

export default function BillPage() {
  const { id } = useParams<{ id: string }>();
  const session = useSession();
  const poll = usePolling<TripSummary>(
    (etag, signal) => api.tripSummary(session!.token, id, etag, signal),
    { intervalMs: 4000, enabled: Boolean(session) },
    etagOf,
  );
  useSessionGuard(poll.error);

  const trip = poll.data;
  if (session === undefined || (session && poll.loading && !trip)) return <Frame><Loading label="정산 내용을 불러오고 있어요" /></Frame>;
  if (session === null) {
    return <Frame><Notice tone="alert" title="정산을 보려면 참여가 필요해요">차량 화면의 QR로 참여한 사람만 볼 수 있어요.</Notice></Frame>;
  }
  if (!trip) return <Frame><ErrorState error={poll.error} onRetry={poll.refresh} /></Frame>;

  const live: Live = { stale: poll.stale, offline: poll.offline, lastUpdated: poll.lastUpdated, refresh: poll.refresh };
  if (!trip.settlement) return <WaitingForBill trip={trip} live={live} />;
  return <Bill trip={trip} s={trip.settlement} session={session} live={live} />;
}

function BackLink({ tripId }: { tripId: string }) {
  return <div><Link href={`/trip/${tripId}`} className="btn btn--text"><IconArrowLeft size={18} /> 이동 화면으로</Link></div>;
}

function WaitingForBill({ trip, live }: { trip: TripSummary; live: Live }) {
  const early = !["ENDED", "REVIEW", "DISPUTED", "CONFIRMED"].includes(trip.state);
  return (
    <Frame demo={trip.data_mode === "DEMO"}>
      <div className="stack">
        <BackLink tripId={trip.id} />
        {live.stale && <StaleNotice offline={live.offline} lastUpdated={live.lastUpdated} onRetry={live.refresh} />}
        <Empty title={early ? "아직 정산 전이에요" : "운전자가 정산안을 만드는 중이에요"}>
          {early ? <p>이동이 끝나면 여기서 내 몫과 계산 과정을 볼 수 있어요.</p> : <p>정산안이 만들어지면 이 화면이 자동으로 바뀌어요.</p>}
        </Empty>
      </div>
    </Frame>
  );
}

function ApprovalMark({ state }: { state: ApprovalState }) {
  if (state === "APPROVED") return <span className="mark mark--ok"><IconCheck size={16} /> {APPROVAL[state]}</span>;
  if (state === "DISPUTED") return <span className="mark mark--bad"><IconAlert size={16} /> {APPROVAL[state]}</span>;
  return <span className="mark"><IconClock size={16} /> {APPROVAL[state]}</span>;
}

function Bill({ trip, s, session, live }: { trip: TripSummary; s: SettlementOut; session: Session; live: Live }) {
  const mine = s.shares.find((sh) => sh.user_id === session.userId);
  const nameOf = new Map(s.shares.map((sh) => [sh.user_id, sh.nickname]));
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<{ kind: "APPROVE" | "DISPUTE"; err: unknown } | null>(null);
  const [comment, setComment] = useState("");
  const [anonymize, setAnonymize] = useState(true);
  const [copied, setCopied] = useState<string | null>(null);
  const [amountCopied, setAmountCopied] = useState("");
  const dispute = useRef<HTMLDialogElement>(null);

  const superseded = s.state === "SUPERSEDED";
  const confirmed = s.state === "CONFIRMED";
  const seen = new Map([...s.warnings, ...trip.issues].map((i) => [i.code + i.message_ko, i]));
  const { attention, info } = splitIssues([...seen.values()]);
  const notices = mergeNotices(s.disclaimers, trip.disclaimers, info.map((i) => i.message_ko));
  const sumMismatch = s.shares.reduce((a, x) => a + x.total_krw, 0) !== s.total_krw;

  async function decide(decision: "APPROVE" | "DISPUTE") {
    setBusy(true);
    setFailure(null);
    try {
      await api.decide(session.token, s.id, { decision, comment: decision === "DISPUTE" ? comment.trim() : "" });
      dispute.current?.close();
      setComment("");
      live.refresh();
    } catch (e) {
      setFailure({ kind: decision, err: e });
    } finally {
      setBusy(false);
    }
  }

  const owed = mine && !mine.is_driver ? mine.owed_to_driver_krw : 0;
  const driverName = s.shares.find((x) => x.is_driver)?.nickname ?? "운전자";
  async function copyAmount() {
    try {
      if (!navigator.clipboard) throw new Error("no clipboard");
      await navigator.clipboard.writeText(String(owed)); // digits only: banking apps paste it as an amount
      setAmountCopied(`${formatInt(owed)}원을 복사했어요. 송금 앱에 붙여 넣으세요.`);
    } catch {
      setAmountCopied(`복사할 수 없었어요. 위의 ${formatInt(owed)}원을 길게 눌러 직접 복사해 주세요.`);
    }
  }

  async function share() {
    const text = buildShareText(trip, s, { anonymize });
    try {
      if (typeof navigator.share === "function") await navigator.share({ text });
      else { await navigator.clipboard.writeText(text); setCopied("복사했어요. 단체 대화방에 붙여 넣어 보세요."); }
    } catch (e) {
      if (e instanceof DOMException && e.name === "AbortError") return; // user closed the share sheet
      setCopied("복사할 수 없었어요. 아래 내용을 길게 눌러 직접 복사해 주세요.");
    }
  }

  const openDispute = () => dispute.current?.showModal();
  let bar: ReactNode = null;
  if (confirmed) {
    // once confirmed, the only job left is paying the driver outside the app
    bar = owed > 0 ? (
      <div className="action-bar__inner">
        <button className="btn btn--primary" type="button" onClick={copyAmount} data-testid="copy-amount"><IconCopy /> {formatInt(owed)}원 복사</button>
        <Link className="btn btn--text" href={`/group/${trip.group_id}`} style={{ justifySelf: "center" }}>모아 만나러 가기</Link>
      </div>
    ) : <div className="action-bar__inner"><Link className="btn btn--primary" href={`/group/${trip.group_id}`}>모아 만나러 가기</Link></div>;
  } else if (mine && !superseded) {
    if (mine.approval === "PENDING") {
      bar = (
        <div className="action-bar__inner action-bar__inner--pair">
          <button className="btn btn--primary" type="button" disabled={busy} onClick={() => decide("APPROVE")} data-testid="approve" aria-describedby="approve-note">
            {busy ? "보내는 중…" : "승인할게요"}
          </button>
          <button className="btn" type="button" disabled={busy} onClick={openDispute} data-testid="dispute-open">이의 있어요</button>
          <p id="approve-note" className="action-bar__note">이 금액에 동의해요 · 돈은 나가지 않아요</p>
        </div>
      );
    } else if (mine.approval === "APPROVED") {
      bar = (
        <div className="action-bar__inner action-bar__row">
          <p className="action-bar__status" data-testid="my-approval"><IconCheck /> 승인했어요 · 다른 분을 기다려요</p>
          <button className="btn btn--text" type="button" onClick={openDispute} data-testid="dispute-open">이의 있어요</button>
        </div>
      );
    } else {
      bar = (
        <div className="action-bar__inner action-bar__row">
          <p className="action-bar__status" data-testid="my-approval"><IconAlert /> 이의 제기를 보냈어요 · 운전자가 정산안을 다시 만들어요</p>
          <button className="btn btn--text" type="button" disabled={busy} onClick={() => decide("APPROVE")} data-testid="approve">승인으로 바꾸기</button>
        </div>
      );
    }
  }

  const sourceLine = DATA_SOURCE[s.efficiency_source];
  const ev = s.consumption_unit === "kWh";
  const strip = rideStrip(trip, session.userId);
  const segs = mine ? mySegments(s.segments, trip.odometer_start_km, mine.user_id) : [];
  const itemById = new Map(trip.cost_items.map((c) => [c.id, c]));
  // The unit price and cost items are always typed by the driver, so they are always MANUAL.
  const sourceGroups = groupSources([
    ["거리", trip.data_mode],
    [ev ? "전비" : "연비", s.efficiency_source],
    [ev ? "전기요금" : "유가", "MANUAL"],
    ...(trip.cost_items.length > 0 ? [["통행료·주차비", "MANUAL"] as const] : []),
  ]);
  const leadSentence = s.energy_policy === "SEGMENT_SHARED"
    ? "같은 구간을 함께 탄 사람끼리 그 구간의 연료·전기 비용을 똑같이 나눠요. 내린 뒤의 구간은 내 몫에 들어가지 않아요."
    : "연료·전기 비용 전체를 함께한 사람이 똑같이 나눠요.";
  const chip = confirmed ? { text: "금액 합의 완료", cls: "badge--ok" } : s.state === "DISPUTED" ? { text: "이의 제기됨", cls: "badge--bad" }
    : superseded ? { text: "이전 정산안", cls: "" } : { text: "모두 승인하면 합의돼요", cls: "" };

  return (
    <Frame demo={trip.data_mode === "DEMO"} bar={bar}>
      <p className="sr-only" aria-live="polite" data-testid="bill-live">정산 상태: {confirmed ? "금액 합의 완료" : superseded ? "새 정산안으로 교체됨" : "승인 기다리는 중"}</p>
      <div className="stack-lg">

        <section className="stack-sm" aria-labelledby="mine">
          <BackLink tripId={trip.id} />
          <div className="row">
            <span className={`badge ${chip.cls}`} data-testid="settlement-state">{chip.text}</span>
            {s.revision > 1 && <span className="small muted">정산안이 다시 만들어졌어요</span>}
          </div>
          <h1 id="mine" className="label" style={{ marginTop: "var(--s-3)" }}>
            {mine ? (mine.is_driver ? "내가 부담하는 금액" : "내가 운전자에게 줄 금액") : "이동 전체 금액"}
          </h1>
          <p className="num-hero" data-testid={mine ? "my-total" : "total"}>
            {formatInt(mine ? (mine.is_driver ? mine.total_krw : mine.owed_to_driver_krw) : s.total_krw)}<span className="unit">원</span>
          </p>
          {mine && <p className="lede" data-testid="riding-sentence">{ridingSentence(s.distance_km, mine.ridden_km, mine.is_driver)}</p>}
          <ul className="sources" aria-label="숫자의 출처" data-testid="source-line">
            {sourceGroups.map((g) => (
              <li key={g.source} data-testid={`source-${g.source.toLowerCase()}`}><SourceBadge source={g.source} testId="source-badge" /> {g.what.join(" · ")}</li>
            ))}
          </ul>
          {confirmed && owed > 0 && (
            <p className="small" data-testid="pay-note">송금은 평소 쓰는 앱으로 {driverName}님께 직접 보내 주세요. 모아타는 돈을 옮기지 않아요.</p>
          )}
          <p className="small" role="status" aria-live="polite" data-testid="amount-copied">{amountCopied}</p>
          <FineLine texts={notices} remember />
        </section>

        {mine && (
          <section className="stack" aria-labelledby="why-mine">
            <h2 id="why-mine" className="subtitle">내 몫은 이렇게 나왔어요</h2>
            {strip && <RideStrip strip={strip} ticks={segmentTicks(s.segments, trip.odometer_start_km)} />}
            {/* only addends of 합계 go in the amount column; the working sits under each label */}
            <dl className="ledger ledger--tight" data-testid="my-basis">
              <div data-testid="my-energy">
                <dt>
                  {ev ? "전기" : "연료"} 비용 내 몫
                  {s.energy_policy === "SEGMENT_SHARED" && segs.length > 0 && (
                    <ul className="ledger__calc">
                      {segs.map((g) => (
                        <li key={g.from + g.to} data-testid="my-segment">
                          <span className="num">{formatKm(g.from)}–{formatKm(g.to)}</span>km 구간 · {formatKrw(g.energyKrw)} ÷ {g.payers}명
                          {segs.length > 1 ? <> = {g.exact ? "" : "약 "}{formatKrw(g.each)}</> : null}
                        </li>
                      ))}
                    </ul>
                  )}
                </dt>
                <dd>{formatKrw(mine.energy_krw)}</dd>
              </div>
              {mine.items.map((it) => {
                const c = itemById.get(it.item_id);
                const why = itemReason(c, payersOf(s.shares, it.item_id));
                return (
                  <div key={it.item_id} data-testid="my-item">
                    <dt>
                      {itemLabel(c, it.label)}
                      {why && <span className="ledger__calc" data-testid="item-reason">{why}</span>}
                    </dt>
                    <dd>{formatKrw(it.amount_krw)}</dd>
                  </div>
                );
              })}
              <div className="total"><dt>합계</dt><dd>{formatKrw(mine.total_krw)}</dd></div>
            </dl>
            {segs.some((g) => !g.exact) && <p className="tiny">나눠떨어지지 않는 1원 단위는 서버가 정해진 순서로 한 사람씩 더해요.</p>}
          </section>
        )}

        {superseded && <Notice tone="warn" title="새 정산안이 나왔어요">이 내용은 이전 정산안이에요. 잠시 뒤 최신 정산안으로 바뀌어요.</Notice>}
        {live.stale && <StaleNotice offline={live.offline} lastUpdated={live.lastUpdated} onRetry={live.refresh} />}
        {failure?.kind === "APPROVE" && <ErrorState error={failure.err} onRetry={() => void decide("APPROVE")} />}
        {sumMismatch && <Notice tone="alert" title="몫의 합계가 총액과 달라요">이 정산안은 믿지 말고 운전자에게 알려 주세요. 승인도 잠시 미뤄 주세요.</Notice>}
        {attention.length > 0 && (
          <div className="stack-sm" data-testid="warnings">
            {attention.map((w) => <Notice key={w.code + w.message_ko} tone="warn">{w.message_ko}</Notice>)}
          </div>
        )}
        {confirmed && <Celebrate trip={trip} session={session} />}

        <div className="accs">
          <Accordion title="어떻게 계산했어요?" testId="acc-how">
            <div className="stack">
              <p className="muted">{leadSentence}</p>
              <div className="stack-sm">
                <p className="label">계산식</p>
                <p className="equation" data-testid="equation">{s.equation}</p>
              </div>

              <div className="stack-sm">
                <h3 className="subtitle">모두의 구간별 연료·전기 비용</h3>
                <ol className="why" data-testid="segments">
                  {s.segments.map((g) => {
                    const from = sinceStart(trip.odometer_start_km, g.start_km) ?? formatKm(g.start_km);
                    const to = sinceStart(trip.odometer_start_km, g.end_km) ?? formatKm(g.end_km);
                    return (
                      <li key={g.index}>
                        <div className="row-between">
                          <strong className="num">출발 후 {from}–{to}km</strong>
                          <span className="num">{formatKrw(g.energy_krw)}</span>
                        </div>
                        <p className="small muted">{formatKm(g.length_km)}km 동안 {g.payers.map((u) => nameOf.get(u) ?? "참가자").join(", ")} ({g.payers.length}명)</p>
                      </li>
                    );
                  })}
                </ol>
              </div>

              <dl className="ledger">
                <div><dt>총 이동비</dt><dd data-testid="total">{formatKrw(s.total_krw)}</dd></div>
                <div><dt>{s.consumption_unit === "L" ? "추정 연료 사용량" : "추정 전기 사용량"}</dt><dd>{formatKm(s.consumption, 2)} {s.consumption_unit}</dd></div>
                <div><dt>연비·전비는</dt><dd><SourceBadge source={s.efficiency_source} /></dd></div>
                <div><dt>운전자 비용</dt><dd>{s.driver_included ? "함께 나눠요" : "나누지 않아요"}</dd></div>
                <div><dt>나누는 방법</dt><dd style={{ maxWidth: "62%" }}>{ENERGY_POLICY[s.energy_policy]}</dd></div>
              </dl>
              <p className="small muted">{sourceLine.explain}</p>
              {trip.cost_items.length > 0 && (
                <ul className="stack-sm small muted">
                  {trip.cost_items.map((c) => (
                    <li key={c.id}>{ITEM_KIND[c.kind]}{c.note ? ` (${c.note})` : ""} {formatKrw(c.amount_krw)} · {ALLOCATION[c.allocation]}</li>
                  ))}
                </ul>
              )}
            </div>
          </Accordion>

          <Accordion title="모두의 몫" testId="acc-everyone">
            <ul className="people" data-testid="shares">
              {s.shares.map((sh) => (
                <li key={sh.user_id} data-user={sh.user_id}>
                  <Avatar name={sh.nickname} me={sh.user_id === session.userId} />
                  <span className="people__who">
                    <span className="people__name">{sh.nickname}{sh.user_id === session.userId ? " (나)" : ""}{sh.is_driver ? " · 운전자" : ""}</span>
                    <span className="people__meta"><ApprovalMark state={sh.approval} /> · <span className="num">{formatKm(sh.ridden_km)}</span>km</span>
                    {sh.comment ? <span className="people__meta">“{sh.comment}”</span> : null}
                  </span>
                  <span className="people__end">{formatKrw(sh.total_krw)}</span>
                </li>
              ))}
            </ul>
          </Accordion>

          <Accordion title="단체 대화방에 공유" testId="acc-share">
            <div className="stack">
              <label className="check">
                <input type="checkbox" checked={anonymize} onChange={(e) => setAnonymize(e.target.checked)} />
                <span>이름 대신 ‘참가자 A·B’로 표시해요 <span className="hint">(그룹 밖에 공유할 때 권장)</span></span>
              </label>
              <button className="btn" type="button" onClick={share} data-testid="share"><IconCopy /> 정산 내용 복사·공유</button>
              {copied && <p className="small" role="status" data-testid="copied">{copied}</p>}
            </div>
          </Accordion>
        </div>
      </div>

      <dialog ref={dispute} aria-labelledby="dispute-title" data-testid="dispute-dialog">
        <div className="stack">
          <h2 id="dispute-title" className="subtitle">어떤 점이 이상한가요?</h2>
          <p className="muted">이의를 보내면 운전자가 정산안을 다시 만들어요. 그때까지 정산은 확정되지 않아요.</p>
          <div className="field">
            <label htmlFor="cmt" className="sr-only">이의 내용</label>
            <textarea id="cmt" className="input" maxLength={100} value={comment} onChange={(e) => setComment(e.target.value)} placeholder="예: 톨비가 두 번 들어간 것 같아요 (100자까지)" data-testid="dispute-comment" />
          </div>
          {failure?.kind === "DISPUTE" ? <ErrorState error={failure.err} onRetry={() => void decide("DISPUTE")} /> : null}
          <div className="btn-pair">
            <button className="btn" type="button" onClick={() => { setFailure(null); dispute.current?.close(); }}>취소</button>
            <button className="btn btn--danger" type="button" disabled={busy || comment.trim().length === 0} onClick={() => decide("DISPUTE")} data-testid="dispute-send">
              {busy ? "보내는 중…" : "이의 보내기"}
            </button>
          </div>
        </div>
      </dialog>
    </Frame>
  );
}

function Celebrate({ trip, session }: { trip: TripSummary; session: Session }) {
  // A failed history load only drops the XP line; it must never hide the confirmed bill.
  const hist = useLoad((signal) => api.history(session.token, trip.group_id, signal), [trip.group_id, session.token]);
  const xp = hist.data?.find((h) => h.trip_id === trip.id)?.xp_gained ?? null;
  return (
    <div className="stack-sm" style={{ textAlign: "center" }} data-testid="celebrate">
      <div style={{ display: "grid", placeItems: "center" }}><Moa mood="PROUD" size={104} grow /></div>
      <p className="subtitle" aria-live="polite">모두 승인했어요. 금액 합의 완료!</p>
      {xp !== null && xp > 0 && <p className="muted">모아가 함께한 이동으로 <strong>+{xp} XP</strong> 자랐어요.</p>}
    </div>
  );
}
