# MOATA 차량 앱 (`car-app/`) — 구현·검증 기록

패키지 `io.moata.pleos` · Kotlin · Jetpack Compose · Room · Pleos Vehicle SDK `ai.pleos.playground:Vehicle:2.3.2`
작성 범위: 차량 앱만(백엔드·승객 웹은 별도). 상태 표기는 `VERIFIED_REAL_SDK` / `VERIFIED_MOCK` / `NOT_RUN` / `BLOCKED` 만 사용한다.

## 1. 상태 요약 (실제로 실행한 것만 통과로 적음)

> 이 절은 에뮬레이터 없이 처음 작성한 뒤 **2026-11-02 실제 Pleos Connect Emulator 2.1.0(API 34)에서 재검증한 결과**로 갱신했다. 상세·원시 로그는 [`pleos-capability-matrix.md`](pleos-capability-matrix.md) §4–5, 화면 증거는 `docs/evidence/car-*.png`.

| 항목 | 상태 | 근거 |
|---|---|---|
| `Vehicle:2.3.2` 해석·컴파일, `initialize()`/`getCurrentDistanceDriven` | **VERIFIED_REAL_SDK** | Pleos Maven AAR, `assembleDebug` |
| SDK 런타임(값·권한·서비스 바인딩) | **VERIFIED_REAL_SDK** | 프로브: 오도미터 `12400.0 → 12410.0 → 12424.5 km` 리스너/getter 모두 관측, 서비스 2.3.22 바인딩 |
| 연비·전비·트립거리 | **VERIFIED_REAL_SDK: 미지원** | 7개 호출 모두 `ERROR_NOT_SUPPORTED_PROPERTY`/`IllegalArgumentException` → 앱은 “직접 입력” 요구(Demo 전환 없음) |
| 실제 에뮬레이터에서 앱 전체 흐름 | **VERIFIED_REAL_SDK** | 로그인 → 그룹 → QR(화면 캡처 디코딩 성공) → 승객 가입·차량에서 승인 → 운행 시작(`start_km=12500`, 서버에 `PLEOS/VERIFIED/sdk 2.3.2`) → 주행 → 종료(24 km) → 정산안(6,000원 = 2,350/2,350/1,300) → 전원 승인 → 확정, 모아 XP 10 |
| 주행 제한(`CarUxRestrictions`) | **VERIFIED_REAL_SDK** | 콘솔 Drive+40 km/h → `주행 중 · 기능 제한`, Park → 복원 |
| 프로세스 재시작 복구 | **VERIFIED_REAL_SDK** | 크래시 후/재설치 후 서버 운행 1개 유지, 24.0 km 복원 |
| 서버가 토큰을 잃은 경우(401) | **VERIFIED_REAL_SDK** | 크래시 없이 “로그인이 만료되었어요” + 다시 시도/닫기 |
| `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug` | **통과** | 단위 테스트 **53개**(실패 0·오류 0·건너뜀 0, `VERIFIED_MOCK`), lint 오류 0·경고 15 |
| `:app:compileReleaseKotlin` | 통과 | `DEMO_TOOLS=false`, HTTPS 전용 자리표시 URL |
| 릴리스 APK | **NOT_RUN** | 서명 키가 없고 만들지 않았다. 디버그 APK를 릴리스 서명본이라 주장하지 않는다 |
| 실제 차량 / 실제 폰으로 QR 스캔 | **NOT_RUN** | QR은 화면 캡처를 OpenCV로 디코딩해 유효함만 확인(카메라 스캔 아님) |

디버그 APK: `apk/moata-debug.apk` = `car-app/app/build/outputs/apk/debug/app-debug.apk` (SHA-256 앞 16자리 `7127e36a0e43cf3f`, minSdk 28 / targetSdk 34, `debuggable=true`).

### 에뮬레이터에서 발견·수정한 결함

`ApiClient.send` 가 응답 본문을 호출 스레드(메인)에서 읽어 `NetworkOnMainThreadException` 으로 앱이 죽었다(`adb logcat -b crash`). 52개 단위 테스트는 가짜 전송을 써서 통과하고 있었다. `withContext(Dispatchers.IO)` 로 고치고 `ApiClientThreadTest` 회귀 테스트를 추가했다(→ 53개).

### PRD 12.1 (AND-01~10) 매핑

