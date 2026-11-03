# 검증 기록 (Verification Evidence)

날짜 2026-11-02 · macOS 26 (Darwin 25.6, arm64) · JDK 17.0.20 · Gradle 9.4.1 · AGP 9.2.1 · Node v26.0.0 · Python 3.12.13(uv) · PostgreSQL 16.14(Homebrew) · Android Emulator 37.1.11 · Pleos Connect Emulator 2.1.0 (API 34)

상태: **PASS** / **FAIL** / **NOT_RUN** / **BLOCKED**. 데이터 성격은 `VERIFIED_REAL_SDK`(실제 에뮬레이터에서 앱이 SDK 호출) · `VERIFIED_MOCK`(가짜/픽스처) · `VERIFIED_ENV`(환경 사실)로 구분한다.

## 0. 한눈에 보기

| 영역 | 명령 | 결과 |
|---|---|---|
| 백엔드 테스트 SQLite | `cd backend && .venv/bin/python -m pytest -q` | **101 PASS**, 3 skipped(PostgreSQL 전용 동시성) |
| 백엔드 테스트 **PostgreSQL 16** | `MOATA_DATABASE_URL=postgresql+psycopg://moata@127.0.0.1:54329/moata_test … pytest -q` | **104 PASS**, 0 skipped |
| 마이그레이션 | `alembic upgrade head` → `check` → `downgrade base` → `upgrade head` (PG) / `upgrade`+`check` (SQLite) | **PASS**, 드리프트 없음(17 테이블, 부분 유니크 인덱스 포함) |
| OpenAPI | `test_openapi_is_valid_and_matches_committed_contract` | **PASS** (`openapi-spec-validator`, 커밋된 `contracts/openapi.yaml` = 앱 생성본) |
| 린트(백엔드) | `uvx ruff check app tests --select F` | **PASS** (미사용/미정의 0) |
| 실서버 E2E | `scripts/demo_e2e.py` (uvicorn+PostgreSQL, uvicorn+SQLite `run_demo.sh`) | **PASS** 12/12 ×2 |
| 차량 앱 빌드·테스트·린트 | `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` | **PASS** — 단위 테스트 **53**(실패 0, `VERIFIED_MOCK`), lint 오류 0/경고 15 |
| **차량 앱 + 실제 SDK, 실제 에뮬레이터** | 프로브 + 전체 흐름(§3) | **VERIFIED_REAL_SDK** — AND-01~10 |
| 승객 웹 | `npm run lint / typecheck / test / build / e2e` | **PASS** — 단위 **73**, e2e **10 PASS**(1 선택 스펙 skipped), axe WCAG 2A/2AA/2.1AA 위반 0 |
| 디자인 린트(웹) | 소스 스캔 `src/` · 렌더링 스캔 `http://…/` · `/join` | 소스 0건(정규식 기반 과소 집계), 렌더링 `/`·`/join` **0건**(초기 `cream-palette` 1건 → 바탕색 변경으로 해소) |

## 1. 백엔드

테스트 구성(SQLite 기준 개수): 비용 엔진 26(PRD SET-01…10 + hypothesis 속성 3종) · API 흐름 15 · 보안/계약 16 · AppSec 회귀 28 · 정확성 리뷰 회귀 16 · PostgreSQL 동시성 3.

PRD 인수 조건 ↔ 테스트:

