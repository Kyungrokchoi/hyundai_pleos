# MOATA (모아타)

Hyundai/42dot **Pleos Connect** 차량 앱 + 동승자 휴대폰 웹 + 서버로 만든 **지인 그룹 이동 기록 · 구간별 N빵 · 캐릭터 ‘모아’ 성장** 서비스.
공개 유상 카풀 매칭이나 결제·송금 서비스가 아닙니다. 정산 금액은 입력한 연비·단가로 계산한 **추정치(참고용)** 입니다.

> 이 저장소의 모든 검증 상태는 [`docs/verification.md`](docs/verification.md) 가 기준입니다. 실행하지 않은 항목은 *NOT_RUN*, 막힌 항목은 *BLOCKED* 로 적습니다.

## 구성

| 폴더 | 내용 |
|---|---|
| [`car-app/`](car-app) | Kotlin · Jetpack Compose · Room · Pleos Vehicle SDK 2.3.2 (Android Automotive) — [`docs/car-app.md`](docs/car-app.md) |
| [`passenger-web/`](passenger-web) | Next.js · TypeScript 모바일 웹(QR 참가, 탑승/하차, 정산 확인, 모아) — [`docs/passenger-web.md`](docs/passenger-web.md) |
| [`backend/`](backend) | FastAPI · SQLAlchemy · Alembic · PostgreSQL/SQLite — 비용 엔진 `app/services/cost_engine.py` |
| [`contracts/`](contracts) | `openapi.yaml` (서버가 생성, 테스트가 드리프트 검사) |
| [`docs/`](docs) | 결정, 능력 매트릭스, 에뮬레이터 프로비저닝, 검증 기록 |
| [`scripts/`](scripts) | `run_demo.sh`(API+웹), `run_backend.sh`, `demo_e2e.py`(실서버 E2E), `console_heartbeat.sh`(Virtual Console 필수), `adb_ui.py`(에뮬레이터 화면 조작), `check_env.sh` |
| [`apk/`](apk) | 디버그 APK 사본 (릴리스 서명 APK는 서명 키가 없어 만들지 않음) |

문서: [PRD](MOATA_Pleos_Official_PRD_TechSpec_v2.md) · [결정](docs/decisions.md) · [API 계약](docs/API_CONTRACT.md) · [Pleos 능력 매트릭스](docs/pleos-capability-matrix.md) · [에뮬레이터](docs/emulator-provisioning.md) · [검증](docs/verification.md) · [데모 런북](docs/DEMO_RUNBOOK.md) · [법적·개인정보](docs/LEGAL_AND_PRIVACY.md) · [디자인 토큰](docs/design-tokens.md) · [구현 계획](docs/implementation-plan.md)

## 빠른 시작 (백엔드)

```bash
# Python 3.12 + uv 권장. 처음 한 번:
cd backend && uv venv --python 3.12 .venv && uv pip install -r requirements-dev.txt --python .venv/bin/python

# 테스트 (SQLite 인메모리)
.venv/bin/python -m pytest -q

# 서버 (기본 SQLite 파일; PostgreSQL 은 MOATA_DATABASE_URL 지정)
cd .. && scripts/run_backend.sh            # http://0.0.0.0:8000/api/v1/docs

# 실서버 대상 E2E 데모 (DEMO 데이터, 12개 확인)
python3 scripts/demo_e2e.py
```

환경 변수는 [`.env.example`](.env.example) 참고. 비밀값은 저장소에 넣지 않습니다. 휴대폰에서 접속하려면 `MOATA_CORS_ORIGINS` 에 `http://<PC의 LAN IP>:3000` 을 추가하세요(와일드카드 금지).

## 승객 웹 · 차량 앱 · 한 번에 실행

```bash
# 승객 웹 (Node 22+; 처음 한 번 npm ci)
cd passenger-web && npm ci && npm run lint && npm run typecheck && npm test && npm run build && npm run e2e
npm run dev                                   # http://localhost:3000 (API 기본값: 접속한 호스트의 :8000)

# 차량 앱 (JDK 17, Android SDK; Gradle 9.4.1 래퍼 포함)
cd car-app && ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb install -r ../apk/moata-debug.apk         # 에뮬레이터: Pleos Connect v2.1.0 (docs/emulator-provisioning.md)

# 데모 전체 (API :8000 + 웹 :3000, 새 SQLite DB)
scripts/run_demo.sh
```

**디버그 APK:** [`apk/moata-debug.apk`](apk) (= `car-app/app/build/outputs/apk/debug/app-debug.apk`, SHA-256 앞 16자리 `7127e36a0e43cf3f`). 릴리스 서명 키가 없어 릴리스 APK 는 만들지 않았다. 90초 데모 절차와 문제 해결은 [`docs/DEMO_RUNBOOK.md`](docs/DEMO_RUNBOOK.md).

## 검증 요약 (자세한 명령·수치는 [`docs/verification.md`](docs/verification.md))

| 영역 | 결과 |
|---|---|
| 백엔드 | SQLite 101 · **PostgreSQL 16 104** 테스트 통과, Alembic 마이그레이션·OpenAPI 계약 드리프트 검사, 실서버 E2E 12/12 |
| 차량 앱 | 단위 53(가짜 SDK, `VERIFIED_MOCK`) · lint 오류 0 · **실제 Pleos Emulator 2.1.0 에서 실제 SDK 로 전체 흐름 검증**(오도미터 읽기·변경 관측, 주행 제한, 복구) |
| 승객 웹 | 단위 73 · e2e 10 · axe 위반 0 · 디자인 린트 렌더링 스캔 0건 |
| 이 환경의 SDK 한계 | 에뮬레이터는 연비/전비/트립거리를 **제공하지 않음** → 연비는 직접 입력(MANUAL) 필수 |
| 미실행 | 실차·실기기 QR 스캔·Safari, 릴리스 서명, docker compose, NaviHelper, 실제 기프티콘 |

## 데이터 출처 표기 (중요)

모든 판독값과 화면에는 출처가 붙습니다: **PLEOS**(차량 SDK에서 읽음) · **MANUAL**(직접 입력) · **DEMO**(가상 데이터, 화면 상단에 상시 `DEMO / 가상 데이터`). SDK 읽기에 실패하면 Demo로 자동 전환하지 않고 “사용 불가”를 표시하며, 운전자가 직접 입력(MANUAL)해야 정산이 진행됩니다.

## 법적·운영 고지

- 비용 분담의 적법성은 상황에 따라 다르며 이 서비스가 보장하지 않습니다. 영리 목적의 운송이 되지 않도록 지인 그룹 간 비영리 비용 분담으로만 사용하세요.
- 쿠폰은 **시연용 / 실제 교환 불가**입니다. 실제 기프티콘은 별도 계약된 제공사 연동과 승인이 필요합니다.
- 개인정보: 닉네임만 저장(VIN·전화번호·실명·좌표 없음). 그룹 삭제(`DELETE /groups/{id}`)로 관련 데이터를 감사 로그까지 지우며, `python -m app.purge` 를 스케줄에 걸면 보존기간(기본 90일) 지난 운행·빈 그룹·만료 토큰을 삭제합니다. 자세한 내용과 사람이 확인해야 할 법적 항목은 [`docs/LEGAL_AND_PRIVACY.md`](docs/LEGAL_AND_PRIVACY.md).