[`pleos-capability-matrix.md` §4](pleos-capability-matrix.md) 에 근거와 함께 정리했다(AND-01…10 모두 실제 에뮬레이터 기준, AND-06은 “미지원 확인”).

## 1-b. UX 재설계 (2026-11-02, 사용자 피드백 반영)

사용자가 실행해 보고 “디자인이 별로고 UX가 너무 불편하다”고 했다. 캡처한 화면을 처음 쓰는 운전자 입장에서 다시 보고 확인한 문제와 조치(전/후 캡처: `docs/evidence/rerun/` ↔ `docs/evidence/redesign/`):

| 문제 | 조치 |
|---|---|
| `함께 타기` 뒤에 설정 10여 개(차량 종류, 데이터 출처, 유가, 연비 방식·값·단위, 운전자 부담, 분담 방식)가 QR보다 먼저 나옴 | 처음엔 **유가 하나만** 큰 숫자패드로 입력, 이후엔 **기억해서 `함께 타기` → 바로 QR**. 나머지는 `고급 설정`에 접어 둠 |
| 글자 18 sp / 버튼 56 dp — 이 에뮬레이터는 160 dpi(1 dp = 1 px, 2560 px)라 규칙은 지켰지만 차에서 못 읽는 크기 | 본문 28 sp(최소 26), 핵심 숫자 120 sp, 버튼 104~136 dp(최소 터치 88 dp). `Tokens.MIN_TEXT_SP/MIN_TOUCH_DP` 와 테스트 갱신 |
| 개발자 용어(`PLEOS 차량 데이터`, `L/100km`, `경계 차량 확인`, 시스템 키보드) | 쉬운 말(`차량에서 읽은 값`, `km/L`, 선택은 ✓ 표시), 숫자는 **큰 온스크린 숫자패드** |
| 운행 중 화면이 휑하고 `운행 종료` 한 번에 종료 | 큰 거리(`16.0 km`) + 탄 사람 이름 + 큰 `도착했어요` → **확인 창** |
| 이동 요약이 데이터 덤프(시작·도착 주행거리, 판독 출처)이고 할 일이 맨 아래 | **큰 거리 + 사람별 탄 거리 + 연비/통행료 카드 + `정산안 만들기`**, 측정 정보는 `자세히`로 접음 |
| 정산 = 텍스트 벽, 고지 4줄 | **큰 합계 + 산식 1줄 + 사람별 카드(금액·상태)**, 고지는 한 줄 + `안내`(전문은 대화상자) |
| 연비 입력 창의 설명이 SDK 오류 원문(`FAILED(onFailed): ai.pleos…ERROR_NOT_SUPPORTED_PROPERTY […]`) | 쉬운 한국어로 교체, 기술 오류는 logcat 에만. 도착 직후가 아니라 요약 화면의 `연비 입력` 카드로 안내 |
| 대기 2명인데 `0명 참여` | `승인 대기 2명` |

**이번 확인에서 찾아 고친 결함:** 앱이 `PUT /trips/{id}/cost-rule` 응답을 `CostRuleOut`으로 해석했는데 서버는 `TripSummary`를 돌려준다. 연비를 입력하면 `200 BAD_RESPONSE` 배너가 뜨고, **차량이 연비를 주는 정상 경로에서도 정산안 생성이 막힐 수 있었다**(이 경로는 에뮬레이터가 연비를 안 줘서 그동안 실행된 적이 없음). 앱을 고치고, 서버 테스트(`test_put_cost_rule_answers_with_the_trip_summary_shape`)로 계약을 고정했다.

## 1-c. UX v3 (리뷰 기반 진단 → 수정, 2026-11-02)

진단과 근거는 `docs/verification.md` §3-d, 결정은 `docs/decisions.md` §F.

