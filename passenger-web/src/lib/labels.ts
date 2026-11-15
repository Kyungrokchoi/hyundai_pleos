import type { Allocation, ApprovalState, DataSource, EnergyPolicy, ItemKind, MemberStatus, TripState } from "@/api/types";

export const TRIP_STATE: Record<TripState, { label: string; hint: string }> = {
  DRAFT: { label: "준비 중", hint: "운전자가 이동을 만들고 있어요." },
  INVITING: { label: "모이는 중", hint: "차에 탔으면 ‘탔어요’를 눌러 주세요. 출발하면 여기서부터 계산돼요." },
  READY: { label: "출발 준비 완료", hint: "차에 탔으면 ‘탔어요’를 눌러 주세요. 출발하면 여기서부터 계산돼요." },
  IN_PROGRESS: { label: "이동 중", hint: "내릴 때 ‘내렸어요’를 눌러 주세요." },
  ENDED: { label: "도착", hint: "운전자가 정산안을 만들고 있어요." },
  REVIEW: { label: "정산 확인 중", hint: "내 몫을 보고 승인해 주세요." },
  CONFIRMED: { label: "금액 합의 완료", hint: "모두 금액을 승인했어요. 송금은 각자 평소 쓰는 앱으로 해요." },
  INTERRUPTED: { label: "잠시 멈춤", hint: "기록이 중단되었어요. 운전자에게 문의해 주세요." },
  CANCELLED: { label: "취소됨", hint: "이 이동은 취소되었어요." },
  DISPUTED: { label: "이의 제기됨", hint: "운전자가 정산안을 다시 만들 거예요." },
};



export const DATA_SOURCE: Record<DataSource, { code: DataSource; label: string; explain: string }> = {
  PLEOS: { code: "PLEOS", label: "차량에서 읽은 값", explain: "차량이 알려 준 주행거리를 그대로 기록했어요." },
  MANUAL: { code: "MANUAL", label: "직접 입력", explain: "운전자가 직접 적은 값이에요." },
  DEMO: { code: "DEMO", label: "가상 데이터", explain: "연습용 가상 데이터예요. 실제 차량 측정값이 아니에요." },
};

export const MEMBER_STATUS: Record<MemberStatus, string> = {
  PENDING: "승인 대기", ACTIVE: "참여 중", REJECTED: "거절됨", LEFT: "나감",
};

export const APPROVAL: Record<ApprovalState, string> = {
  PENDING: "승인 전", APPROVED: "승인했어요", DISPUTED: "이의 있어요",
};

export const ITEM_KIND: Record<ItemKind, string> = { TOLL: "통행료", PARKING: "주차비", OTHER: "기타" };

export const ALLOCATION: Record<Allocation, string> = {
  EQUAL: "참여자 균등", DISTANCE_WEIGHTED: "탄 거리 비례", DRIVER_ONLY: "운전자 부담",
};

export const ENERGY_POLICY: Record<EnergyPolicy, string> = {
  SEGMENT_SHARED: "구간마다 함께 탄 사람끼리 나눔(탄 거리 반영)",
  EQUAL_SPLIT: "전체 연료·전기 비용을 참여자가 똑같이 나눔",
};

export function stateLabel(s: TripState): string {
  return TRIP_STATE[s]?.label ?? s;
}