| 인수 조건 | 테스트 |
|---|---|
| 두 승객이 초대 운행에 참가, 거리 변화, 모두 같은 기록 | `test_it01_…`, 실서버 E2E |
| 24 km·12 km/L·1,800원/L·통행료 2,400원·3명 → **6,000원**, 2,000원씩, 반올림 보존 | `test_set01…`, `test_it01…`(+ 속성 테스트: 합계 보존/음수 없음/입력 순서 무관) |
| 승객 중도 하차 → **2,350 / 2,350 / 1,300**, 구간 근거 | `test_set03…`, `test_passenger_leaves_halfway…` |
| EV/ICE 별도 단위·출처 라벨 | `test_ev_and_ice_use_separate_units…`, `test_pleos_efficiency_comes_from_sdk_unit_l_per_100km` |
| 잘못된 값·SDK 권한/속성 없음 → 우아하게 저하 | `test_sdk_unavailable_degrades_gracefully…`, `test_sensor_glitches…`, `test_absurd_numbers…` |
| 종료·승인·판독값 재시도가 정산/XP 를 두 배로 만들지 않음 | `test_duplicate_finish…`, `test_it03…`, `test_two_trips_confirming_at_once…`, PG 동시성 3종 |
| 시뮬레이션 값이 실측으로 표시되지 않음 | `test_demo_data_is_labelled…`, `SOURCE_MISMATCH`, `EFFICIENCY_SOURCE_MISMATCH` |
| 인증·권한(IT-02, IT-08), 삭제, 보존 | `tests/test_security.py`, `tests/test_hardening.py` |

### 검증이 잡은 실제 결함 (수정 완료, 회귀 테스트 있음)

| 발견 경로 | 결함 |
|---|---|
| SET-01 테스트 | 산식 표시가 `18원/L` (`rstrip("0")` 이 정수 문자열의 0까지 제거) |
| **PostgreSQL 실행** | 감사 `entity_id VARCHAR(64)` 에 `trip:item` 결합 ID 가 들어가 길이 초과(SQLite 는 길이를 검사하지 않아 통과) |
| 정확성 리뷰 | EQUAL_SPLIT 이 빈 차 구간 비용을 승객에게 부과, 낡은 락 상태(PG 에서 이중 종료/확정 후 DISPUTE 가능), XP 갱신 소실, 0 km 탑승으로 XP, 같은 `event_id` 로 시작 주행거리 오염, 한 그룹에 진행 중 운행 2개, 오타로 운행 종료 불가, ETag 가 stale 경고를 가림, 연대 0001 타임스탬프 500 등 11건 |
| AppSec 리뷰 | 청크 본문 한도 우회, naive 타임스탬프 500, 수치 상한 없음(저장 후 요약 영구 500), CostItem 전역 PK 존재 오라클, 감사/보존 잔여물, 상태 전이 교착 등 |
| **실제 에뮬레이터** | 차량 앱이 메인 스레드에서 네트워크 본문을 읽어 `NetworkOnMainThreadException` 크래시(JVM 단위 테스트의 가짜는 통과) |

## 2. 에뮬레이터 / 환경 (`docs/emulator-provisioning.md`)

Pleos Connect Emulator 2.1.0 / API 34 / Automotive 부팅(`release_connect_v2.1.0`), RAM 4GB, Cold boot. 차량 서비스 2.3.22(≥2.3.13), `CAR_DRIVE`/`CAR_ENERGY` `protectionLevel:normal`. Virtual Console 은 **브라우저 하트비트(`POST /browser/session`)가 있어야** 쓰기가 된다(없으면 503 `emulator_inactive`).
`sdkmanager` 가 사용자 업데이트 사이트를 읽지 못해 이미지를 수동 설치(SHA-1 일치 확인).

## 3. 실제 SDK · 실제 에뮬레이터 (VERIFIED_REAL_SDK)

상세·원시 값은 [`pleos-capability-matrix.md` §4–5](pleos-capability-matrix.md), 화면 증거는 `docs/evidence/car-*.png`, 프로브 JSON 은 `docs/evidence/probe-result-emulator-2.1.0.json`.

