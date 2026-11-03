"use client";
import { useState, type RefObject } from "react";
import { api } from "@/api/client";
import type { TripSummary } from "@/api/types";
import { ApiError } from "./errors";
import type { Session } from "./session";

// "unboard" (잘못 눌렀어요) is an alight with `undo`: the server drops the boarding if the car has not moved since,
// even when the driver's start won the race.
export type RideAction = "board" | "alight" | "unboard";

// Shared by the pinned bar and the status text so they always agree.
export function useRideActions(trip: TripSummary, session: Session, dialog: RefObject<HTMLDialogElement | null>, onDone: () => void) {
  const [busy, setBusy] = useState<RideAction | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [last, setLast] = useState<RideAction | null>(null);

  async function run(kind: RideAction) {
    setBusy(kind);
    setError(null);
    setLast(kind);
    try {
      if (kind === "board") await api.board(session.token, trip.id, session.userId);
      else if (kind === "unboard") await api.alight(session.token, trip.id, session.userId, { undo: true });
      else await api.alight(session.token, trip.id, session.userId);
      dialog.current?.close();
      onDone();
    } catch (e) {
      dialog.current?.close();
      // a retried alight whose first attempt already landed is not a failure
      if (kind !== "board" && e instanceof ApiError && e.code === "NOT_ON_BOARD") onDone();
      else setError(e);
    } finally {
      setBusy(null);
    }
  }
  // board/alight are idempotent on the server, so retry just resends.
  const retry = () => { if (last) void run(last); };
  return { busy, error, retry, run };
}
