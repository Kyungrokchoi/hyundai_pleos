"use client";
import { useRouter } from "next/navigation";
import { useEffect, useId, useState } from "react";
import { api } from "@/api/client";
import type { InvitePreviewOut, MembershipOut } from "@/api/types";
import { Accordion, FineLine } from "@/components/Disclosure";
import { Frame } from "@/components/Frame";
import { Moa } from "@/components/Moa";
import { Notice } from "@/components/Notice";
import { ErrorState, Loading } from "@/components/StateViews";
import { CONSENT_VERSION } from "@/lib/consent";
import { describeError } from "@/lib/errors";
import { fetchMyMembership } from "@/lib/membership";
import { dropInviteToken, saveSession, updateSession } from "@/lib/session";
import { signOut } from "@/lib/signout";
import { useInviteToken } from "@/lib/useInviteToken";
import { useLoad } from "@/lib/useLoad";
import { usePolling } from "@/lib/usePolling";
import { useSession } from "@/lib/useSession";
import { useSessionGuard } from "@/lib/useSessionGuard";

export default function JoinPage() {
  const session = useSession();
  const token = useInviteToken();

  if (token === undefined || session === undefined) return <Frame><Loading label="초대를 확인하고 있어요" /></Frame>;
  if (session?.pending && session.groupId) return <Frame><Waiting /></Frame>;
  if (!token) return <Frame><NoInvite /></Frame>;
  return <InviteForm token={token} />;
}

function NoInvite() {
  return (
    <div className="stack">
      <div style={{ display: "grid", placeItems: "center" }}><Moa mood="SLEEPY" size={96} /></div>
      <h1 className="title">초대 링크가 필요해요</h1>
      <p className="muted">운전자의 차량 화면에 나온 QR을 휴대폰 카메라로 스캔하면 이 화면으로 바로 들어와요. 주소를 직접 입력할 필요는 없어요.</p>
      <Notice tone="info">QR이 사라졌다면 운전자에게 ‘새 초대 만들기’를 부탁해 주세요. 초대는 잠시 동안만 쓸 수 있어요.</Notice>
    </div>
  );
}

function InviteForm({ token }: { token: string }) {
  const router = useRouter();
  const session = useSession();
  const preview = useLoad<InvitePreviewOut>((signal) => api.previewInvite(token, signal), [token]);

  const nickId = useId();
  const [nickname, setNickname] = useState("");
  const [agreeData, setAgreeData] = useState(false);
  const [agreeRules, setAgreeRules] = useState(false);
  const [busy, setBusy] = useState(false);
  const [submitError, setSubmitError] = useState<unknown>(null);
  const [touched, setTouched] = useState(false);

  const signedIn = Boolean(session);
  const name = nickname.trim();
  const nameOk = signedIn || (name.length >= 1 && name.length <= 20);
  const canSubmit = nameOk && agreeData && agreeRules && !busy;

  if (preview.loading && !preview.data) return <Frame><Loading label="초대를 확인하고 있어요" /></Frame>;
  if (preview.error || !preview.data) {
    return (
      <Frame>
        <div className="stack">
          <ErrorState error={preview.error} invite onRetry={preview.reload} />
          {describeError(preview.error, { invite: true }).kind === "gone" && (
            <button className="btn" type="button" onClick={dropInviteToken}>이 초대 정보 지우기</button>
          )}
        </div>
      </Frame>
    );
  }
  const p = preview.data;
  const n = p.rules_ko.length;

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setTouched(true);
    if (!canSubmit) return;
    setBusy(true);
    setSubmitError(null);
    try {
      const out = await api.acceptInvite(
        { token, nickname: signedIn ? null : name, consent_version: CONSENT_VERSION, rules_ack: true },
        session?.token ?? null,
      );
      const bearer = out.token ?? session?.token;
      if (!bearer) throw new Error("no token");
      saveSession({
        userId: out.user_id, token: bearer, nickname: session?.nickname ?? name,
        groupId: out.group_id, tripId: out.trip_id, pending: out.member_status !== "ACTIVE",
      });
      dropInviteToken();
      if (out.member_status === "ACTIVE") router.replace(out.trip_id ? `/trip/${out.trip_id}` : `/group/${out.group_id}`);
    } catch (err) {
      setSubmitError(err);
    } finally {
      setBusy(false);
    }
  }

  const bar = (
    <div className="action-bar__inner">
      {!canSubmit && !busy && <p className="small muted" id="why-disabled">{signedIn ? "아래 두 가지에 체크하면 보낼 수 있어요." : "닉네임을 쓰고 아래 두 가지에 체크하면 보낼 수 있어요."}</p>}
      <button className="btn btn--primary" type="submit" form="join-form" disabled={!canSubmit} aria-describedby={canSubmit ? undefined : "why-disabled"}>
        {busy ? "참여 요청 중…" : "참여 요청 보내기"}
      </button>
    </div>
  );

  return (
    <Frame bar={bar}>
      <form id="join-form" className="stack-lg" onSubmit={submit} noValidate>
        <section className="stack-sm">
          <h1 className="display">{p.group_name}</h1>
          <p className="lede">{p.driver_nickname}님의 차에 함께 타요.</p>
          <FineLine texts={p.disclaimers} remember />
        </section>

        <div className="accs">
          <Accordion title={`정산 규칙 보기 (${n}가지)`} testId="rules-acc">
            {n === 0 ? <p className="muted">운전자가 아직 규칙을 정하지 않았어요.</p> : (
              <ul className="stack-sm" data-testid="rules">
                {p.rules_ko.map((r) => <li key={r} style={{ paddingLeft: "1.25rem", textIndent: "-1.25rem" }}>• {r}</li>)}
              </ul>
            )}
          </Accordion>
        </div>

        <section className="stack" aria-labelledby="who">
          <h2 id="who" className="subtitle">누구로 참여할까요?</h2>
          {signedIn ? (
            <div className="stack-sm">
              <p>이 휴대폰은 <strong>{session?.nickname}</strong>님으로 참여 중이에요.</p>
              <button className="btn btn--text" type="button" onClick={() => void signOut(session?.token)}>다른 이름으로 참여하기</button>
            </div>
          ) : (
            <div className="field">
              <label htmlFor={nickId}>닉네임 <span className="hint">(1~20자, 실명이 아니어도 돼요)</span></label>
              <input
                id={nickId} className="input" value={nickname} maxLength={20} autoComplete="nickname" enterKeyHint="done"
                onChange={(e) => setNickname(e.target.value)} onBlur={() => setTouched(true)}
                aria-invalid={touched && !nameOk} aria-describedby={touched && !nameOk ? `${nickId}-err` : undefined}
              />
              {touched && !nameOk && <p id={`${nickId}-err`} className="field-error">닉네임을 1~20자로 입력해 주세요.</p>}
            </div>
          )}

          <div>
            <label className="check">
              <input type="checkbox" checked={agreeData} onChange={(e) => setAgreeData(e.target.checked)} />
              <span>닉네임과 탑승 구간의 주행거리를 저장하는 데 동의해요. <span className="hint">위치, 연락처, 차량번호는 저장하지 않아요.</span></span>
            </label>
            <label className="check">
              <input type="checkbox" checked={agreeRules} onChange={(e) => setAgreeRules(e.target.checked)} />
              <span>
                {n > 0 ? `정산 규칙 ${n}가지를 확인했고 동의해요.` : "운전자가 정한 정산 규칙에 동의해요."}
                <span className="hint"> 위의 ‘정산 규칙 보기’에서 읽을 수 있어요.</span>
              </span>
            </label>
          </div>
        </section>

        {submitError ? <ErrorState error={submitError} invite /> : null}
      </form>
    </Frame>
  );
}