| 확인 | 결과 |
|---|---|
| SDK 읽기 | 오도미터 `12400.0 km`, 리스너·1초 폴링 getter 모두 콘솔 변경(12410, 12424.5)을 관측. SoC `100 %` |
| 미지원 | 연비·전비·트립거리 7개 호출 `ERROR_NOT_SUPPORTED_PROPERTY` / `IllegalArgumentException` → 앱 “직접 입력” 요구, Demo 자동 전환 없음 |
| 전체 흐름 | 로그인 → 그룹 → 운행 생성(서버 `PLEOS`) → QR(화면 캡처를 디코딩해 `http://10.0.2.2:3000/join#…` 확인) → 승객 2명 가입 → 차량에서 승인 → 시작(서버 `start_km=12500`, 판독값 `PLEOS/VERIFIED/sdk 2.3.2`) → 승객 탑승, 콘솔 +10 km, 지호 하차(서버가 경계 `12500→12510 VERIFIED` 기록) → 종료 `12524` → 통행료 2,400 → 정산 **2,350/2,350/1,300** → 차량+폰 승인 → **CONFIRMED**, 모아 XP 10, 배지 3 |
| 복구(AND-08) | 크래시·재설치 후 서버 운행 1개 유지, 화면 `운행 중 · 1명 · 24.0 km` 복원 |
| 주행 제한(AND-10) | 콘솔 Drive + 40 km/h → `주행 중 · 기능 제한`, 정적 모아 + “정차 후에 이용할 수 있어요”만. Park 복귀 시 복원 |
| 401 | 서버가 토큰을 잃어도 크래시 없이 “로그인이 만료되었어요” |

한계: 정산은 연비가 MANUAL 인 구성이다(에뮬레이터가 연비를 주지 않음). 연비 단위(L/100km, km/kWh)는 **값으로 관측하지 못했고** KDoc 및 단위 테스트(VERIFIED_MOCK)에 근거한다.

### 3-b. 재실행 (같은 날, 에뮬레이터 재부팅 후 새 설치·새 DB, **승객은 실제 웹 브라우저로 조작**)

증거: `docs/evidence/rerun/` (20 파일). 주행거리 `34200 → 34224`, 지호가 `34216`에서 하차.

| 단계 | 결과 |
|---|---|
| 프로브 | `34000.0` 읽음, 리스너 `34000 → 34037.5 → 34100`, getter 동일, 연비/전비 미지원(`ERROR_NOT_SUPPORTED_PROPERTY`) — `docs/evidence/probe-result-rerun.json` |
| 승객 가입 | 폰 크기(412×915) Chromium 에서 QR 주소로 가입 → 차량 화면에 요청이 자동으로 뜨고 차량에서 승인 |
| 운행 | 서버 `IN_PROGRESS / PLEOS / start 34200 / sdk 2.3.2`; 승객이 웹에서 `탑승했어요`, 폰 화면 `이동 중 · 차량 실측`; 지호 웹에서 `내렸어요` → 구간 `34200→34216 VERIFIED, closed_by USER` |
| 정산 | 차량·폰 동일: **2,200 / 2,200 / 1,600 = 6,000원**. 검산: 24 km ÷ 12 × 1,800 = 3,600, 16 km(3명) 2,400→800씩, 8 km(2명) 1,200→600씩, 통행료 800씩 |
| 확정 | 폰 2명 + 차량 승인 → `CONFIRMED`, 모아 XP 10, 배지 `FIRST_RIDE`·`TRIO`·`CLEAN_SPLIT`, 보상 이벤트 1건 |
| 주행 제한 | Drive+40 km/h → `주행 중 · 기능 제한`, Park 복귀 시 복원 |
| 크래시 | 이 실행의 crash 버퍼 0건 (수정 전 2건) |

**이 재실행에서 발견·수정한 결함:** `scripts/run_demo.sh` 가 e2e 가 마지막에 만든 `.next` 빌드(API 주소 `:8010` 이 **빌드 시점에 박힘**)를 재사용해 폰 웹이 서버에 접속하지 못했다(“연결이 끊겼어요”). e2e 가 `NEXT_DIST_DIR=.next-e2e` 로 별도 빌드하도록 분리하고, 데모는 자기 빌드 표식(`.next/.demo-build`)이 있을 때만 재사용한다. 분리 후 e2e 10개 재통과, e2e 실행 중에도 데모 웹 서비스 유지.

### 3-c. UX 재설계 후 재실행 (사용자 피드백)

증거 `docs/evidence/redesign/` (전/후 비교는 `docs/evidence/rerun/`). 새 APK 로 앱 데이터를 비운 **처음 사용 상태**에서 전체 흐름을 다시 진행했다: 로그인 → 그룹 → `함께 타기`(유가 `1800` 숫자패드) → QR → 승객 2명 웹 가입 → 승인 → 출발(`50100`) → 탑승/주행(`50108`, `50116`)/지호 하차 → `50124` 도착(확인 창) → 연비 `12`(숫자패드) → 통행료 `2400` → 정산 **2,200 / 2,200 / 1,600 = 6,000원** → 차량+폰 승인 → `CONFIRMED`, 모아 XP 10 → 정원 → Drive+40 km/h 제한 → Park 복원.