- **출발 전 탑승:** 초대 화면에 사람별 `탔어요` / `아직 안 탔어요`, 헤더 `탔어요 n/m명`. 안 누른 사람이 있으면 `출발하기` 에서 `○○님도 탔나요?` → `모두 탔어요 · 출발`(운전자 대리, outbox 로 `/ready` 전에 전송) / `안 탄 사람 빼고 출발` / `기다릴게요`.
- **구간 막대(`RideLanes.kt`):** 요약은 `24km를 함께 왔어요` + 사람별 막대(이름·km 한 줄, 아래 전폭 막대, `16km에서 내림`), 정산 화면 왼쪽에도 같은 막대. TalkBack 은 줄마다 한 문장. `lanesFrom()` 은 순수 함수로 단위 테스트(`RideLanesTest`).
- **출처:** 상태 칩 `PLEOS · 차량 데이터`, 값마다 `sourceKo()` = 코드 + 한국어. 정산 화면 `숫자의 출처`(거리·연비·유가·통행료).
- **안전:** `parked()` 를 데이터 변경 함수 전부에서 재확인, 주행 중 `MessageBar` 는 버튼 없음. 운행 취소·거절·항목 삭제는 확인 창.
- **오류 문구:** `Msg.detail` 은 logcat 만. 401 은 `다시 로그인`.
- **접근성:** 제목 `heading()`, 배너·승인 대기 칩 live region, `⌫` 는 그린 아이콘 + `지우기`, 펼치기 토글 `펼쳐짐/접힘`, 승인/거절 버튼 이름에 닉네임, 모아는 장식(스크린리더 제외), `Choice` 는 `FlowRow`(줄바꿈)·`selectableGroup`, 대화창 스크롤.
- **아이콘:** `Icons.kt` 의 선 아이콘 4개(뒤로·펼치기·체크·지우기). 유니코드 ← ▼ ✓ ⌫ 제거.
- **결과:** 단위 61 PASS, lint 오류 0. 실제 TalkBack 낭독·큰 글꼴은 NOT_RUN.

## 2. 실제 SDK에서 확인한 사실 (소스 JAR KDoc 인용, 2.3.2)

PRD와 다른/PRD가 불확실하다고 한 부분을 SDK 아티팩트로 확정했다. (출처: `Vehicle-2.3.2-sources.jar`, AAR 매니페스트)

| 사실 | 근거 |
|---|---|
| `Vehicle.initialize()`, `release()` 는 **반환값 없음(void)**. 연결 완료 콜백이 문서에 없다 → `connect()`는 "예외 없음"만 증명하고, 서비스 바인딩은 첫 읽기에서 드러난다 | `Vehicle.kt` KDoc "Typical flow" |
| `Odometer.getCurrentDistanceDriven(onComplete: (Float?) -> Unit, onFailed)` → *"Current odometer value of the vehicle in kilometers (km), or `null`"* — **누적 주행거리 km** | `Odometer.kt` |
| 트립 거리(`getTripCurrentDrivingDistance` 등)는 **미터(m)** | `Odometer.kt` |
| `DistanceDrivenListener.onDistanceDrivenUpdated(Float?)`: `registerDistanceDriven`은 km, `registerTrip*DrivingDistance`는 m | `DistanceDrivenListener.kt` |
| 연비 `Fuel.get*FuelEconomy` → **L/100 km** (km/L 아님). `getCurrentFuelEconomy`는 "during the current driving session" | `Fuel.kt` |
| 전비 `EvBattery.get*EvEfficiency` → **km/kWh**, SoC는 `Int?` %, 배터리 용량은 Wh | `EvBattery.kt` |
| 권한: Odometer = `CAR_DRIVE`, Fuel/EvBattery = `CAR_ENERGY`, 보호 수준 **normal** (런타임 요청 불필요) | KDoc `(protection level: normal)`, 문서 vehicle-permission 표 |
| `CapabilityConfig.accessType` ∈ {NONE, READ, WRITE, READ_WRITE} | `CapabilityConfig.kt`, `AccessType.kt` |
| 차량 **서비스** 최소 버전 `2.3.13` (`min_version_name`), SDK 현재 `2.3.2` | AAR `AndroidManifest.xml` meta-data |
| AAR이 `uses-permission ai.pleos.playground.service.vehicle.VEHICLE_BINDING` 과 `<queries> action ai.pleos.playground.vehicle.VEHICLE_START` 를 자동 병합 | 병합 매니페스트(`apkanalyzer manifest print`) |
| `getEvBatteryLevel`, `getEvBatteryCapacity` 는 **AAR/소스에는 있으나 공식 문서 목차에는 없음** | 문서 EvBattery 인덱스 vs 소스 |

주의: 위는 **문서/타입 수준** 사실이다. 에뮬레이터가 실제로 이 단위로 값을 주는지, `current`가 트립 평균인지 누적 평균인지는 **NOT_RUN** — 프로브로 확인해야 한다. 단위는 원시값 그대로 업로드하고 서버가 정규화한다(`L/100km`→`km/L` 변환은 서버 `cost_engine.fuel_economy_to_km_per_l`).

