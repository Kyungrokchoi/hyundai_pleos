import { act, render, renderHook, screen } from "@testing-library/react";
import { createRef } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { TripSummary } from "@/api/types";
import { SourceBadge } from "@/components/SourceBadge";
import { ApiError, NetworkError } from "@/lib/errors";

const board = vi.fn();
const alight = vi.fn();
vi.mock("@/api/client", () => ({ api: { board: (...a: unknown[]) => board(...a), alight: (...a: unknown[]) => alight(...a) } }));
const { useRideActions } = await import("@/lib/useRideActions");

const trip = { id: "t1" } as TripSummary;
const session = { userId: "u1", token: "tok", nickname: "별이" };

describe("useRideActions: '다시 시도' really sends the failed action again", () => {
  beforeEach(() => { board.mockReset(); alight.mockReset(); });

  it("retry re-runs the same action after a network failure", async () => {
    const done = vi.fn();
    board.mockRejectedValueOnce(new NetworkError()).mockResolvedValueOnce({});
    const { result } = renderHook(() => useRideActions(trip, session, createRef(), done));
    await act(() => result.current.run("board"));
    expect(result.current.error).toBeInstanceOf(NetworkError);
    expect(done).not.toHaveBeenCalled();
    await act(async () => { result.current.retry(); });
    expect(board).toHaveBeenCalledTimes(2);
    expect(board).toHaveBeenLastCalledWith("tok", "t1", "u1");
    expect(result.current.error).toBeNull();
    expect(done).toHaveBeenCalledTimes(1);
  });

  it("'잘못 눌렀어요' is an alight with undo; '내렸어요' is a plain alight; a retried undo still carries undo", async () => {
    const done = vi.fn();
    alight.mockRejectedValueOnce(new NetworkError()).mockResolvedValue({});
    const { result } = renderHook(() => useRideActions(trip, session, createRef(), done));
    await act(() => result.current.run("unboard"));
    await act(async () => { result.current.retry(); });
    expect(alight).toHaveBeenNthCalledWith(1, "tok", "t1", "u1", { undo: true });
    expect(alight).toHaveBeenNthCalledWith(2, "tok", "t1", "u1", { undo: true });
    await act(() => result.current.run("alight"));
    expect(alight).toHaveBeenLastCalledWith("tok", "t1", "u1");
    expect(done).toHaveBeenCalledTimes(2);
  });

  it("a retried alight whose first attempt already landed counts as done, not as an error", async () => {
    const done = vi.fn();
    alight.mockRejectedValueOnce(new NetworkError()).mockRejectedValueOnce(new ApiError(409, "NOT_ON_BOARD", "탑승 중이 아닙니다."));
    const { result } = renderHook(() => useRideActions(trip, session, createRef(), done));
    await act(() => result.current.run("alight"));
    await act(async () => { result.current.retry(); });
    expect(alight).toHaveBeenCalledTimes(2);
    expect(result.current.error).toBeNull();
    expect(done).toHaveBeenCalledTimes(1);
  });
});

describe("SourceBadge: car data gets no chip, other sources are flagged with their code and plain Korean", () => {
  it.each([["MANUAL", "MANUAL · 직접 입력"], ["DEMO", "DEMO · 가상 데이터"]] as const)("%s", (src, text) => {
    render(<SourceBadge source={src} testId={`b-${src}`} />);
    const el = screen.getByTestId(`b-${src}`);
    expect(el.textContent).toBe(text);
    expect(el.getAttribute("data-source")).toBe(src);
  });
  it("PLEOS renders nothing", () => {
    const { container } = render(<SourceBadge source="PLEOS" testId="b-PLEOS" />);
    expect(container.innerHTML).toBe("");
  });
});