function Waiting() {
  const router = useRouter();
  const session = useSession();

  const poll = usePolling<MembershipOut>(
    async (_etag, signal) => {
      if (!session?.groupId) return { status: "not-modified" };
      const me = await fetchMyMembership(session.token, session.groupId, signal);
      return { status: "ok", data: me, etag: null };
    },
    { intervalMs: 3000, enabled: Boolean(session?.groupId) },
  );

  useSessionGuard(poll.error);
  const status = poll.data?.status;
  const target = poll.data?.current_trip_id ?? session?.tripId ?? null;   // prefer today's trip over the one saved on this phone
  const declined = status === "REJECTED" || status === "LEFT";
  useEffect(() => {
    if (status !== "ACTIVE" || !session) return;
    updateSession({ pending: false, tripId: target });
    router.replace(target ? `/trip/${target}` : `/group/${session.groupId}`);
  }, [status, session, target, router]);

  if (declined) {
    return (
      <div className="stack" data-testid="rejected">
        <div style={{ display: "grid", placeItems: "center" }}><Moa mood="SLEEPY" size={96} /></div>
        <h1 className="title">이번에는 참여하지 못했어요</h1>
        <p className="muted">운전자가 참여 요청을 받지 않았어요. 사람을 잘못 초대했다면 운전자에게 직접 물어봐 주세요.</p>
        <button className="btn" type="button" onClick={() => updateSession({ pending: false, groupId: undefined, tripId: undefined })}>확인</button>
      </div>
    );
  }
  return (
    <div className="stack-lg" data-testid="waiting">
      <div className="stack">
        <div style={{ display: "grid", placeItems: "center" }}><Moa mood="HAPPY" size={104} live /></div>
        <h1 className="title" aria-live="polite">운전자의 승인을 기다리고 있어요</h1>
        <p className="muted">{session?.nickname}님의 참여 요청을 보냈어요. 운전자가 차량 화면에서 승인하면 이 화면이 자동으로 넘어가고, 바로 ‘탔어요’를 누를 수 있어요. 출발 전까지 이 화면을 열어 두세요.</p>
      </div>
      {poll.error && !poll.loading ? <ErrorState error={poll.error} onRetry={poll.refresh} /> : null}
    </div>
  );
}