공식 문서 페이지 중 읽을 수 없었던 것: Pleos 메서드별 API 페이지(`.../odometer/get-current-distance-driven` 등)는 JS 렌더링이라 본문이 비어 있었다 → 위 단위는 소스 JAR KDoc을 근거로 했다. `getting-started` 페이지에는 앱 매니페스트 요구사항(uses-feature/카테고리)이 없었다.

## 3. 도구 체인과 PRD와의 차이 (docs/decisions.md 반영 필요)

| 항목 | PRD/지시 | 실제 | 이유 |
|---|---|---|---|
| Gradle | 8.0+ | **9.4.1** (래퍼, 캐시된 배포판으로 생성) | 이 머신에 이미 캐시됨, 전역 gradle 없음 |
| AGP | 호환 버전 | **9.2.1** (내장 Kotlin) | 로컬 캐시에 있던 검증된 조합 |
| Kotlin | – | **2.4.10** (compose/serialization 플러그인) | AGP 기본 2.2.10으로는 최신 `kotlin-stdlib 2.4`/`serialization 1.12`(메타데이터 2.4.0)를 읽지 못해 컴파일 실패 → 플러그인 버전을 2.4.10으로 올려 해결 |
| KSP | – | **2.3.13** + Room **2.8.5** | KSP 2.3.x는 Kotlin 버전과 분리됨. 스키마 `app/schemas/.../1.json` 생성 확인 |
| compileSdk | 34 (지시문은 36) | **37** | 설치된 플랫폼은 `android-36`, `android-37.0`뿐(공유 SDK를 건드리지 않음). 최신 AndroidX/OkHttp가 compileSdk ≥ 37을 요구해 36으로는 `checkDebugAarMetadata` 실패. compileSdk는 컴파일에만 영향 |
| targetSdk / minSdk | 34 / 28 | 34 / 28 | PRD·공식 에뮬레이터 요구와 동일 |
| Java | – | 17 (`jvmToolchain(17)`) | 설치된 JDK |

AGP 9 + KSP + Room + Compose 조합은 **동작**했다. 8.x 폴백은 필요하지 않았다.
Gradle 데몬이 파이프를 붙잡아 출력 파이프라인이 멈추는 현상이 있어, 빌드는 `./gradlew ... < /dev/null > log 2>&1` 로 실행했다.

### 매니페스트 결정

- 포함: `CAR_DRIVE`, `CAR_ENERGY`, `INTERNET`, `uses-feature android.hardware.type.automotive (required)`, `uses-library android.car (required=false)`. `CONTROL_CAR_*` 없음(병합 결과에서도 0건 확인).
- `uses-feature automotive`, `distractionOptimized=true` 메타데이터는 **표준 AAOS 선언이며 Pleos 앱 등록 가이드로 확인하지 못했다 → UNVERIFIED**. 앱이 `CarUxRestrictions`를 직접 지켜 주행 중에는 읽기 전용 V03만 그리므로 `distractionOptimized`를 선언했다. Pleos 앱 심사/등록 문서에서 이 선언이 허용되는지 확인이 필요하다.
- 디버그 전용 `network_security_config`(cleartext 허용; Android는 "임의의 LAN IP"를 표현할 수 없어 debug에서는 전체 허용). 릴리스는 기본 설정 = HTTPS만.
- `allowBackup=false`, 토큰은 앱 전용 `SharedPreferences`(암호화 라이브러리 미사용; `androidx.security:security-crypto`는 폐기 상태라 도입하지 않음 — 한계로 기록).

## 4. 구조

```
car-app/app/src/main/java/io/moata/pleos/
  MoataApp.kt, MainActivity.kt          컨테이너(수동 DI), 프로브 intent 트리거(debug 전용)
  core/vehicle/
    VehicleSample.kt                    DataSource{PLEOS,MANUAL,DEMO} · Quality · SignalType(단위) · SampleRules(검증)
    VehicleDataSource.kt                connect/read/observeOdometer/disconnect
    CallbackAwait.kt                    onComplete/onFailed → suspend. Complete|Failed|Timeout|Threw 네 가지를 구분
    PleosApis.kt                        final Vehicle 위의 이음새(테스트에서 Odometer/Fuel/EvBattery 인터페이스를 가짜로 대체)
    PleosVehicleDataSource.kt           실제 SDK. 능력 확인 → 읽기, 모든 실패는 error 텍스트가 있는 샘플
    DemoVehicleDataSource.kt            결정적 24km 픽스처, 항상 DEMO/ESTIMATED
    ManualVehicleDataSource.kt          직접 입력, 항상 MANUAL/ESTIMATED
    PleosProbe.kt, AndroidProbeEnv.kt   진단(§6)
  data/local/                           Room: outbox(append-only), trip, grp · Session(SharedPreferences)
  data/remote/                          Dto(=schemas.py) · ApiClient(OkHttp) · OutboxProcessor · Transport
  domain/                               TripRepository · TripRecorder · UxPolicy · CarUxStateSource
  ui/                                   Tokens/Theme/Moa/Components · Chrome(셸) · Screens · TripScreens · DebugScreen · AppViewModel · QrBitmap
```

