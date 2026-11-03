# MOATA API 계약 v1

기계가 읽는 기준은 [`backend/app/schemas.py`](../backend/app/schemas.py) 이고, 서버 구현 후에는 `contracts/openapi.yaml`(FastAPI에서 생성)이 단일 기준이다.
이 문서는 **엔드포인트·권한·호출 순서**를 사람이 읽기 위한 요약이다. Prefix: `/api/v1`.

## 공통 규칙

- 인증: `Authorization: Bearer <token>` (불투명 토큰, 서버는 SHA-256 해시만 저장). 토큰은 `POST /auth/guest` 또는 `POST /invites/accept` 응답에서 한 번만 받는다.
- ID: 클라이언트가 만든 UUID를 쓰는 리소스(`groups`, `trips`, `cost-items`, 판독값 `event_id`)는 **같은 ID 재전송 = 같은 결과**(멱등).
- 금액은 정수 KRW, 거리·단가·소비량은 **십진 문자열**(`"24"`, `"1800"`). 시간은 ISO-8601 UTC.
- 오류: `{ "code": "...", "message_ko": "...", "detail": null }` + HTTP 400/401/403/404/409/413/422.
- 폴링: `GET /trips/{id}/summary` 는 `ETag: "<revision>"` 을 주고 `If-None-Match` 가 같으면 `304`.
- 승객 초대 토큰은 URL **프래그먼트**(`/join#<token>`)로 전달해 서버·프록시 로그에 남지 않게 한다.

## 엔드포인트

| 메서드 | 경로 | 호출자 | 설명 |
|---|---|---|---|
| GET | `/health` | 누구나 | 상태 확인 |
| POST | `/auth/guest` | 누구나 | 닉네임+동의로 임시 게스트 생성(차량 앱 최초 실행) |
| POST | `/groups` | 로그인 | 그룹 생성(호출자=OWNER). `id` 재전송은 멱등 |
| GET | `/groups` | 로그인 | 내 그룹 목록 |
| GET | `/groups/{gid}` | 그룹 ACTIVE 멤버 | 그룹+멤버 |
| DELETE | `/groups/{gid}` | OWNER | 그룹과 모든 운행 데이터 삭제 |
| POST | `/groups/{gid}/invites` | OWNER | 만료되는 초대 토큰 생성(평문 토큰은 이때 한 번만) |
| POST | `/invites/preview` | 누구나 | 토큰으로 그룹명·규칙·고지 미리보기(개인정보 없음) |
| POST | `/invites/accept` | 누구나/로그인 | 닉네임·동의·규칙 확인 → `PENDING` 멤버 + (신규면) 토큰 |
| POST | `/groups/{gid}/members/{uid}/decision` | OWNER | 참가 승인/거절 |
| GET | `/groups/{gid}/character` | ACTIVE 멤버 | 모아 상태(XP·레벨·배지) |
| GET | `/groups/{gid}/history` | ACTIVE 멤버 | 이동 이력 |
| GET | `/groups/{gid}/rewards` | ACTIVE 멤버 | 데모 쿠폰(교환 불가) |
| POST | `/trips` | OWNER | 운행 세션 생성 → `INVITING` |
| GET | `/trips/{tid}/summary` | 그룹 ACTIVE 멤버 | 거리·참가자·비용·정산·데이터 품질 전체 |
| PUT | `/trips/{tid}/cost-rule` | 운전자 | 비용 규칙(연비/전비·단가·운전자 포함·정책) |
| POST | `/trips/{tid}/ready` | 운전자 | `INVITING`→`READY` (규칙·ACTIVE 승객 필요) |
| POST | `/trips/{tid}/start` | 운전자 | `READY`→`IN_PROGRESS`, 첫 주행거리 스냅샷 |
| POST | `/trips/{tid}/readings/batch` | 운전자(차량 앱) | 오프라인 판독값 일괄 동기화(멱등) |
| POST | `/trips/{tid}/participants/{uid}/board` | 본인 또는 운전자 | 탑승 확인(승객은 서버의 최신 주행거리를 경계로 사용) |
| POST | `/trips/{tid}/participants/{uid}/alight` | 본인 또는 운전자 | 하차 확인 |
| PATCH | `/trips/{tid}/intervals/{iid}` | 운전자 | 경계값 교정(사유 필수, 감사 기록) |
| POST | `/trips/{tid}/cost-items` | 운전자 | 통행료/주차비 등 추가(멱등) |
| DELETE | `/trips/{tid}/cost-items/{iid}` | 운전자 | 비용 항목 삭제(감사 기록) |
| POST | `/trips/{tid}/end` | 운전자 | `IN_PROGRESS`→`ENDED`, 마지막 스냅샷. 재호출은 같은 결과 |
| POST | `/trips/{tid}/cancel` | 운전자 | 종료 전 취소 |
| POST | `/trips/{tid}/settlements/preview` | 운전자 | 정산안(리비전) 생성. 입력이 같으면 같은 리비전 반환 |
| POST | `/settlements/{sid}/approvals` | 참가자 본인 | 승인/이의제기. 최신 리비전만 가능 |

## 핵심 호출 순서

```
차량(운전자)                              서버                          승객 웹
POST /auth/guest ─────────────────────▶ 토큰
POST /groups {id,name}
POST /trips {id, group_id, data_mode, cost_rule}   → INVITING
POST /groups/{g}/invites {trip_id}      → token ───QR(/join#token)───▶ POST /invites/preview
                                                                      POST /invites/accept → PENDING + 토큰
POST /groups/{g}/members/{u}/decision APPROVE                         (폴링) GET /trips/{t}/summary
POST /trips/{t}/ready → READY                                         POST …/participants/me/board(=내 user_id)
POST /trips/{t}/start {odometer} → IN_PROGRESS
POST /trips/{t}/readings/batch (주기적)                                POST …/alight (중도 하차)
POST /trips/{t}/cost-items {toll}
POST /trips/{t}/end {odometer} → ENDED
POST /trips/{t}/settlements/preview → REVIEW                          GET summary → 정산안·산식
POST /settlements/{s}/approvals (운전자)                              POST /settlements/{s}/approvals
                          전원 승인 → CONFIRMED, 모아 XP +10(운행당 1회)
```

## 상태 기계

`INVITING → READY → IN_PROGRESS → ENDED → REVIEW → CONFIRMED`, 분기: `CANCELLED`(종료 전), `DISPUTED`(이의제기 발생 → 운전자가 새 리비전 제안 시 `REVIEW` 복귀).
`INTERRUPTED` 는 스키마에만 예약되어 있고 MVP 서버가 만들지 않는다(차량 앱은 로컬 DB로 복구한다).

## 정산 책임 범위

서버가 유일한 정산 권위다. 클라이언트가 보낸 총액·참가자 ID·정규화된 값은 신뢰하지 않으며, 원시 값+단위만 받아 서버가 정규화·계산한다.
정산은 **추정치**이며 결제·송금은 하지 않는다.
