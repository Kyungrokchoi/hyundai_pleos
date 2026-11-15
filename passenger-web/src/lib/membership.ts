import { useEffect } from "react";
import { api } from "@/api/client";
import type { MembershipOut, MemberStatus } from "@/api/types";
import { updateSession, type Session } from "@/lib/session";
import { useLoad } from "@/lib/useLoad";


export async function fetchMyMembership(token: string, groupId: string, signal?: AbortSignal): Promise<MembershipOut> {
  return api.myMembership(token, groupId, signal);
}

export async function fetchMyStatus(token: string, groupId: string, signal?: AbortSignal): Promise<MemberStatus> {
  return (await fetchMyMembership(token, groupId, signal)).status;
}



export function useCurrentTrip(session: Session | null | undefined): string | null | undefined {
  const groupId = session && !session.pending ? session.groupId ?? null : null;
  const res = useLoad<MembershipOut>(
    groupId && session ? (signal) => fetchMyMembership(session.token, groupId, signal) : null,
    [session?.token ?? null, groupId],
  );
  const current = res.data ? res.data.current_trip_id ?? null : undefined;
  useEffect(() => {
    if (current && session && current !== session.tripId) updateSession({ tripId: current });
  }, [current, session]);
  return current;
}