| 확인 | 결과 |
|---|---|
| 차량 앱 | 단위 53 PASS, lint 오류 0, 백엔드 103 PASS(+PG 전용 3 skipped) |
| 연비 미지원 경로(`ensureEfficiency`/수동 입력) | **VERIFIED_REAL_SDK**: 이 에뮬레이터는 연비를 주지 않아 요약 화면의 `연비 입력` 카드 → 숫자패드 → `12 km/L · 직접 입력한 값` 저장 |
| 찾아 고친 결함 | ① SDK 오류 원문이 사용자 대화창에 노출 ② `PUT /cost-rule` 응답 타입 오류(`BAD_RESPONSE`) ③ `0명 참여` 표기 ④ 이동 기록 `24.000 km` |
| 사용성 측정 | `함께 타기` 이후 QR까지 설정 결정 **10여 개 → 1개(유가)**, 재방문 시 **0개**(기억). 화면당 주 버튼 1개 |

**승객 웹 재설계(게이트를 다시 실행해 확인):** 정산서 높이 **2,561 → 957 px**(상세 펼치면 2,086), 승인 버튼은 항상 화면에 고정, 고지 전문은 `자세히`/`안내 보기` 한 곳, 가입 1,148 → 915 px, 진행 1,167 → 957 px, 노랑은 화면당 주 버튼 1개. lint·typecheck pass, 단위 **89**, e2e **11 PASS**(1 skipped), axe 위반 0, 정산서 ≤1,100 px·승인 버튼 첫 화면 고정 검사 추가, 디자인 린트 `/`·`/join` 0건. 증거 `docs/evidence/web-v2/`. 실제 휴대폰(iOS 안전 영역 등)에서는 NOT_RUN.

**한계:** “편해졌다”는 판단은 아직 제 해석이다. 실제 운전자 사용성 시험을 하지 않았다(NOT_RUN).

### 3-d. 리뷰 기반 UX 진단 → 수정 → 재실행 (2026-11-02, UX v3)

**진단(실제로 실행):** 디자인 리뷰(웹: 디자인 리뷰와 디자인 린트·브라우저 증거를 서로 분리해 실행 · 차량: 네이티브 단일 리뷰), 차량 디자인 감사(네이티브 기준), UX 리뷰, 접근성 리뷰. 리뷰어에게는 자기평가 문서를 막고 화면·코드만 줬다. 전부 전문가 관점 리뷰이며 실사용자 시험이 아니다.

**코드로 확인한 핵심 결함 → 수정:**

| 결함 | 수정 | 검증 |
|---|---|---|
| P0 `출발하기` 한 번에 READY→IN_PROGRESS → 출발 전 `탑승` 기록 불가, 안 누르면 정산에서 빠짐 | 서버: INVITING 에서도 탑승(`PRE_DEPARTURE`), 출발 전 하차=취소. 차량: 사람별 `탔어요/아직`, 출발 시 `지호님도 탔나요?` → 운전자 대리 표시(outbox 로 ready 전에 전송, 감사 `by_driver`) | pytest 3개 추가 · **VERIFIED_REAL_SDK** 실행(아래) |
| 주행 중에도 오류 배너 `다시 시도`가 승인·정산·규칙 변경을 실행 | `parked()` 를 데이터 변경 함수 전부에 재확인, 주행 중 배너는 버튼 없는 읽기 전용 | 코드 + 주행 중 화면(30) |
| 출처 표기 규칙 위반(차량 정산은 연비가 수동인데 `차량에서 읽은 값`만, 웹 정산서 상단엔 출처 없음) | 값마다 출처, 문자 코드 `PLEOS/MANUAL/DEMO` + 한국어(차량 `숫자의 출처`, 웹 출처 줄) | 화면 24·25 |
| 원시 오류(`200 BAD_RESPONSE`, SDK 예외) 노출, 401 의 `다시 시도`=로그아웃 | 상세는 logcat 만, `다시 로그인` 라벨 | logcat `MOATA: offline: …` 확인 |
| 한 번 탭으로 되돌릴 수 없는 동작(운행 취소·거절·항목 삭제) | 확인 창(위험 색) | 화면 09b, 삭제 창 |
| 배너 버튼 대비 2.06/1.76:1 | 잉크 글자·테두리, 대비 테스트에 쌍 추가 | 단위 테스트 |
| 웹 `다시 시도` 가 재전송 안 함, `정산 완료` 오해 | 실제 재실행, `금액 합의 완료` + 금액 복사 + “돈은 나가지 않아요” | e2e |
| 정체성(구간이 그림으로 안 나옴) | **구간 막대**(차량 요약·정산, 웹 이동·정산서) | 화면 20·24·25 |

