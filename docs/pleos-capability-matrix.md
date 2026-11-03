# Pleos Vehicle SDK 능력 매트릭스

상태 표기: **VERIFIED_REAL_SDK**(실제 에뮬레이터에서 앱이 SDK를 호출해 확인) · **VERIFIED_SOURCE**(SDK 2.3.2 AAR/소스 JAR 확인, 실행 전) · **VERIFIED_ENV**(에뮬레이터 환경 사실, 앱 코드 아님) · **NOT_RUN** · **BLOCKED**.
이 문서의 “SDK 사실”은 `ai.pleos.playground:Vehicle:2.3.2` 의 `Vehicle-2.3.2.aar`·`-sources.jar` (https://nexus-playground.pleos.ai/repository/maven-releases/) 에서 읽은 KDoc/시그니처다. 공식 웹 문서의 메서드 페이지는 SPA로 렌더링되어 단위·권한 본문을 가져올 수 없었기 때문이다(목차만 확인).

## 1. 환경 (VERIFIED_ENV)

| 항목 | 값 |
|---|---|
| 호스트 | macOS (Darwin 25.6, arm64), JDK 17 (Homebrew), Gradle 9.4.1(캐시된 배포판), AGP 9.2.1 |
| 에뮬레이터 | `emulator` 37.1.11, AVD `MOATA_Pleos_API34`, 이미지 `release_connect_v2.1.0` (Pleos Connect Emulator **2.1.0**), Android 14 / API 34, tag `android-automotive`, RAM 4GB, Cold boot |
| 차량 서비스 | `ai.pleos.playground.service.vehicle` **2.3.22** (code 74). SDK 2.3.2가 요구하는 최소 `2.3.13` 이상 |
| 권한 정의 | `pleos.car.permission.CAR_DRIVE`, `CAR_ENERGY`, `ai.pleos.playground.service.vehicle.VEHICLE_BINDING` — 모두 `protectionLevel:normal` (런타임 승인 불필요, 매니페스트 선언만) |
| Virtual Console | `http://127.0.0.1:8088/` (`adb forward tcp:8088 tcp:8088`). **브라우저 하트비트(`POST /browser/session`)가 있어야 브리지 활성**(→ `scripts/console_heartbeat.sh`) |

## 2. SDK 호출 순서와 의미 (VERIFIED_SOURCE)

| 항목 | 사실 |
|---|---|
| 의존성 | `ai.pleos.playground:Vehicle:2.3.2` (AAR). 런타임 의존: vehicle-aidl 1.1.1, vehicle-data 1.1.11, core-ktx 1.8.0, coroutines-android 1.6.3, kotlin-reflect/stdlib 1.9.25, gson 2.10.1 |
| 매니페스트 병합 | SDK가 `VEHICLE_BINDING` 권한과 `<queries><intent><action ai.pleos.playground.vehicle.VEHICLE_START/>` 를 병합해 넣음 |
| 진입 | `Vehicle(context)` → `initialize()` → `getOdometer()/getFuel()/getEvBattery()` → … → `release()`. **`initialize()`/`release()` 는 void, 연결 완료 콜백 없음** |
| 비동기 | 모든 getter/check는 `onComplete: (T) -> Unit`, `onFailed: (Exception) -> Unit` 콜백. 권한 누락 시 `onFailed` (`ERROR_SECURITY_PERMISSION` 계열) |
| 리스너 | `registerDistanceDriven(DistanceDrivenListener)` / `unregisterDistanceDriven`. 리스너 인터페이스는 `onDistanceDrivenUpdated(Float?)` + `onFailed(Exception)` |
| 용량 확인 | `check*Capabilities(onComplete: (CapabilityConfig) -> Unit, …)`; `CapabilityConfig.accessType ∈ {NONE, READ, WRITE, READ_WRITE}` |

## 3. 신호별 매트릭스

| 데이터 | API (확인된 이름) | 타입 | **단위 (KDoc)** | 권한 | PRD 용도 | 실제 에뮬레이터 결과 |
|---|---|---|---|---|---|---|
| 총 주행거리(오도미터) | `Odometer.getCurrentDistanceDriven` / `registerDistanceDriven` / `checkCurrentDistanceDrivenCapabilities` | `Float?` | **km** (“Current odometer value of the vehicle in kilometers”) | CAR_DRIVE | 거리의 진실원본(스냅샷 차이) | **VERIFIED_REAL_SDK** — 앱 프로브가 `12400.0` 반환(콘솔 기준값과 일치). 콘솔로 12410 → 12424.5 로 바꾸자 리스너·폴링 getter 양쪽에 반영. 콘솔 쓰기는 float32(12345.6 → 12345.599609) |
| 트립 거리 | `getTripCurrentDrivingDistance`, `…Cumulative…`, `…Instantaneous…`, `…SinceRefill…` | `Float` | **m** | CAR_DRIVE | 사용 안 함(의미가 “운행 구간 누적/최근 급유 후” 등으로 달라 구간 정산 기준으로 부적합) | **VERIFIED_REAL_SDK: 미지원** — `getTripCurrentDrivingDistance`, `getTripCumulativeDrivingDistance` 모두 `ERROR_NOT_SUPPORTED_PROPERTY` |
| 연비 (ICE) | `Fuel.getCurrentFuelEconomy`, `getCumulativeFuelEconomy`, `getInstantaneousFuelEconomy`, `getSinceChargeFuelEconomy` | `Float` | **L/100 km** | CAR_ENERGY | 연비 자동 입력(서버가 km/L 로 환산) | **VERIFIED_REAL_SDK: 미지원** — `checkCurrentFuelEconomyCapabilities`·`getCurrentFuelEconomy` 는 `ERROR_NOT_SUPPORTED_PROPERTY`, `getCumulative/InstantaneousFuelEconomy` 는 `IllegalArgumentException: property ID is not in carPropertyConfig list … 0x21600be2 / 0x21600be0`. 콘솔에도 항목 없음 |
| 전비 (EV) | `EvBattery.getCurrentEvEfficiency`, `…Cumulative…`, `…Instantaneous…`, `…SinceCharge…` | `Float` | **km/kWh** | CAR_ENERGY | 전비 자동 입력 | **VERIFIED_REAL_SDK: 미지원** — `checkCurrentEvEfficiencyCapabilities`·`getCurrentEvEfficiency` `ERROR_NOT_SUPPORTED_PROPERTY`, `getCumulativeEvEfficiency` `IllegalArgumentException … 0x21600b15`. 콘솔에도 항목 없음 |
| EV SoC | `EvBattery.getEvBatteryStateOfCharge` / `registerEvBatteryStateOfCharge` | `Int` | % | CAR_ENERGY | 참고 표시만(비용 계산에 SoC 차이를 쓰지 않음, PRD §8.1) | **VERIFIED_REAL_SDK** — capability `READ`, 값 `100` (콘솔 기본 SoC 100.0과 일치) |
| EV 배터리 용량 | `getEvBatteryCapacity` | `Float` | **Wh** | CAR_ENERGY | 사용 안 함 | NOT_RUN. 공식 문서 목차엔 없고 AAR에는 존재 |
| 연료 잔량 | `Fuel.getFuelPercent` | `Int` | % | CAR_ENERGY | 사용 안 함 | NOT_RUN |
| 통행료 | `Toll` 도메인 (`CAR_TOLL`) | — | — | CAR_TOLL | **사용 안 함** — 통행료는 운전자가 직접 입력(PRD §8.1). 최소 권한 원칙으로 권한 미선언 | — |
| 내비 경로 | NaviHelper 3.0.5 | — | — | NAVI_ROUTE* | P2 보류. 예정 경로거리를 실제 이동거리로 쓰지 않음 | NOT_RUN |

### 데이터 정규화 (서버 `cost_engine`)

`L/100km → km/L = 100 ÷ x`, `Wh/km → km/kWh = 1000 ÷ x`, `m → km = ÷1000`. 값이 null·0·NaN·단위 불명이면 `UNAVAILABLE/INVALID` 로 저장하고 **가짜 값을 만들지 않는다**. 센서 연비가 없으면 정산은 `EFFICIENCY_MISSING` 으로 차단되고 운전자가 직접 입력(`MANUAL`)해야 한다.

## 4. 앱 프로브로 검증할 항목 (AND-01 … AND-10)

| ID | 내용 | 상태 | 근거 |
|---|---|---|---|
| AND-01 | Emulator v2.1.0 / API 34 에서 `adb devices` 인식 | **VERIFIED_ENV** | `emulator-5554 device`, `release_connect_v2.1.0` |
| AND-02 | `Vehicle:2.3.2` Gradle resolve/compile | **VERIFIED_REAL_SDK** | `assembleDebug` 성공(Pleos Maven 에서 AAR 해석) |
| AND-03 | CAR_DRIVE/CAR_ENERGY 선언 후 `initialize()` | **VERIFIED_REAL_SDK** | 프로브 `initialize COMPLETE`, 두 권한 `GRANTED`(설치 시 자동 부여, normal) |
| AND-04 | `getOdometer()` 응답 또는 명확한 `onFailed` | **VERIFIED_REAL_SDK** | `getCurrentDistanceDriven` → `12400.0` (3 ms). 미지원 항목은 `onFailed` 로 정확한 사유 기록 |
| AND-05 | 콘솔 Motion 거리 변경 → 앱 SDK 읽기 변화 | **VERIFIED_REAL_SDK** | 같은 20 s 창에서 리스너 `12400.0 → 12410.0 → 12424.5`, 폴링 getter 동일(변경 시각과 일치). 이후 전체 흐름에서 서버에 `PLEOS / VERIFIED / sdk 2.3.2` 로 저장 |
| AND-06 | ICE 연비 / EV 전비 접근 성공·실패와 단위 | **VERIFIED_REAL_SDK (미지원 확인)** | 이 에뮬레이터는 연비·전비·트립거리를 제공하지 않음 → 앱이 “확인 불가”로 기록하고 직접 입력(MANUAL)을 요구. 단위(L/100km, km/kWh)는 KDoc 기준이며 **에뮬레이터에서 값으로는 관측하지 못함** |
| AND-07 | 미지원 시 “사용 불가” 표시, Demo 자동전환 금지 | **VERIFIED_REAL_SDK(동작) + VERIFIED_MOCK(단위테스트)** | 트립 설정 화면이 “직접 입력” 요구, 데이터 출처 배지는 계속 `PLEOS 차량 데이터`, 연비는 `12 km/L · 직접 입력`(MANUAL)로 서버에 라벨됨 |
| AND-08 | 백그라운드/재실행 후 중복 운행 없음 | **VERIFIED_REAL_SDK** | 크래시 후 재실행·재설치(`-r`) 모두 서버의 운행은 1개, 화면은 `운행 중 · 함께 탄 사람 1명 · 24.0 km` 로 복구 |
| AND-09 | callback 종료 전 `release()` 금지, 구독/해제 | **VERIFIED_REAL_SDK** | 이벤트 순서: `listener:register … listener:unregister → release:call → release:done` (모든 콜백 이후) |
| AND-10 | 주행 UX 제한 시 QR·금액 편집·애니메이션 숨김 | **VERIFIED_REAL_SDK** | 콘솔 기어 Drive + 40 km/h → 배지 `주행 중 · 기능 제한`, 화면은 정적 모아 + `정차 후에 이용할 수 있어요` 만. Park 복귀 시 정산 화면 복원 (`docs/evidence/car-09-driving-restricted.png`) |

## 5. 결과 기록 (2026-11-02, 실제 Pleos Connect Emulator 2.1.0 / API 34)

원시 결과: [`docs/evidence/probe-result-emulator-2.1.0.json`](evidence/probe-result-emulator-2.1.0.json) · logcat 태그 `MOATA_PROBE`.

```bash
adb -s emulator-5554 install -r apk/moata-debug.apk
curl -X POST "http://127.0.0.1:8088/vehicle/odometer?traveled_distance_km=12400"      # 콘솔 하트비트 실행 중이어야 함
adb -s emulator-5554 shell am start -n io.moata.pleos/.MainActivity --ez moata.probe true --ei moata.probe.listen_sec 20
#   (창 6초/11초 지점에 콘솔 주행거리를 12410, 12424.5 로 변경)
adb -s emulator-5554 shell run-as io.moata.pleos --user 10 cat files/probe-result.json   # 차량 이미지는 멀티유저(user 10)
```

결과: `verdict=SDK_RETURNED_VALUES counts={COMPLETE=5, FAILED=10}`

| 단계 | 결과 |
|---|---|
| 환경 | `feature.automotive=true`, SDK 2.3.2(`current_version_code 73`), 서비스 `…VehicleService` 2.3.22 (min 2.3.13) |
| `Vehicle.initialize()` | COMPLETE (1 ms, 값 없음) |
| `cap.odometer.current` | COMPLETE `CapabilityConfig(accessType=READ)` (435 ms — 첫 바인딩 지연) |
| `get.odometer.current` | COMPLETE **12400.0** (km) |
| 리스너 `updates` | `4ms:12400.0, 4246:12400.0, 6245:12410.0, 9245:12410.0, 11245:12424.5, 16244:12424.5, 19245:12424.5` — 실패 0 |
| `polledGetter`(1 s) | 6.0 s 지점부터 12410.0, 11.0 s 지점부터 12424.5 |
| `cap.ev.soc` / `get.ev.soc` | COMPLETE `READ` / **100** (%) |
| 연비·전비·트립거리 7개 | FAILED — `ERROR_NOT_SUPPORTED_PROPERTY` 또는 `IllegalArgumentException: property ID is not in carPropertyConfig list` |
| 해제 | `listener:unregister` → `release:call` → `release:done(COMPLETE)` |

**결론:** 이 에뮬레이터에서 **주행거리 기반 구간 정산은 실제 SDK 값으로 가능**하고, **연비/전비는 SDK로 받을 수 없어 MANUAL 입력이 필수**다(앱·서버 모두 그렇게 동작). 실차에서 연비/전비가 제공되는지는 이 환경으로 알 수 없으며 *NOT_RUN* 이다. 단위(L/100km, km/kWh)가 값으로 관측되지 않았으므로 서버의 변환(`100 ÷ x`)은 KDoc과 단위 테스트(VERIFIED_MOCK)에 근거한다.

### 발견된 앱 결함 (실제 에뮬레이터에서만 드러남, 수정 완료)

`ApiClient.send` 가 응답 본문을 호출 스레드(메인)에서 읽어 `NetworkOnMainThreadException` 으로 앱이 죽었다(`logcat -b crash`, 2회). JVM 단위 테스트의 가짜 전송은 이 예외를 만들 수 없어 통과하고 있었다. `withContext(Dispatchers.IO)` 로 수정하고, 본문 읽기가 호출 스레드에서 일어나지 않는지 직접 검증하는 회귀 테스트(`ApiClientThreadTest`)를 추가했다. 수정 후 전체 흐름이 크래시 없이 완료됨.
