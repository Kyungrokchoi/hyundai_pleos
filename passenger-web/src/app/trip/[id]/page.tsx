"use client";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useRef, type ReactNode } from "react";
import { api } from "@/api/client";
import type { ParticipantOut, TripSummary } from "@/api/types";
import { Avatar } from "@/components/Avatar";
import { FooterNotice } from "@/components/Disclosure";
import { Frame } from "@/components/Frame";
import { IconCheck } from "@/components/icons";
import { Notice } from "@/components/Notice";
import { RideStrip } from "@/components/RideStrip";
import { SourceBadge } from "@/components/SourceBadge";
import { ErrorState, Loading, StaleNotice } from "@/components/StateViews";
import { sinceStart, splitIssues } from "@/lib/bill";
import { formatAge, formatKm, kmBetween } from "@/lib/format";
import { TRIP_STATE } from "@/lib/labels";
import { useCurrentTrip } from "@/lib/membership";
import { mergeNotices } from "@/lib/notices";
import { boardedByDriver, rideStrip } from "@/lib/ride";
import type { Session } from "@/lib/session";
import { usePolling } from "@/lib/usePolling";
import { useRideActions } from "@/lib/useRideActions";
import { useSession } from "@/lib/useSession";
import { useSessionGuard } from "@/lib/useSessionGuard";

const etagOf = (t: TripSummary) => `"${t.revision}"`;
const FINISHED = ["ENDED", "REVIEW", "DISPUTED", "CONFIRMED"];
const PRE_DEPARTURE = ["INVITING", "READY"];

export default function TripPage() {
  const { id } = useParams<{ id: string }>();
  const session = useSession();
  const poll = usePolling<TripSummary>(
    (etag, signal) => api.tripSummary(session!.token, id, etag, signal),
    { intervalMs: 3000, enabled: Boolean(session) },
    etagOf,
  );
  useSessionGuard(poll.error);

  const trip = poll.data;
  if (session === undefined || (session && poll.loading && !trip)) return <Frame><Loading label="이동 정보를 불러오고 있어요" /></Frame>;
  if (session === null) return <Frame><NeedJoin /></Frame>;
  if (!trip) return <Frame><ErrorState error={poll.error} onRetry={poll.refresh} /></Frame>;
  return <TripBody trip={trip} session={session} stale={poll.stale} offline={poll.offline} lastUpdated={poll.lastUpdated} refresh={poll.refresh} />;
}

function NeedJoin() {
  return (
    <div className="stack" data-testid="need-join">
      <Notice tone="alert" title="이 이동을 보려면 참여가 필요해요">
        차량 화면의 QR을 스캔해서 참여를 요청해 주세요. 참여 중인 사람만 이동 기록과 정산을 볼 수 있어요.
      </Notice>
      <Link className="btn btn--text" href="/">처음 화면으로</Link>
    </div>
  );
}