**재실행(실제 에뮬레이터 + 실제 웹, 승객 폰은 Playwright 412px):** 첫 사용 상태 → 그룹 → 유가 `1800` → QR(화면 이미지를 ZXing 으로 디코딩해 가입, 주소 글자 없음) → 2명 가입 → 거절 확인 창 확인 후 취소 → 승인 → **서연은 정차 중 폰에서 `탔어요`**, 지호는 안 누름 → `출발하기` → `지호님도 탔나요?` → `모두 탔어요 · 출발`(`50200`) → Drive·40 km/h 에서 `16.0 km`, 버튼 없음 → 지호 폰 `내렸어요`(`50216`) → `50224` Park → 도착 확인 → 요약(구간 막대, 지호 `16km에서 내림`) → 연비 `12`(MANUAL) → 통행료 `2400` → 정산 **2,200 / 2,200 / 1,600 = 6,000원**, 출처 4줄 → 차량·폰 승인 → `3/3명 승인`, 웹 `금액 합의 완료`·`1,600원 복사` → 정원 이력 `24 km · … · PLEOS`(`24.000` 해소) → Drive 에서 `정차 후에 이용할 수 있어요`. 증거 `docs/evidence/v3/`(30장+폰), 웹 단독 `docs/evidence/web-v3/`.

| 게이트 | 결과 |
|---|---|
| 백엔드 pytest | **106 PASS**, 3 skipped(PG 전용) |
| 차량 단위 / lint | **61 PASS** / 오류 0(경고 22, 이전과 동일 수) |
| 웹 lint·tsc / Vitest / Playwright | 0 / **103 PASS** / **12 PASS**, axe(`wcag22aa` 포함) 위반 0 — VERIFIED_MOCK(웹 작업 보고, 차량은 스크립트) |
| 디자인 린트 | 소스·`/`·`/join` 0건(정규식 기반 과소 집계 한계, 정산 화면은 토큰 필요로 미스캔) |

**실행 중 만난 환경 문제:** 에뮬레이터 Wi-Fi 끊김(설정 화면에서 `AndroidWifi` 재연결), 데모 백엔드가 옛 코드로 떠 있어 출발 전 탑승이 거절됨(같은 DB 로 재시작) — `DEMO_RUNBOOK.md` §3 에 기록.

**여전히 NOT_RUN:** TalkBack/VoiceOver 실제 낭독, 큰 글꼴(font_scale 1.3) 검사, 실제 운전자 사용성 시험(계획: `docs/usability-test-plan.md`), 실기기 폰.

### 3-e. 재진단(같은 방식) → 2차 수정

**재진단(독립 리뷰, 이전 점수·문서 비공개):** 차량·웹 디자인 리뷰 재실행(웹 디자인 린트 0건). 둘 다 구간 막대를 "이 제품만의 장면"으로 인정. 새로 나온 P1: 웹 정산서 내역 열이 합계와 안 맞아 보임, 통행료 분배 이유 없음, **운전자가 대신 표시한 탑승이 승객에게 안 보임**(제가 "기록에 남는다"고 한 것은 감사 로그에만 있었음), 차량 이의 제기 후 막다른 길, 출처 표기 누락(주행 화면·초대 규칙 줄·비용 항목). 평가자가 `20-summary.png` 가 앱이 아니라 Wi-Fi 설정 화면임을 잡아냄(재설치 직후 캡처, 제가 확인하지 않음) → 다시 찍음.