**원칙**
- 데이터 출처는 운행 생성 시 하나로 정해지고(`PLEOS|MANUAL|DEMO`), 어떤 경로도 다른 출처로 **자동 대체하지 않는다.** PLEOS가 실패하면 이유(SDK 오류 문구)를 보여 주고 재시도 또는 사용자의 명시적 직접 입력만 제공한다. DEMO는 디버그 빌드에서만 선택 가능하고(`BuildConfig.DEMO_TOOLS`) 상단에 `DEMO / 가상 데이터` 배너가 항상 보인다.
- 정산 계산은 **서버가 유일한 권위**다. Kotlin에는 비용 엔진이 없다. 차량은 원시값+단위를 올리고 서버가 만든 산식·몫을 그대로 보여 준다.
- 오프라인 우선: 시작/종료/판독값/비용 항목은 Room outbox에 쌓이고 `OutboxProcessor`가 `seq` 순서로 재생한다. 재시도는 지수 백오프(1,2,4…60초). 4xx 거절은 `FAILED`로 남겨 검사 가능하게 두며 조용히 버리지 않는다. 판독값은 연속된 것끼리 최대 100개씩 배치로 전송.
- 멱등: 이벤트 ID는 유니크 인덱스(같은 ID 두 번 저장 불가), 운행 시작/종료는 상태로 막고, 운행 생성은 같은 ID 또는 이미 진행 중인 운행이 있으면 새로 만들지 않는다. 재시작 시 `TripRepository.open()`이 진행 중/정산 대기 운행을 복구하고 IN_PROGRESS면 기록을 재개한다. 좌석 센서로 사람을 추측하지 않는다(탑승/하차는 동승자의 명시적 행동).
- 기록 주기: 리스너 갱신 + **15초 하트비트 읽기**(차가 멈춰 있어 값이 안 바뀌어도 "최신 판독"이 낡지 않게 — 서버가 승·하차 경계를 최신 판독에서 가져오므로), 저장은 5초 간격으로 제한. 실패한 읽기도 `value=null + error` 로 저장(품질 추적).

## 5. 주행 제한 (UX)

공식 주행 안전 가이드(`driver-distraction-guideline`, 본문 읽음)는 **`CarUxRestrictions`를 읽고 제한 기능의 UI/동작을 줄이거나 끄라**고 한다. 속도/기어는 감지 수단으로 언급하지 않는다.

- `CarUxStateSource`는 SDK의 `platforms/android-NN/optional/android.car.jar` 스텁으로 **컴파일 시** 실제 `android.car.Car` / `CarUxRestrictionsManager`(`Car.createCar(ctx, handler, CAR_WAIT_TIMEOUT_DO_NOT_WAIT, listener)`, `getCarManager(CAR_UX_RESTRICTION_SERVICE)`, `isRequiresDistractionOptimization`)를 사용한다. 리플렉션이나 가상의 API 없음.
- **fail-safe**: 시작은 `UNKNOWN`. 카 서비스가 없거나 예외가 나도 `UNKNOWN`. `PARKED`는 차가 `requiresDO=false`라고 답할 때만. `UNKNOWN`/`DRIVING`은 모두 제한 상태.
- 제한 상태에서는 루트가 항상 V03(정적 모아 아이콘 + 동승 인원 + 큰 상태 문구 + 거리)만 그린다. QR, 비용 편집, 모아 애니메이션, 보상/정원, 설정, 그룹 편집, 운행 시작/종료, 다이얼로그가 모두 도달 불가. 모아 애니메이션은 정차 상태의 홈/정원에서만 켜진다.
- 비-차량 이미지에서 시험하려면 디버그 화면의 "주행 상태 강제"(PARKED/DRIVING)를 쓴다. 차량이 알려 주는 값이 아니라는 문구가 함께 표시된다.