function TripBody({ trip, session, stale, offline, lastUpdated, refresh }: {
  trip: TripSummary; session: Session; stale: boolean; offline: boolean; lastUpdated: number | null; refresh: () => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const act = useRideActions(trip, session, dialog, refresh);
  const current = useCurrentTrip(session);
  const newerTrip = current && current !== trip.id && ["CONFIRMED", "CANCELLED"].includes(trip.state) ? current : null;
  const me = trip.participants.find((p) => p.user_id === session.userId);
  const info = TRIP_STATE[trip.state];
  const live = trip.distance_km ?? kmBetween(trip.odometer_start_km, trip.latest_odometer?.km ?? null);
  const finished = FINISHED.includes(trip.state);
  const { attention, info: infoIssues } = splitIssues(trip.issues);

  const active = Boolean(me && !me.is_driver && me.member_status === "ACTIVE");
  const exits = me?.intervals.filter((i) => i.offboard_at) ?? [];
  const alighted = active && !me?.on_board && exits.length > 0;
  const parked = PRE_DEPARTURE.includes(trip.state);
  // board while parked; a first tap after departure still counts from that point
  const canBoard = active && !me?.on_board && (parked || (trip.state === "IN_PROGRESS" && me?.intervals.length === 0));
  const canAlight = active && Boolean(me?.on_board) && trip.state === "IN_PROGRESS";
  const canReboard = alighted && trip.state === "IN_PROGRESS";
  const exitKm = sinceStart(trip.odometer_start_km, exits[exits.length - 1]?.odometer_off_km ?? null);

  let bar: ReactNode = null;
  if (canBoard) {
    bar = (
      <div className="action-bar__inner">
        <button className="btn btn--primary" type="button" disabled={act.busy !== null} onClick={() => act.run("board")} data-testid="board">
          {act.busy === "board" ? "기록 중…" : "탔어요"}
        </button>
      </div>
    );
  } else if (canAlight) {
    bar = (
      <div className="action-bar__inner">
        <button className="btn btn--primary" type="button" disabled={act.busy !== null} onClick={() => dialog.current?.showModal()} data-testid="alight">
          내렸어요
        </button>
      </div>
    );
  } else if (finished) {
    bar = (
      <div className="action-bar__inner">
        <Link className="btn btn--primary" href={`/trip/${trip.id}/bill`} data-testid="to-bill">정산 보기</Link>
      </div>
    );
  }

  const offForMe = alighted && !finished;
  const headline = offForMe ? "내렸어요" : info.label;
  const hint = offForMe ? "다른 사람은 계속 이동 중이에요. 도착하면 정산이 만들어져요." : me?.on_board && parked ? "출발하면 여기서부터 계산돼요." : info.hint;
  const notices = mergeNotices(trip.disclaimers, infoIssues.map((i) => i.message_ko));
  const strip = rideStrip(trip, session.userId);
  const myLane = strip?.lanes.find((l) => l.mine);
  // after getting off, show my own distance instead of the car's
  const showMine = alighted && myLane !== undefined;

  return (
    <Frame demo={trip.data_mode === "DEMO"} bar={bar}>
      <p className="sr-only" aria-live="polite" data-testid="state-live">이동 상태: {headline}</p>
      <div className="stack-lg">

        {newerTrip && (
          <div className="stack-sm" data-testid="newer-trip">
            <Notice tone="info" title="새 이동이 시작됐어요">이 화면은 지난 이동이에요. 오늘 이동에서 ‘탔어요’를 눌러 주세요.</Notice>
            <Link className="btn btn--primary" href={`/trip/${newerTrip}`}>오늘 이동 열기</Link>
          </div>
        )}

        <section className="stack-sm" aria-labelledby="t">
          <p className="label">{trip.group_name}{trip.vehicle_alias ? ` · ${trip.vehicle_alias}` : ""}</p>
          <h1 id="t" className="display" data-testid="state-label">{headline}</h1>
          <p className="lede">{hint}</p>
        </section>

        {stale && <StaleNotice offline={offline} lastUpdated={lastUpdated} onRetry={refresh} />}

        <section className="stack-sm" aria-labelledby="dist">
          <h2 id="dist" className="label">{showMine ? "내가 탄 거리" : trip.distance_km ? "이동 거리" : "지금까지 이동 거리"}</h2>
          {showMine ? <p className="num-xl" data-testid="my-distance">{formatKm(myLane.riddenKm)}<span className="unit">km</span></p>
            : live ? <p className="num-xl" data-testid="distance">{formatKm(live)}<span className="unit">km</span></p>
            : <p className="lede" data-testid="distance-empty">이동이 시작되면 거리가 여기에 보여요.</p>}
          <p className="row small muted">
            <SourceBadge source={trip.data_mode} />
            {!trip.distance_km && trip.latest_odometer && (
              <span>{formatAge(trip.latest_odometer.age_seconds)} 갱신{trip.latest_odometer.quality !== "VERIFIED" ? " · 정확도가 낮아요" : ""}</span>
            )}
          </p>
        </section>

        <MyStatus trip={trip} me={me} active={active} alighted={alighted} exitKm={exitKm} canReboard={canReboard} act={act} />

        {attention.length > 0 && (
          <section className="stack-sm" aria-label="기록 확인 필요" data-testid="issues">
            {attention.map((i) => <Notice key={i.code + i.message_ko} tone="warn">{i.message_ko}</Notice>)}
          </section>
        )}

        <section className="stack-sm" aria-labelledby="who">
          <h2 id="who" className="subtitle">{strip ? "누가 어디까지 탔나요" : "함께 탈 사람"}</h2>
          {strip ? <RideStrip strip={strip} /> : (
            <ul className="people" data-testid="participants">
              {trip.participants.map((p) => <PersonRow key={p.user_id} p={p} mine={p.user_id === session.userId} trip={trip} />)}
            </ul>
          )}
        </section>

        <div>
          <Link className="btn btn--text" href={`/group/${trip.group_id}`}>우리 모아 보기</Link>
        </div>
      </div>

      <FooterNotice texts={notices} remember />

      <dialog ref={dialog} aria-labelledby="alight-title" data-testid="alight-dialog">
        <div className="stack">
          <h2 id="alight-title" className="subtitle">여기서 내리나요?</h2>
          <p className="muted">내린 지점이 기록되고, 이후 구간의 비용은 내 몫에서 빠져요. 잘못 눌렀다면 바로 ‘다시 탔어요’를 누르면 돼요.</p>
          <div className="btn-pair">
            <button className="btn" type="button" onClick={() => dialog.current?.close()}>아니요</button>
            <button className="btn btn--primary" type="button" disabled={act.busy !== null} onClick={() => act.run("alight")} data-testid="alight-confirm">
              {act.busy === "alight" ? "기록 중…" : "네, 내렸어요"}
            </button>
          </div>
        </div>
      </dialog>
    </Frame>
  );
}

function MyStatus({ trip, me, active, alighted, exitKm, canReboard, act }: {
  trip: TripSummary; me: ParticipantOut | undefined; active: boolean; alighted: boolean; exitKm: string | null; canReboard: boolean;
  act: ReturnType<typeof useRideActions>;
}) {
  const finished = FINISHED.includes(trip.state);
  let body: ReactNode = null;
  if (!me) body = <Notice tone="warn" title="아직 이 이동의 참여자가 아니에요">운전자가 참여를 승인하면 여기서 탑승 버튼이 나타나요.</Notice>;
  else if (me.is_driver) body = <Notice tone="info">운전자는 차량 화면에서 이동을 조작해요. 이 화면에서는 보기만 할 수 있어요.</Notice>;
  else if (me.member_status !== "ACTIVE") body = <Notice tone="warn" title="운전자의 승인을 기다리고 있어요" />;
  else if (alighted) {
    body = (
      <div className="stack-sm" data-testid="alighted">
        <Notice tone="ok">
          {exitKm ? `출발 후 ${exitKm}km 지점에서 내렸어요. ` : ""}{finished ? "정산에서 내 몫을 볼 수 있어요." : "이후 구간은 내 몫에 들어가지 않아요."}
        </Notice>
        {canReboard && (
          <button className="btn btn--text" type="button" disabled={act.busy !== null} onClick={() => act.run("board")} data-testid="reboard">
            잘못 눌렀나요? 다시 탔어요
          </button>
        )}
      </div>
    );
  } else if (me.on_board && active && PRE_DEPARTURE.includes(trip.state)) {
    body = (
      <div className="stack-sm" data-testid="boarded-parked">
        <Notice tone="ok" title="탔어요로 기록했어요">출발하면 여기서부터 계산돼요. 내릴 때 이 화면에서 ‘내렸어요’를 눌러 주세요.</Notice>
        <button className="btn btn--text" type="button" disabled={act.busy !== null} onClick={() => act.run("unboard")} data-testid="unboard">
          {act.busy === "unboard" ? "취소하는 중…" : "잘못 눌렀어요"}
        </button>
      </div>
    );
  }
  return (
    <>
      {body}
      {boardedByDriver(me) && (
        <div data-testid="boarded-by-driver">
          <Notice tone="info">운전자가 대신 탑승으로 기록했어요. 탄 시점이 다르면 정산서에서 이의를 남겨 주세요.</Notice>
        </div>
      )}
      {act.error ? <ErrorState error={act.error} onRetry={act.retry} /> : null}
    </>
  );
}

function PersonRow({ p, mine, trip }: { p: ParticipantOut; mine: boolean; trip: TripSummary }) {
  const exit = [...p.intervals].reverse().find((i) => i.offboard_at);
  const exitKm = sinceStart(trip.odometer_start_km, exit?.odometer_off_km ?? null);
  const moving = trip.state === "IN_PROGRESS";
  const onBoard = p.on_board || (p.is_driver && moving);

  let meta: ReactNode;
  if (p.is_driver) meta = "운전자";
  else if (p.member_status === "PENDING") meta = "참여 승인을 기다려요";
  else if (p.on_board) meta = <span className="mark mark--ok"><IconCheck size={16} /> {PRE_DEPARTURE.includes(trip.state) ? "탔어요" : "탑승 중"}</span>;
  else if (exit) meta = exitKm ? `출발 후 ${exitKm}km 지점에서 내렸어요` : "내렸어요";
  else meta = "아직 타지 않았어요";

  return (
    <li data-onboard={onBoard} data-testid="person" data-user={p.user_id}>
      <Avatar name={p.nickname} onBoard={onBoard} me={mine} />
      <span className="people__who">
        <span className="people__name">{p.nickname}{mine ? " (나)" : ""}</span>
        <span className="people__meta">{meta}</span>
      </span>
    </li>
  );
}