**코드 리뷰(규칙: 보고 후 사용자 승인 → 전부 적용):** 스크래치 탐침 테스트로 재현된 결함 ① 취소된 운행에 `탑승 중` 표시 ② 그룹 만들기 재시도 시 중복 그룹(재시도마다 새 UUID) ③ 출발 직후 도착한 `잘못 눌렀어요`가 0 km 탑승으로 남아 통행료 청구 ④ 승객이 먼저 누른 뒤 운전자 표시가 도착하면 감사 로그가 `by_driver: true` 로 오기록 ⑤ 차량 거리 계산 중복 ⑥ 출발 확인 창 명단이 4초 폴링으로 바뀜 ⑦ 이미 성공한 재시도를 오류로 표시.

**수정:** 서버 `opened_by`(USER/DRIVER) 컬럼 + Alembic `0002`(upgrade·`alembic check` 통과, `demo.db` 는 `create_all` 산이라 같은 `ALTER TABLE` 을 백업 후 적용), `on_board` 는 진행 중 상태에서만, `BoardingIn.undo` — 차가 움직이기 전의 `잘못 눌렀어요`만 탑승 자체를 지움(평범한 `내렸어요`는 0 km 기록으로 남아 운전자가 고칠 수 있음: 처음 시도한 "0 km 면 무조건 삭제"는 기존 정정 테스트 4개를 깨서 좁힘), 감사는 구간이 실제로 생길 때만. 차량: 재시도 같은 id, `alreadyDone(MEMBER_NOT_PENDING / TRIP_LOCKED)`, 출발 확인 명단 고정, 이의 시 `이의가 있어요` + `비용 고치기`(운전자 자기 이의 버튼 제거), 운전자 `받을 금액`, 출처 코드 추가, 모아 바퀴 색(잉크 위 잉크로 안 보였음), 운전 상태 칩 live region, `삭제/바꾸기` 이름에 대상. pytest **110 PASS**(+3 skipped), 차량 단위 **62 PASS**, lint 오류 0.

### 3-f. 2·3차 실제 실행 — 같은 그룹의 두 번째 운행 (증거 `docs/evidence/v3b/`)

처음 두 실행은 늘 **새 그룹**이었다. 같은 그룹으로 다시 타 보니 테스트에 없던 결함이 나왔다.

| 발견 | 원인 | 수정 | 검증 |
|---|---|---|---|
| **P0 돌아온 승객이 오늘 운행에 못 들어감** — 폰 홈의 `이어서 보기`와 새 QR 모두 지난주(확정된) 운행으로 보냄 | 폰은 마지막으로 가입한 운행 ID 만 기억, API 에 그룹의 현재 운행을 알려 줄 길이 없음 | `GET /groups/{id}/me` 에 `current_trip_id`(ACTIVE 멤버만). 웹: 홈 `오늘 이동 보기`, 승인 대기 → 오늘 운행, 끝난 운행 화면에 `새 이동이 시작됐어요 → 오늘 이동 열기` | pytest 2개, Vitest 1개, 실제 화면 13·02 |
| 출발 확인 창이 "모두 탔어요 / 모두 빼고" 뿐 | 한 명만 대신 표시할 수 없음(폰을 잃은 승객 등) | 초대 목록 사람마다 `탔어요로 표시`(감사 `by_driver`) | 화면 08·09 |
| **SDK 가 출발 주행거리를 `0` 으로 성공 반환** → 서버에 시작 0 저장, 도착 50,232 km 로 서버가 422 거절, 차량은 `50232km를 함께 왔어요` 표시 + `보내지 못한 기록 1건` 에서 빠져나올 길 없음 | 앱 재시작 직후 `getCurrentDistanceDriven` 이 0 을 돌려준 것으로 보임(logcat 이 이미 회전해 원인 확정 못 함) | 출발/도착 주행거리가 0 이거나 이 차가 이미 보고한 값보다 작으면 0.7초 간격 3회 재측정 후에도 이상하면 **막고** 다시 시도 요청(임의 대체 없음). 운전자가 `이 운행 닫기` 하면 그 운행의 서버 거절 기록을 정리하고 몇 건인지 알림 | 단위 테스트 2개, 실제로 정리 메시지 확인. 막힌 운행은 운전자 토큰으로 API `cancel` (DB 직접 수정 안 함) |
| 승객 폰 세션이 로그아웃됨 | **제 자동화 스크립트**가 `참여 요청 보내기` 대신 `다른 이름으로 참여하기`를 눌렀다 | 제품 결함 아님. 대신 "기기를 잃은 멤버는 확정을 막을 수 있다(운전자가 멤버를 뺄 수 없음)"는 한계를 확인 → 잔여 위험 | 폰은 `서연`으로 새로 가입 |