## 6. SDK 프로브 실행 방법

프로브는 `initialize` → 능력 확인(`AccessType`) → Odometer/Fuel/EvBattery 읽기(각 API의 문서화된 단위 함께 기록) → `registerDistanceDriven` 창(기본 10초, 같은 창에서 `getCurrentDistanceDriven`을 1초마다 폴링해 리스너/게터 비교) → `release` 순서로 돈다. 각 단계는 `COMPLETE`(값 또는 null) / `FAILED(onFailed)` / `TIMEOUT` / `THREW` 중 정확히 하나로 기록되고, 이벤트 순서 로그로 `release`가 모든 콜백 뒤에 오는지 확인할 수 있다. 환경(기기 지문, 자동차 feature, 병합된 SDK 메타데이터, `VEHICLE_START`에 응답하는 서비스 패키지·버전)과 권한(GRANTED/DENIED)도 포함한다.

```bash
cd car-app && ./gradlew :app:assembleDebug
adb devices                                   # AND-01
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
# 헤드리스 실행(debug 빌드 전용 intent extra). 리스너 창 20초 동안 Virtual Console의 Motion 주행거리를 바꿔 본다
adb shell am start -n io.moata.pleos/.MainActivity --ez moata.probe true --ei moata.probe.listen_sec 20
adb logcat -s MOATA_PROBE                     # STEP/EVENT 줄 + JSON[n] 조각 + MOATA_PROBE_END verdict=...
adb exec-out run-as io.moata.pleos cat files/probe-result.json > probe-result.json
adb forward tcp:8088 tcp:8088                 # Virtual Console: http://127.0.0.1:8088/
```

판정 값: `SDK_RETURNED_VALUES` | `BLOCKED: initialize ...` | `BLOCKED: every getter timed out (vehicle service not bound?)` | `NO_VALUES: ...`.
앱 안에서는 홈 → 개발자 도구 → "프로브 실행"으로도 돌릴 수 있다. 비-Pleos 이미지에서는 `vehicle_service.resolved`가 `NONE`으로 나오고 모든 읽기가 TIMEOUT/FAILED가 된다 — 이것이 정상적인 "BLOCKED" 증거이며 통과로 취급하지 않는다.

## 7. 실행한 명령과 결과

```text
cd car-app
./gradlew clean :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --no-build-cache
  → BUILD SUCCESSFUL (56 actionable tasks)
  → 단위 테스트 52개: 실패 0, 오류 0, 건너뜀 0
      PleosVehicleDataSourceTest 13 · OutboxProcessorTest 9 · TripRepositoryTest 6 · ContractTest 6 · PleosProbeTest 4
      ThemeContrastTest 4 · UxPolicyTest 3 · DemoAndManualSourceTest 2 · SampleRulesTest 2 · RecoveryAndRecorderTest 2 · ThrottleTest 1
  → lint: 오류 0, 경고 15
./gradlew :app:compileReleaseKotlin --no-build-cache   → BUILD SUCCESSFUL
apkanalyzer manifest print app-debug.apk → 권한 CAR_DRIVE, CAR_ENERGY, INTERNET, VEHICLE_BINDING; CONTROL_CAR_* 0건
```

개발 중 테스트가 잡은 실제 결함(수정 완료): Manual 소스의 부정확한 오류 문구("the SDK returned null"), 그리고 코드 리뷰에서 찾은 3건 — 직접 입력한 연비를 차량 값으로 덮어쓸 수 있던 경쟁 상태(이제 최신 서버 상태를 await한 뒤에만 판단), 정지 상태에서 "최신 판독"이 낡는 문제(하트비트 추가), 운행 종료 후 재시작하면 정산 화면으로 돌아갈 수 없던 문제(`open()` 복구 추가).

lint 경고 15건: `OldTargetApi`(targetSdk 34는 PRD/에뮬레이터 요구로 의도적), 최신 AGP/Gradle/Kotlin 안내 4건, `InsecureBaseConfiguration`(디버그 전용 cleartext, 의도적), `DataExtractionRules`, `ModifierParameter` 2건, `UseKtx` 6건(`SharedPreferences.edit` — 기능 영향 없음).

## 8. 디자인 토큰 (Theme.kt / Tokens.kt)

