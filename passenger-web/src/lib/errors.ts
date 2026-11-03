export class ApiError extends Error {
  readonly status: number;
  readonly code: string | null;
  readonly messageKo: string | null;

  constructor(status: number, code: string | null, messageKo: string | null, detail?: string) {
    super(detail ?? messageKo ?? `HTTP ${status}`);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.messageKo = messageKo;
  }
}

// fetch() itself failed (offline, DNS, CORS, server down).
export class NetworkError extends Error {
  constructor(cause?: unknown) {
    super("network");
    this.name = "NetworkError";
    this.cause = cause;
  }
}

export type ErrorKind =
  | "offline" | "auth" | "forbidden" | "notfound" | "gone" | "conflict" | "validation" | "toolarge" | "server";

export interface ErrorView {
  kind: ErrorKind;
  title: string;
  body: string;
  retryable: boolean;
}

const COPY: Record<ErrorKind, { title: string; body: string; retryable: boolean }> = {
  offline: { title: "연결이 끊겼어요", body: "인터넷이나 서버 연결을 확인하고 다시 시도해 주세요. 기록은 서버에 안전하게 남아 있어요.", retryable: true },
  auth: { title: "다시 참여가 필요해요", body: "이 기기의 참여 정보가 만료되었거나 없어요. 차량 화면의 QR을 다시 스캔해 주세요.", retryable: false },
  forbidden: { title: "볼 수 있는 권한이 없어요", body: "이 이동에 참여 중인 사람만 볼 수 있어요. 운전자가 참여를 승인했는지 확인해 주세요.", retryable: false },
  notfound: { title: "찾을 수 없어요", body: "링크가 올바르지 않거나 이미 삭제된 기록이에요.", retryable: false },
  gone: { title: "초대 링크가 만료되었어요", body: "초대는 잠시 동안만 쓸 수 있어요. 운전자에게 새 QR을 보여 달라고 요청해 주세요.", retryable: false },
  conflict: { title: "지금은 할 수 없는 동작이에요", body: "이동 상태가 바뀌었을 수 있어요. 화면을 새로 고친 뒤 다시 시도해 주세요.", retryable: true },
  validation: { title: "입력을 확인해 주세요", body: "보낸 내용이 올바르지 않아요.", retryable: false },
  toolarge: { title: "내용이 너무 길어요", body: "조금 줄여서 다시 보내 주세요.", retryable: false },
  server: { title: "서버에 문제가 생겼어요", body: "잠시 뒤 다시 시도해 주세요. 계속되면 운전자에게 알려 주세요.", retryable: true },
};

export function describeError(err: unknown, opts: { invite?: boolean } = {}): ErrorView {
  let kind: ErrorKind = "server";
  let serverMessage: string | null = null;

  if (err instanceof NetworkError) {
    kind = "offline";
  } else if (err instanceof ApiError) {
    serverMessage = err.messageKo;
    const s = err.status;
    if (s === 401) kind = "auth";
    else if (s === 403) kind = "forbidden";
    else if (s === 404 || s === 410) kind = s === 410 || opts.invite ? "gone" : "notfound";
    else if (s === 409) kind = "conflict";
    else if (s === 413) kind = "toolarge";
    else if (s === 400 || s === 422) kind = "validation";
    else kind = "server";
  }

  const base = COPY[kind];
  // The server's message is specific enough to show only for validation and conflict.
  const body = serverMessage && (kind === "validation" || kind === "conflict") ? serverMessage : base.body;
  return { kind, title: base.title, body, retryable: base.retryable };
}

// Handles both `{code, message_ko}` and FastAPI's default `{detail: ...}` bodies.
export function parseErrorBody(status: number, body: unknown): ApiError {
  if (body && typeof body === "object") {
    const b = body as Record<string, unknown>;
    if (typeof b.code === "string" || typeof b.message_ko === "string") {
      return new ApiError(
        status,
        typeof b.code === "string" ? b.code : null,
        typeof b.message_ko === "string" ? b.message_ko : null,
        typeof b.detail === "string" ? b.detail : undefined,
      );
    }
    const d = b.detail;
    if (d && typeof d === "object" && !Array.isArray(d)) {
      const dd = d as Record<string, unknown>;
      if (typeof dd.code === "string" || typeof dd.message_ko === "string") {
        return new ApiError(status, (dd.code as string) ?? null, (dd.message_ko as string) ?? null);
      }
    }
    if (typeof d === "string") return new ApiError(status, null, null, d);
  }
  return new ApiError(status, null, null);
}
