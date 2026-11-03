# 구현 계획과 진행 상태

PRD §14의 단계에 맞추되, “계획 후 멈추지 않는다”는 지시에 따라 감사 직후 구현을 시작했다. 상태는 최종 검증 후 `docs/verification.md` 가 기준이다.

## Phase A — 감사 (완료)

- 명세: `MOATA_Pleos_Official_PRD_TechSpec_v2.md` 628줄 전체 정독. 구현 지시문이 언급한 별도 지시 문서 파일은 존재하지 않음.
- 환경: macOS arm64, JDK 17, Android cmdline-tools(플랫폼 36/37.0 only), adb OK, Gradle 래퍼 없음(9.4.1 배포판 캐시만), Node 26, Python 3.14(→3.12 사용), Docker 데몬 꺼짐, PostgreSQL 16 설치됨. `git` 저장소 아님.
- 공식 문서·실제 SDK: 문서 4개 페이지 확인(에뮬레이터 요구사항, 권한 표, 의존성/저장소 좌표). 메서드 단위는 SPA라 읽을 수 없어 **SDK 소스 JAR의 KDoc**으로 확인 → `docs/pleos-capability-matrix.md`.

## Phase B — SDK와 골격 (완료/검증 중)

- Pleos Maven 해결 + AAR 분석 → 차량 앱에서 `Vehicle(context).initialize()` 컴파일 확인.
- 에뮬레이터 프로비저닝 완료(`docs/emulator-provisioning.md`).
- 백엔드/웹은 “한 번의 왕복” 이상으로 구현됨.

## Phase C — E2E 코어 (백엔드 완료)

그룹 → 운행 → QR 초대 → 승인 → 시작 → 탑승/하차 → 판독값 → 종료 → 결정적 정산 → 승인 → 모아. 비용 엔진 테스트를 UI보다 먼저 작성했다.

## Phase D — 캐릭터와 폴리시

모아 진행(XP/레벨/배지/시연 쿠폰)은 서버, 마스코트는 웹(SVG)·차량(Compose Canvas). 디자인 린트는 웹에만 적용(Compose 검증 불가).

## Phase E — 품질 게이트 (`docs/verification.md`)

백엔드 pytest(SQLite/PostgreSQL)·OpenAPI·마이그레이션·실서버 E2E, Android 빌드/린트/단위테스트, 웹 lint/typecheck/build/Vitest/Playwright, 에뮬레이터 AND-01…10, 정확성·AppSec 코드 리뷰.

## 우선순위 맵

| 우선순위 | 항목 | 상태 |
|---|---|---|
| P0 | SDK 능력 스파이크 | 소스·환경 확인 완료, 앱 프로브 실행은 `verification.md` |
| P0 | 안드로이드 앱(그룹/운행/거리/QR) | 구현, 빌드·단위테스트 재검증 |
| P0 | 백엔드, 승객 QR 참가, 양방향 동기화, 구간별 정산, E2E 데모 | 완료(위 검증) |
| P1 | 연비/전비 출처, 수동 조정, 감사 이력, 승인, 오프라인/멱등, 모아 성장, UX | 서버·클라이언트 구현 |
| P2 | NaviHelper, 데모 쿠폰 | 쿠폰(시연용)만 구현. NaviHelper는 미구현(`docs/car-app.md` 참고) |

## 의도적으로 하지 않은 것

결제·송금, 공개 매칭, 실제 기프티콘 발행, 릴리스 서명 APK, 실시간 소켓, 마이크로서비스/큐, 유료 인증, 외부 유가 API.