**3차 실행:** 돌아온 지호(QR 재스캔 없이 오늘 운행), 새로 가입한 서연 → 서연은 폰에서 `탔어요`, 지호는 운전자가 `탔어요로 표시` → 폰에 `운전자가 대신 탑승으로 기록했어요`, 차선에 `운전자가 기록` → 출발(기기 없는 옛 멤버는 제외) → 8 km(`50232`→`50240`, 시작값 정상) → 연비 12 → 정산 1,200원 → 지호 폰에서 이의 `주차비 1,000원이 빠졌어요` → 차량 `이의가 있어요` + `비용 고치기` → 주차비 1,000 → 정산안 2차 **2,200원 = 734 + 733 + 733**(합 보존) → 3/3 승인 → `금액 합의 완료`. **VERIFIED_REAL_SDK**(차량), 승객은 실제 웹(Playwright).

**게이트(최종):** pytest **112 PASS**(+3 PG 전용 skipped) · 차량 단위 **64 PASS**, lint 오류 0 · 웹 lint·tsc 0, Vitest **109**, Playwright **13 PASS**(axe `wcag22aa` 포함 0) · 디자인 린트 0건.

## 4. 승객 웹 (`docs/passenger-web.md`)

Next 16.4.4 / React 19.3.1 / TS 5.9.3. e2e 는 Playwright(데스크톱 Chromium + Pixel 7 에뮬레이션)로 **자체 백엔드(:8010, 임시 SQLite)와 프로덕션 빌드(:3100)** 를 띄워 실행: QR 가입 → 승인 → 탑승 → 거리 → 중도 하차 → 정산 → 승인 → 모아 +10 XP, 이의 제기, DEMO 배너, 만료 초대, 비로그인/타인, 연결 끊김 복구, 접근성(axe), 토큰 폐기. 초대 토큰이 어떤 요청 URL 에도 나타나지 않음을 검사.

## 5. NOT_RUN / BLOCKED (정직한 목록)

| 항목 | 상태 | 이유 |
|---|---|---|
| 실제 차량, 실제 폰 카메라로 QR 스캔, iOS/Safari·실기기 브라우저 | **NOT_RUN** | 환경 없음. QR 은 이미지 디코딩으로 유효성만 확인 |
| 연비/전비 SDK 값(단위 관측) | **NOT_RUN** | 이 에뮬레이터가 미지원(실제 확인) |
| `docker-compose.yml` | **NOT_RUN** | Docker 데몬이 꺼져 있음(같은 스키마는 Homebrew PG16 으로 검증) |
| 릴리스 서명 APK | **BLOCKED** | 서명 키 없음(만들지 않음). 디버그 APK 만 있음 |
| NaviHelper(P2), 실제 기프티콘 | **미구현** | PRD P2·계약 필요 |
| 의존성 취약점 스캔(`pip-audit`) | **NOT_RUN** | 네트워크 감사 도구를 쓰지 않음. 웹 `npm audit --omit=dev` 0건 |
| Compose 화면의 자동 디자인 검사 | **불가** | 웹 디자인 린트는 네이티브 Compose 를 검증하지 못함 → 수동 대비·크기 검토 + `ThemeContrastTest` |