브리프의 팔레트를 그대로 쓰되 **텍스트로 쓰는 코랄/틸은 대비를 위해 더 밝은 색조로 조정**했다(`ThemeContrastTest`가 AA 4.5:1을 강제).

| 토큰 | 값 | 비고 |
|---|---|---|
| ink / paper / moa-yellow / route-teal | `#14213D` / `#FBF8F1` / `#FFC83D` / `#12A594` | 브리프 그대로 |
| alert-coral | `#E5484D` | 잉크 위 4.08:1 → **글자에 사용 금지**(장식만) |
| coral-text | `#FF8F93` | 잉크 위 7.30:1, 경고/위험 버튼 채움 |
| teal-text | `#3CD3C0` | 잉크 위 8.57:1, 틸 글자·핀 |
| surface / surface-hi / muted | `#1D2D50` / `#26396A` / `#C3CCE0` | 보조 면/글자 |
| 글자 최소 | **26sp** (본문 28, 핵심 숫자 120) | 터치 최소 **88dp** (주 버튼 104~136dp) |

모아는 Canvas로 그린 원본 벡터(둥근 노란 몸통, 작은 바퀴 둘, 틸 "경로 핀" 뭉치; 표정은 눈·입만). 외부 마스코트 에셋 미사용.

## 9. 한계와 백엔드에 필요한 것

- 런타임 검증은 §1 참고. 남은 NOT_RUN: 실제 차량, 폰 카메라로 QR 스캔, 릴리스 서명.
- 차량 앱은 **드라이버 쪽 탑승/하차 버튼을 제공하지 않는다**(동승자가 휴대폰에서 직접 확인하는 것이 PRD 설계). `TripRepository.board()`는 구현되어 있으나 UI에서 쓰지 않는다.
- `INTERRUPTED` 상태는 서버에서 만들지 않으므로 차량 앱의 복구는 로컬 DB 기반이다.
- 푸시/WebSocket 없음: 약 4초 폴링(`GET /trips/{id}/summary`). ETag 미사용(서버는 ETag/304 지원).
- 토큰 저장은 앱 전용 SharedPreferences(암호화 없음).
- 릴리스: `moata.apiBaseUrl`/`moata.webBaseUrl`을 `https://…`로 주지 않으면 `*.invalid` 자리표시값이 들어가 접속 불가이며 서명 키도 없다.
- QR이 인코딩하는 웹 주소는 `-Pmoata.webBaseUrl=http://<PC LAN IP>:3000` 으로 지정해야 한다(휴대폰은 `10.0.2.2`에 접근할 수 없음). API는 에뮬레이터에서 기본 `http://10.0.2.2:8000/`.
- 데모 소스의 연비는 PRD 예제(12 km/L)를 재현하려고 **`km/L`** 단위로 보고한다(실제 SDK는 L/100km). 단위는 값과 함께 전송된다.

### 백엔드 계약에서 내가 가정한 점 (서버 구현과 대조 필요)

1. `POST /auth/guest`, `GET /groups`(배열), `GET /groups/{g}/rewards`(배열), `GET /trips/{t}/summary` — `docs/API_CONTRACT.md` 그대로.
2. 본문 없는 POST: `/trips/{t}/ready`, `/trips/{t}/cancel`, `/trips/{t}/settlements/preview` (빈 본문으로 호출).
3. `/ready`·`/start`·`/end`·`/cancel`을 **같은 요청으로 다시 보내도 오류가 아니라 같은 결과**여야 한다(아웃박스 재생·응답 유실 재전송 때문). 4xx면 `FAILED`로 남는다.
4. `PUT /trips/{t}/cost-rule`을 `ENDED` 상태에서도 허용해야 한다(연비는 도착 후 차량에서 읽어 채우는 흐름: PLEOS 출처는 생성 시 `efficiency_value=null`).
5. 판독값 업로드는 `value` 원시 숫자 + `unit`(`km`, `L/100km`, `km/kWh`, `%`; 데모/직접 입력은 `km/L` 등)이다. 서버가 이 단위들을 모두 받아야 한다. 값이 없는 읽기도 `value` 생략 + `error` 로 올라온다.
6. 비용 규칙의 `efficiency_value`/`unit_price_krw`는 **문자열**로 보낸다.
7. 초대 토큰은 `join_path` 의 프래그먼트(`/join#token`)로 받고, QR은 `WEB_BASE_URL + join_path`.
