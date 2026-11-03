# MOATA (모아타) — Pleos Connect 기반 그룹 이동 기록·N빵·캐릭터 리워드

**문서 종류:** 구현 가능한 PRD + SRS + Technical Design + Acceptance Test Plan  
**버전:** v2.0 / 검증 기준일 2026-11-02  
**사전 준비:** SDK 계정·Android Emulator 필요  
**중요:** 이 문서는 공식 Pleos 문서에 근거해 다시 작성했다. Android SDK/Gradle 설치·Maven 의존성 다운로드·에뮬레이터 빌드/실행은 사용자의 실제 개발 머신에서 수행해야 한다. SDK API가 문서에 있다고 모든 차량/에뮬레이터에서 지원되는 것은 아니다.

## 0. 우선 읽을 공식 문서 및 증거 우선순위

문서가 상충할 때 아래의 순서로 결정한다.

1. **빌드에 다운로드된 SDK API/JAR와 실제 컴파일 결과**, 에뮬레이터에서 `onComplete/onFailed` 출력
2. 개별 SDK의 최신 프로그래밍 가이드/API Reference
3. 최신 Pleos 앱 개발 시작하기, 프로젝트 생성 및 SDK 접근 안내
4. 공모전 개발 환경·심사·제출 가이드

주요 공식 URL:

- 에뮬레이터 설치: https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator
- Virtual Console: https://document.pleos.ai/docs/connect/guide/getting-started/application-development/use-vehicle-emulator
- 앱 시작하기: https://document.pleos.ai/docs/pleos-only/getting-started
- SDK 신청: https://document.pleos.ai/docs/pleos-only/create-my-project
- Vehicle SDK 개요: https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/intro
- Vehicle SDK 가져오기: https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/project-setup
- 차량 퍼미션: https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-permission
- Odometer API Reference: https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/odometer/entry
- Fuel API Reference: https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/fuel/entry
- EvBattery API Reference: https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/ev-battery/entry
- NaviHelper SDK 소개: https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/intro
- NaviHelper 가져오기: https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/importing-sdk
- NaviHelper 퍼미션: https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/declaring-permissions
- 주행 안전: https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/driver-distraction-guideline
- 공모전 심사: https://appdevchallenge.auto-hmg.io/guide/judging-awards
- 공모전 제출: https://appdevchallenge.auto-hmg.io/guide/submission

**문서 버전 주의:** SDK의 상세 Vehicle Guide는 v2.3.2를 명시하지만, 별도의 퀵스타트 예제에는 2.3.1 문자열이 남아 있다. **이번 프로젝트는 세부 SDK 가이드의 `Vehicle:2.3.2`를 기준으로 설정하고 실제 Maven 동작을 확인한다.** NaviHelper는 `NaviHelper:3.0.5`를 선택한다. 특정 버전 다운로드가 막히면 임의로 최신판으로 바꾸지 말고 오류·해결 과정·승인된 버전을 문서화한다.

## 1. 제품 목적

**서비스명:** MOATA (모아타)

**한 줄 소개:** "오늘 이만큼 같이 탔어요. 이동비는 공평하게 나누고, 우리 캐릭터는 함께 자라요."

**고객 문제:** 친구·가족·동료가 이미 합의해 함께 차를 타더라도 누가 얼마나 탔는지, 실제 이동거리가 얼마인지, 유류비/전기료를 어떻게 공평하게 나누는지가 매번 번거롭다. 단순 N빵 앱은 차의 실제 주행 데이터와 분리되어 있다. 잦은 반복 동승에는 추억·동기 요소도 적다.

**해결 경험:** 차에서 모아타 그룹을 선택 → 동승자가 QR로 참여 → Pleos 차량 주행거리를 기록 → 탑승 시점별 비용을 계산 → 각자 휴대폰에서 정산안을 확인 → 공동 캐릭터 '모아'가 성장하고 이동 성과가 누적된다.

**핵심 차별점은 기능들의 연결이다.** 차량 데이터가 없는 상태에서 화면만 예쁘게 만들거나, 게임형 캐릭터만 만들면 제품의 중심 가치를 충족하지 않는다.

### 1.1 타깃

- **MVP:** 이미 서로 아는 만 18세 이상 성인의 초대형 비상업적 그룹(친구·동료·가족). 새로운 승객을 공개 모집하지 않는다.
- **추가 검토:** 법령과 보험·플랫폼 약관 검토를 마친 조직의 출퇴근 정산·복지 모델.
- **제외:** 무허가 영업용 운송, 공개 유상 승객 매칭, 자동 송금, 예약 수수료 기반 유상 카풀 알선.

### 1.2 공모전 목표

- 차량특화성: Android Automotive/Pleos Odometer·Fuel/EvBattery로 실측 기반의 이동 요약을 만든다.
- 창의성: 팀 캐릭터가 실제 공동 이동의 기록을 반영해 성장하고, 차량과 동승자 휴대폰이 같은 여정에 참여한다.
- 기술 활용성: 별도 Mock만이 아니라 **Pleos Emulator + Virtual Console 데이터 변화가 APK에 반영되는 실제 데모**가 필수다.
- 사업성: 반복되는 친목·동료 이동 중심의 리텐션, 캐릭터 테마, B2B 이동복지/브랜드 쿠폰을 검토한다. 실제 MAU·구독 전환율은 출시 후 측정 전까지 가정값으로만 제시한다.

## 2. 제품 범위 및 비범위

### 2.1 P0 — 반드시 작동해야 하는 MVP

- 그룹 생성과 참여 (QR 초대 링크, 만료되는 일회성 초대 토큰)
- 운행 세션 시작, 중지, 종료 및 복구
- 차량 데이터 수집 (거리 우선, 연비/전비는 지원 가능 범위에서)
- 참여자 탑승·하차 구간과 데이터 출처 표시
- ICE 또는 EV 비용 추정 + 통행료/주차비 입력 + 구간별 N빵
- 사용자별 정산 근거·승인/이의 제기 (금액 전송 없음)
- 공동 캐릭터 '모아' 상태 및 미션·배지
- 운행별 리포트 및 히스토리
- Pleos 실차형 Emulator 데모 + 단위/통합테스트

### 2.2 P1 — P0 완료 후

- NaviHelper 경유지/목적지 연결. 단, 거리의 진실원본을 NaviHelper의 예정경로 거리로 대체하지 않는다.
- 매주 그룹 요약과 반복 동승 추세
- 여러 차량 간 그룹 활동 집계 (다른 차량의 실시간 ECU 직접 수집은 금지)
- 캐릭터 스킨과 정차 후 미니 애니메이션
- 제휴 쿠폰 인터페이스를 가상 캠페인으로 시연

### 2.3 P2 — 계약·승인 후

- 실제 기프티콘 발행/취소/교환, 제휴 브랜드 관리 시스템
- 금융/결제/실송금, 공개 카풀 매칭, 기업 정산 서비스
- Play Store/공식 Pleos App Market 배포를 위한 추가 안전·법률·개인정보 심사

**절대 요구 금지:** 공개 SDK의 미지원 원격 제어, CAN/EDR 무단 접근, 정밀 위치의 암묵적 업로드, 차량 속도 경쟁, 위험한 운전 행동에 보상을 제공하는 시스템.

## 3. 디자인 및 실제 사용자 흐름

### 3.1 주행 전 (차량 주차 상태)

1. 차량용 앱 홈에서 차주가 그룹을 선택한다. '모아' 캐릭터와 최근 이력 확인.
2. `함께 타기` 누름 → 서버가 `INVITING` 세션과 제한시간 있는 QR 링크 발행.
3. 동승자 B/C는 휴대전화 브라우저에서 링크 열기 → 닉네임·동의 → 참가 요청 → 차주 승인.
4. 사용자에게 정산 규칙(운전자 포함/제외, 탑승구간 가중, 비용 항목)을 미리 표시하고 수락을 받는다.
5. 차주가 주차 중 `운행 시작`. Vehicle SDK를 통해 첫 거리 스냅샷을 저장한다.

### 3.2 주행 중 (차량 UI 최소화)

- 차량 화면은 **정적 캐릭터 아이콘 + 동승 인원 + 큰 상태 문구** 정도만 제공한다.
- 비용 상세 편집, QR 가입, 기프티콘, 캐릭터 애니메이션은 숨긴다.
- 차량 데이터는 비동기 이벤트로 기록하고, 커넥션 종료 시 중복 없이 재동기화한다.
- 동승자의 탑승/하차는 모바일 웹의 사용자 확인과 정차 시점 거리 스냅샷을 결합한다. **문 열림 데이터만으로 사람 신원·승하차 완료를 확정하지 않는다.**
- 네트워크가 없어도 차량 앱은 로컬 DB에 운행을 기록하고 동기화한다.

### 3.3 도착 후 (정차 및 주행 제한 해제 시)

1. 운전자가 `운행 종료` → 마지막 거리 스냅샷 저장.
2. 비용 산정 미리보기: 거리, 효율, 유가/전기요금, 통행료, 주차비, 누가 언제 탔는지.
3. 승객들이 휴대폰으로 금액을 확인하고 이의 제기/수정을 요청한다.
4. 정산안이 확정되면 세션을 `CONFIRMED`로 전환한다. 실제 송금은 하지 않는다.
5. **도착 후에만** 모아가 성장하고 배지와 캐릭터 테마가 나타난다.
6. 이동 카드 공유. 타인의 이름·위치·지출이 공개되지 않도록 내보내기 전 개인정보 제거 옵션 제공.

### 3.4 화면 ID

| 화면 | 차량/모바일 | 세부 기능 | 주행 중 정책 |
|---|---|---|---|
| V01 Home | 차량 | 그룹/모아/세션 시작 | 시작 동작 제한 |
| V02 Invite | 차량 | QR, 참가자, 룰 선택 | 숨김 |
| V03 Driving | 차량 | 진행 상태/누적 거리/인원 | 간소화 화면만 |
| V04 Trip Summary | 차량 | 실측 정보·근거·오류 | 상세보기 제한 |
| V05 Settlement | 차량 | 비용 산정 및 확인 | 숨김 |
| V06 Moa Garden | 차량 | 캐릭터 성장·미션 | 숨김 |
| P01 Join | 승객 웹 | 초대 확인, 익명 닉네임, 그룹 가입 | 승객용 기기 |
| P02 Trip | 승객 웹 | 출발/도착 확인, 세션 상태 | 승객이 직접 조작 |
| P03 Bill | 승객 웹 | 정산안 검토/승인/이의제기 | 주행 종료 후 유도 |
| P04 Rewards | 승객 웹 | 비금전 배지, 데모 쿠폰 | 실제 교환 불가 문구 |
| A01 Demo Control | 개발자 전용 | 데이터 공급원, 반복 데모 초기화 | 릴리즈 빌드에서 제거 |

## 4. 공식 Pleos 개발 환경 명세

### 4.1 필수 환경

- Android Studio 최신 안정판, Kotlin, Android Gradle Plugin과 호환되는 JDK
- `minSdk = 28`, `compileSdk = 34` 기준. `targetSdk`는 프로젝트/앱 심사 규정에 맞춰 빌드 시 검증하고 문서화.
- Gradle **8.0 이상** 및 사용 중인 Android Gradle Plugin과 호환되는 버전 사용
- Pleos Emulator **v2.1.0**, Android API 34, Automotive 폼팩터, RAM 4GB, Cold Boot
- Apple Silicon Mac은 ARM64 시스템 이미지, Windows/Linux는 x86 계열 공식 URL 사용

### 4.2 에뮬레이터 이미지 주소

- Mac: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml`
- Windows/Linux: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`

**수동 설정:** Android Studio → Tools → SDK Manager → SDK Update Sites → + → `Pleos Connect System Image` 추가 → SDK Platforms → API 34 Pleos 이미지 설치 → Android Studio 재시작 → Device Manager → Create Device → Automotive → Pleos Connect v2.1.0 → Advanced Settings: RAM 4GB, Cold boot → AVD 생성/실행.

**CLI 확인:**

```bash
adb devices
adb forward tcp:8088 tcp:8088
# PC 브라우저에서 http://127.0.0.1:8088/ 열기
```

Virtual Console에서 `Motion` 도메인의 주행거리, `Energy` 도메인의 전비/배터리/충전 상태 관련 카드 등 실제 표시되는 항목을 변경하고, 앱에서 수집되는 값과 비교한다. **특정 카드가 제공하는 범위를 문서화하며, console 값을 바꾼 것과 앱이 실제 SDK로 읽은 것은 구분해 기록한다.**

### 4.3 SDK 신청 상태 및 범위

공식 프로젝트 생성/SDK 신청 가이드 기준:

- `Vehicle State`, `NaviHelper` — Ready to Use.
- `FusedLocation`, `TextToSpeech`, `SpeechToText`, `Vehicle Control` — Not Available Yet로 안내됨.
- 따라서 P0에서 SDK 제어·전용 STT/TTS·FusedLocation은 사용하지 않는다.
- **앱 자체 로그인 정보/SDK 서버 비밀값은 소스나 APK에 넣지 않는다.** 플레이그라운드 프로젝트 등록·인증이 필요한 기능은 별도로 문서화한다.

## 5. Gradle / Android Manifest

`settings.gradle.kts`의 repositories 항목:

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://nexus-playground.pleos.ai/repository/maven-releases/")
    }
}
```

`app/build.gradle.kts` 핵심 항목(상용 빌드 구성은 기존 프로젝트와 병합):

```kotlin
android {
    namespace = "io.moata.pleos"
    compileSdk = 34
    defaultConfig {
        applicationId = "io.moata.pleos"
        minSdk = 28
        // targetSdk는 최신 공식 빌드/심사 요건 확인 후 설정
    }
}
dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    // 네비게이션 화면까지 구현하는 P1에만 추가
    // implementation("ai.pleos.playground:NaviHelper:3.0.5")
}
```

`AndroidManifest.xml` 최소 읽기 권한:

```xml
<uses-permission android:name="pleos.car.permission.CAR_DRIVE" />
<uses-permission android:name="pleos.car.permission.CAR_ENERGY" />
<!-- NaviHelper 사용이 필요할 경우에만 추가 -->
<!-- <uses-permission android:name="pleos.car.permission.NAVI_ROUTE" /> -->
<!-- <uses-permission android:name="pleos.car.permission.NAVI_ROUTE_SEARCH" /> -->
```

- `CAR_DRIVE`: Odometer 등, `CAR_ENERGY`: Fuel/EvBattery. API별 `@RequiresPermission`을 재검증한다.
- **제어 권한(`CONTROL_CAR_*`) 선언 금지** — 이번 프로젝트는 상태 읽기만 사용한다.
- `INTERNET`은 서버 통신에 필요하며, 디버그 `http`를 허용하는 경우 debug 전용 network security config로 국한한다. release는 HTTPS만 사용한다.
- 구체적인 automotive 앱 선언과 Activity/카테고리는 Pleos 앱 설계·등록 가이드에 맞춰 확인한다. 임의로 플레이스토어 카테고리나 시스템 권한을 가정하지 않는다.

## 6. SDK Adapter 구현 명세 — 가장 먼저 진행

### 6.1 우선 실제 빌드/연동 Probe

**순서:** SDK 의존성 해결 → `Vehicle(context)` → `initialize()` → `getOdometer()` → `getFuel()` 또는 `getEvBattery()` → capability 또는 get 호출 → 결과/오류 로그 → `release()` 순서. `get`과 capability 결과는 `onComplete/onFailed`로 비동기 전달. 즉시 반환으로 오해하거나 callback 도중 `release()`하지 않는다.

공식 API Reference에서 확인한 메서드 이름:

| 데이터 | 도메인 객체 | 문서에 존재하는 API 후보 | 데이터 사용상 주의 |
|---|---|---|---|
| 운행 거리 | `getOdometer()` | `getCurrentDistanceDriven()`, `getTripCurrentDrivingDistance()`, `getTripCumulativeDrivingDistance()`, `registerDistanceDriven()` | 실제 반환 단위, reset 의미, 범위 및 이벤트 빈도는 테스트로 확인 |
| 연비 | `getFuel()` | `getCurrentFuelEconomy()`, `getCumulativeFuelEconomy()`, `getInstantaneousFuelEconomy()` | 현재/누적 연비 의미와 단위(km/L vs L/100km 등) 검증 |
| 전비 | `getEvBattery()` | `getCurrentEvEfficiency()`, `getCumulativeEvEfficiency()`, `getEvBatteryStateOfCharge()` | 전비가 km/kWh로 환산 가능한지 단위 변환 확인 |
| 경로(P1) | `NaviHelper` | `initialize()`, `addListener()`, 경로/목적지 API | 예측 경로거리를 실제 이동거리로 사용 금지 |

구현 규칙:

1. 각 도메인은 기능 지원 여부 확인 가능 시 capability를 먼저 체크한다.
2. 값이 null·0·비정상·역행하거나 단위 불명인 경우 `UNKNOWN`으로 표시하고 **가짜 정상 데이터를 생성하지 않는다**.
3. 수집된 모든 값에 `value`, `unit`, `timestamp`, `source`, `sdkVersion`, `quality`를 보존한다.
4. Odometer의 기준점은 운행 시작·승하차 구간 경계·종료 시 기록한다. 앱이 종료되어도 Room DB에 저장한다.
5. Event Listener를 등록했다면 라이프사이클에 맞게 해제한다. 네트워크 재시도와 SDK 커넥션 재연결을 구분한다.
6. SDK 메서드의 **매개변수 타입과 반환형은 다운로드된 AAR/API Reference를 근거로 확인**한다. 여기서는 확인된 이름만 명시하며 호출 시그니처를 추측해 작성하지 않는다.

### 6.2 데이터 추상화

```kotlin
enum class DataSource { PLEOS, MANUAL, DEMO }
enum class ValueQuality { VERIFIED, ESTIMATED, UNAVAILABLE, INVALID }

data class VehicleSample(
    val capturedAtEpochMs: Long,
    val distanceKm: Double?,
    val fuelEconomyKmPerL: Double?,
    val evEfficiencyKmPerKwh: Double?,
    val batterySocPct: Double?,
    val source: DataSource,
    val quality: ValueQuality
)

interface VehicleDataSource {
    suspend fun connect(): Result<Unit>
    suspend fun snapshot(): VehicleSample
    fun observe(): kotlinx.coroutines.flow.Flow<VehicleSample>
    suspend fun disconnect()
}
```

구현체:

- `PleosVehicleDataSource`: 실제 SDK. P0에서 가장 중요한 클래스.
- `DemoVehicleDataSource`: deterministic fixture. `DEMO` 붉은/회색 상시 마크, 릴리즈에서 기본 비활성, 실제 API 연결 성공으로 포장 금지.
- `ManualVehicleDataSource`: SDK 불가 차량에서 사용자가 계기판 주행거리/연비를 직접 입력하는 대안. `MANUAL` 명시.

### 6.3 거리의 진실원본

- 우선적으로 **검증된 누적 주행거리 스냅샷의 차이**를 사용한다. API의 `CurrentDistanceDriven`가 어떤 계측 의미인지 검증하기 전에는 '누적 총주행거리'로 단정하지 않는다.
- `distanceSegmentKm = endKm - startKm`. 음수, 비정상 대폭 점프, 값 없음은 검증실패로 표시하고 사용자 수동 교정+감사기록을 요청한다.
- 세션 중간 누락이 있다면 마지막 유효한 경계값과 정상 재연결값으로 구간 복구 가능성을 확인한다. 불가능하면 정확한 구간별 정산을 주장하지 않는다.
- 단위를 저장하고 내부 연산 전에 km로 정규화한다. 0km를 기본값으로 채우지 않는다.

## 7. 데이터 모델 및 상태 기계

### 7.1 상태

`DRAFT → INVITING → READY → IN_PROGRESS → ENDED → REVIEW → CONFIRMED`

비정상 상태 `INTERRUPTED`, `CANCELLED`, `DISPUTED`를 추가한다.

- `startTrip`은 `READY`에서만 한 번 성공.
- `endTrip`은 `IN_PROGRESS`에서만 성공; idempotency key로 중복 종료 방지.
- `CONFIRMED` 이후 원본 센서 기록 불변. 수정 시 새로운 `SettlementRevision` 기록.
- 운행 중 서버 장애가 나도 로컬 오프라인 저장 후 동기화한다.

### 7.2 엔티티 (필수 필드)

```text
User(id, nickname, created_at, consent_version)
Group(id, name, owner_user_id, created_at)
GroupMember(group_id, user_id, role, joined_at, left_at)
Invite(id, group_id, trip_id, token_hash, expires_at, max_uses, used_count)
Trip(id, group_id, vehicle_id_alias, state, driver_user_id, started_at, ended_at,
     data_mode, odometer_start_km, odometer_end_km, revision)
ParticipantInterval(id, trip_id, user_id, onboard_at, offboard_at,
                    odometer_on_km, odometer_off_km, confirmation_status)
VehicleReading(id, trip_id, captured_at, type, raw_value, raw_unit, normalized_value,
               normalized_unit, source, quality, sdk_version)
CostRule(trip_id, vehicle_type, unit_price_krw, efficiency_value,
         efficiency_unit, driver_included, shared_cost_policy)
CostItem(id, trip_id, type, amount_krw, payer_id, receipt_ref, input_source)
Settlement(id, trip_id, revision, state, total_cost_krw, created_at)
SettlementShare(settlement_id, user_id, amount_krw, distance_weight)
SettlementApproval(settlement_id, user_id, decision, decided_at)
CharacterProgress(group_id, xp, level, skin_id, updated_at)
RewardEvent(id, group_id, trip_id, rule_id, xp_delta, created_at, unique_key)
DemoCoupon(id, group_id, brand_label, status, exchangeable=false)
AuditEvent(id, actor_id, entity_type, entity_id, action, at, diff_json)
```

- 위치 좌표는 MVP의 필수 데이터가 아니다. 무분별한 경로 저장을 피하고, 거리 데이터와 세션 식별자만 우선 사용한다.
- 차량 VIN, 휴대전화 번호, 실명은 MVP 저장 요구사항에서 제외한다.
- 초대 토큰과 인증 토큰은 평문 영구 저장 금지.

## 8. 비용 계산 명세

### 8.1 정확히 무엇을 추정하는가

**ICE** `fuel_liters = distance_km / fuel_economy_km_per_l`  
`energy_cost_krw = fuel_liters * price_krw_per_l`

**EV** `electricity_kwh = distance_km / efficiency_km_per_kwh`  
`energy_cost_krw = electricity_kwh * price_krw_per_kwh`

- 연비·전비는 **자동차 데이터가 지원되고 단위가 확인되면 우선 사용**, 미지원이면 운전자가 평균값을 입력.
- 연료 탱크 잔량·배터리 SoC 단순 차이만으로 실제 주행 에너지 비용을 계산하지 않는다.
- 전력요금/유가는 사용자가 입력한다. 외부 시세 API는 P1이며 영수증과 실제 비용 차이를 안내한다.
- 통행료·주차비는 운전자가 수동 입력하고 항목별 배분 방식을 선택한다.
- 비용은 수익/수수료가 아니라, 이용자가 합의한 **참고용 정산안**으로만 표시한다.

### 8.2 구간 정산

구간별 센서 경계값이 충분할 때, `S1` 0–10km에 운전자 A, B, C 세 명, `S2` 10–24km에 운전자 A와 B 두 명이 있었다고 하자.

- 총 24km, 연비 12 km/L, 유가 1,800원/L → 에너지 비용 3,600원.
- `S1`: 10km → 1,500원 → 세 명에게 500원씩.
- `S2`: 14km → 2,100원 → 두 명에게 1,050원씩.
- 에너지 비용 결과: `A=1,550원`, `B=1,550원`, `C=500원`, 합계 3,600원.
- 별도 통행료 2,400원을 **전체 참석자 균등**으로 배분하면 세 명에게 800원씩 추가 → `A=2,350원`, `B=2,350원`, `C=1,300원`, 합계 6,000원.
- 기본 규칙은 운전자를 포함하며, `driver_included=false`인 경우 2차 사용자 합의 및 테스트된 정책으로만 제공한다. 운전자만 탑승한 구간은 기본적으로 운전자에게 배분한다.

**Rounding:** 내부 비용을 `Decimal/BigDecimal`로 계산하고, 각 구간에서 원 단위 결과가 합계와 같도록 Largest Remainder 및 안정적인 user ID tie-break로 잔액 원을 할당한다. float로 돈을 최종 계산하지 않는다.

### 8.3 무효·미지원 값

- `distance < 0`, `efficiency <= 0`, `NaN/Infinity`, 단위 불명, 중복 경계값, 불완전 동승 구간 → 상세 오류 표시 및 수정 확인 요구.
- 참가자 미승인 상태에서는 '정산 완료'로 표시하지 않음.
- P0에서는 송금 버튼 대신 `정산안 복사/공유`와 `확인`만 제공.

## 9. 캐릭터·보상 시스템

### 9.1 캐릭터 컨셉

**모아**: 작은 바퀴와 둥근 몸통을 가진 독창적인 팀 마스코트. 주행 중에는 고정 아이콘, 정차 후 이동 카드에서 표정·장식·레벨이 바뀐다.

- 성장 규칙은 돈·주행거리·속도와 직접 비례하지 않는다.
- `verifiedCompletedTrip` 1회당 팀 XP 10 (해당 Trip ID에 1번만).
- 서로 다른 두 사람 이상이 함께 완료한 세션에서만 기본 XP 지급.
- 연속 주행·장거리 운전·급가속·급제동을 유도하는 보상은 금지.
- 레벨: Lv.1 (0), Lv.2 (30), Lv.3 (70), Lv.4 (130) 등. 수치는 데모 규칙이며 서버 설정에 분리.
- 팀 전체 프로필에는 동승 횟수, 완료 여행 카드와 획득 배지를 표시한다.

### 9.2 기프티콘 및 사업 모델

MVP는 실제 돈/금전적 가치가 없는 **데모 쿠폰**을 발행한다. UI에 `시연용 / 실제 교환 불가`를 명시한다.

후속 사업 모델: 캐릭터 스킨, 프리미엄 그룹 테마, 제휴 브랜드의 이벤트 쿠폰, 사내 통근 커뮤니티용 관리 대시보드. 제휴 쿠폰은 해당 브랜드의 정식 발행·취소 API 계약, 비용 부담 주체, 환불/만료/중복발행 방지 검증이 완료될 때만 제공.

- 보상 이벤트는 `(trip_id, reward_rule_id)` unique key로 중복 지급 방지.
- 개발 편의를 위해 가짜 쿠폰을 실제 교환되는 쿠폰처럼 소개하지 않는다.
- 이동거리/이용횟수를 무한히 늘리도록 유도하는 마케팅은 피한다.

## 10. 제안 기술 아키텍처

```text
                          ┌──────────────────────────────┐
                          │   Pleos Android Automotive  │
                          │  Kotlin / Compose / Room     │
                          │  Real Vehicle SDK adapter   │
                          │  Offline events + sync      │
                          └──────────────┬───────────────┘
                                         │ REST/HTTPS
                    ┌────────────────────▼───────────────────┐
                    │ FastAPI Backend                        │
                    │ group/trip/interval/settlement/reward  │
                    │ PostgreSQL (Docker)                    │
                    └────────────────────▲───────────────────┘
                                         │ REST/HTTPS
                          ┌──────────────┴───────────────┐
                          │ Passenger mobile web/PWA    │
                          │ Next.js / TypeScript        │
                          │ QR join, onboard, approval  │
                          └──────────────────────────────┘
```

**필수 분리:** 운행 기록·정산 규칙의 권위는 서버에 두되, 차량 앱 로컬 DB에 미동기화 이벤트를 보존한다. 서버를 쓰지 못하는 데모 시나리오도 미리 정의한다(단일 에뮬레이터 Mock QR 게스트). 

**권장 repo 구조:**

```text
moata/
  README.md
  .env.example
  docker-compose.yml
  docs/
    PRD.md
    SDK_VERIFICATION.md
    API_CONTRACT.md
    DEMO_RUNBOOK.md
    TEST_REPORT.md
    LEGAL_AND_PRIVACY.md
  car-app/
    app/src/main/
      java/io/moata/pleos/
        core/vehicle/{VehicleDataSource,PleosVehicleDataSource,DemoVehicleDataSource}
        data/local/{entities,dao}
        data/remote/
        domain/{TripManager,SettlementCalculator,RewardPolicy}
        ui/{home,invite,driving,summary,settlement,character}
  passenger-web/
    app/{join,trip,settlement,rewards}
    src/{api,components,lib}
  backend/
    app/{main.py,api,models,schemas,services,auth,db}
    tests/
  scripts/{run_backend.sh,run_demo.sh,check_env.sh}
  apk/
```

**기술 스택:** Kotlin + Coroutines + Jetpack Compose + Room / Next.js + TypeScript + Tailwind / Python 3.11+ FastAPI + SQLAlchemy + PostgreSQL + pytest. 패키지 버전은 환경에 맞는 안정 릴리즈를 pin하고 lockfile 저장. 빌드 확인 없이 새 라이브러리 추가 금지.

## 11. Backend API 계약 (초안)

**REST API prefix:** `/api/v1`  
모든 쓰기 요청은 인증된 사용자의 그룹/세션 권한 확인이 필요하다. `Idempotency-Key`로 생성·종료·보상 지급 중복 방지.

| Method | Path | 역할 |
|---|---|---|
| POST | `/groups` | 그룹 생성 |
| GET | `/groups/{groupId}` | 권한 있는 그룹 조회 |
| POST | `/groups/{groupId}/invites` | 만료되는 QR 토큰 생성 |
| POST | `/invites/accept` | 초대 수락 및 그룹 참가 |
| POST | `/trips` | 그룹의 운행 세션 생성 |
| POST | `/trips/{tripId}/start` | 시작, 첫 거리 스냅샷 연결 |
| POST | `/trips/{tripId}/participants/{userId}/board` | 탑승 확인 |
| POST | `/trips/{tripId}/participants/{userId}/alight` | 하차 확인 |
| POST | `/trips/{tripId}/readings/batch` | 오프라인 이벤트 일괄 동기화 |
| POST | `/trips/{tripId}/end` | 운행 종료, 마지막 스냅샷 |
| POST | `/trips/{tripId}/settlements/preview` | 정산안 생성 |
| POST | `/settlements/{settlementId}/approvals` | 승인/이의 제기 |
| GET | `/trips/{tripId}/summary` | 거리·비용·데이터 품질 |
| GET | `/groups/{groupId}/character` | 캐릭터 상태 |
| GET | `/groups/{groupId}/history` | 이동 이력 |
| GET | `/health` | 서버 상태 확인 |

**응답 형식:** ISO8601 UTC timestamps, stable opaque UUID, integer KRW, decimal string for precise costs, enum code and user-facing Korean message. API 계약을 `openapi.yaml`로 저장하고 Android/TS 클라이언트는 계약을 기준으로 개발한다.

**구현 보안:** 회원 초대는 서명된 단기 토큰/서버 검증; 서버에서 사용자별 접근 검증. 직접 `/trips/{id}`를 호출해 타인 정보를 볼 수 없어야 한다. 이메일/SMS 전송 없이 임시 게스트 인증을 사용하는 데모는 제한된 테스트 환경에서만 사용한다. CORS 허용 오리진 최소화, 배포 HTTPS, SQL 인젝션 방지, 요청 크기 제한, 로그에서 토큰과 개인식별 정보 제거.

## 12. 테스트 명세

### 12.1 Android SDK Probe (실제 에뮬레이터 필수)

- AND-01: Emulator v2.1.0/API 34에서 `adb devices` 인식.
- AND-02: `Vehicle:2.3.2` Gradle 의존성 resolve/compile 성공 또는 정확한 장애 로그.
- AND-03: `CAR_DRIVE`, `CAR_ENERGY` 선언 후 SDK `initialize()` 성공.
- AND-04: `getOdometer()`로 거리 응답 또는 명확한 `onFailed` 수집.
- AND-05: Virtual Console Motion 거리 변경 → 앱 Vehicle SDK에서 해당 변화를 확인(방법과 실제 리턴값 로그).
- AND-06: ICE 연비 또는 EV 전비 접근 성공/실패 및 단위 표기 확인.
- AND-07: 미지원 기능에서 Null/Permission 오류 → 화면에 “사용 불가” 표시, **Demo 자동전환 금지**.
- AND-08: 앱 백그라운드/재실행 후 중복 운행 생성되지 않음.
- AND-09: callback 종료 전 `release()` 금지, 구독/해제 검증.
- AND-10: 주행 UX 제한 활성화 시 QR·금액 편집·애니메이션 숨김.

### 12.2 정산 Unit Tests

- SET-01: 전원 전체 탑승, 24km/12kmL/1800원L/통행료 2400원/3명 = 2000원씩.
- SET-02: 중도 하차 구간, 0~10km 3명 / 10~24km 2명 = 에너지비 1550/1550/500원.
- SET-03: SET-02 + 통행료 전체 참석자 균등 = 2350/2350/1300원.
- SET-04: EV 60km/5kmkWh/250원kWh = 3000원 총 에너지비.
- SET-05: 1000원 3분할, 합계 = 정확히 1000원, 결정적 tie-break.
- SET-06: 거리 역행, 전비 0, 음수 가격, NaN/Infinity 입력 차단.
- SET-07: 운전자 포함/제외 정책 변화 시 일관된 분담.
- SET-08: 중도 탑승·하차 타임스탬프 역전 및 겹친 구간 검증.
- SET-09: 오류 교정 후 원본 읽기 기록은 변하지 않고 수정 로그 생성.
- SET-10: 동일 요청 재시도에도 정산 revision 중복 생성 안 됨.

### 12.3 통합/API/캐릭터

- IT-01: QR 생성 → 승객 2명 참여 → 출발 → 하차 → 정산 승인.
- IT-02: 만료/변조/타인 소유 초대 링크 거부.
- IT-03: 서버 끊김 → 로컬 저장 → 재연결 → 이벤트 중복 없이 전송.
- IT-04: 세션 종료 두 번 눌러도 한 세션·한 정산.
- IT-05: 동일 Trip에 XP 두 번 지급되지 않음.
- IT-06: 주행 중 리워드/게임/기프티콘 화면 차단.
- IT-07: 데모 쿠폰 실제 교환 불가 문구 확인.
- IT-08: 로그/공유카드에 그룹 외 사람 개인정보 노출 없음.

**최소 통과 기준:** Backend unit/integration 전부 통과, Android `assembleDebug`와 `testDebugUnitTest` 통과, 실제 Emulator에서 AND-01~10 결과 기록. SDK 미지원이나 외부 환경 부재는 `BLOCKED_ENVIRONMENT`로 정확히 기록하고 `PASS`로 둔갑시키지 않는다.

## 13. PT 시연(90~120초)과 근거자료

| 순서 | 관객에게 보여줄 장면 | 검증 포인트 |
|---|---|---|
| 1 | 차량 화면의 모아 캐릭터와 그룹 | 차 안에서 시작하는 앱 |
| 2 | 승객 2명이 스마트폰 QR로 참가 | 차량 + 승객 웹 연동 |
| 3 | Virtual Console Motion에서 거리 변화 | Pleos SDK 실측 연동 |
| 4 | 한 명이 중간에 하차 | 구간별 기록 |
| 5 | 운행 종료 → 정확한 N빵 계산 | 수학적 신뢰성 |
| 6 | 세 명이 확인 → 모아 성장 | 감성/리텐션 |
| 7 | 월간 기록과 데모 기프티콘 | 확장성/사업성, 시연용 명시 |

**데모는 두 가지를 준비한다.**

- **Real Emulator Mode:** SDK에서 읽힌 값을 그대로 저장하며 실제 데이터 출처 표시. 실패하면 실패 화면도 기록.
- **Offline Demo Mode:** 동일한 24km fixture를 재현할 수 있는 개발자 전용 모드. 상단에 `DEMO / 가상 데이터` 표시. 발표에서 구분해 설명.

필수 제출 증빙: `docs/SDK_VERIFICATION.md`, 에뮬레이터 스크린샷/화면 녹화, `docs/TEST_REPORT.md`, 빌드 로그, `apk/팀명_모아타_release.apk` (실제 서명된 release 빌드만).

## 14. 작업 단계 / 강제 품질 게이트

**Phase 0 — Discovery**: 레포 파악, OS/SDK/Gradle/JDK/adb 확인, 공식 docs 대조, 버전 충돌 기록, 체크리스트 작성. 기존 코드 삭제 금지.

**Phase 1 — SDK Probe**: 차량 전용 Kotlin 프로젝트 최소 구조 생성, Maven + Manifest + `Vehicle` 초기화, Odometer/Fuel/EV 응답 읽기, 실제 Emulator로 검증. 실패하면 Mock 연결로 실제 SDK 성공이라고 주장하지 말고 장애 표시.

**Phase 2 — Domain Logic**: Session state machine, 구간 스냅샷, 비용 계산, 원단위 결정적 분배, 시간·거리·단위 검증, Unit tests.

**Phase 3 — Backend**: DB 마이그레이션, 인증, 그룹/초대/세션/정산/보상 API, idempotency, pytest.

**Phase 4 — Vehicle UI + Passenger Web**: 주행 전/중/후 화면, QR, 승인/이의제기, 캐릭터, 데이터 품질 뱃지, 백엔드 연결.

**Phase 5 — End-to-End**: Virtual Console 이벤트, 동승자 웹, 정산 검증, 주행 중 제한, 장애복구 테스트.

**Phase 6 — Delivery**: README/Korean+English setup, `.env.example`, 실행 스크립트, release APK 가능 시 제작, 데모 시나리오, TODO/미지원 기능 표. 실제 실험을 하지 못한 항목은 "검증하지 못함"으로 남긴다.

**각 Phase에서 요구:** 구현 파일 목록, 실행한 명령, pass/fail 결과, 장애 원인, 다음 작업. 오류를 숨기고 초록 체크 처리 금지. 존재하지 않는 API 클래스·메서드·권한은 임의로 꾸며 넣지 않는다.

## 15. 개발 환경 명령

```bash
# 0. 환경 확인
uname -a
java -version
adb version
adb devices
# Android SDK 위치는 ANDROID_HOME/ANDROID_SDK_ROOT 또는 Android Studio에서 확인

# 1. Android Gradle: 레포 생성 이후
cd car-app
./gradlew --version
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
# Emulator가 있는 환경에서만
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 2. Backend: repo root 기준
cd ../backend
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
pytest -q
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000

# 3. Passenger web
cd ../passenger-web
npm ci
npm run lint
npm run build
npm run dev

# 4. Virtual Console
adb forward tcp:8088 tcp:8088
# 브라우저: http://127.0.0.1:8088/
```

**연결 주의:** Android Emulator → 호스트에서 실행 중인 Backend로 접속할 때 `10.0.2.2:8000` 사용 여부를 실제 Emulator 네트워킹으로 검증한다. 휴대폰 브라우저에서는 `10.0.2.2`를 사용할 수 없으므로, 같은 LAN의 PC IP 또는 HTTPS 테스트 주소를 사용한다. 실제 프로덕션은 HTTPS 필수. 서버 호스트의 기본 방화벽과 인증 정책을 고려한다.

## 16. 지원 범위 리스크 / 즉시 해결 원칙

| 위험 | 행동 |
|---|---|
| SDK Maven 접근 불가 | URL, 에러 로그, 환경 기록 → DemoDataSource와 도메인 구현은 진행, 실제 연동 BLOCKED 표시 |
| SDK 값 반환이 0/Null | Vehicle capability/permissions/Console 상태 점검, 무단 fabricated 값 금지 |
| Odometer 의미 불명 | 단위·기준점·Reset 확인, 미확인 시 구간 정산 정확성 보장 금지 |
| 앱이 주행 중 제한 상태 | CarUxRestrictions 우선, QR·정산·캐릭터 성장 애니메이션 비활성화 |
| 네트워크 중단 | 로컬 append-only 이벤트 및 동기화 큐, idempotency |
| 서로 다른 시점의 Boarding | 각 승하차 경계의 snapshot, 누락 시 수정 요청 |
| 적법성/금융 규제 | 공개 매칭·자동 송금 미구현, 국내 변호사/규제 기관 확인 전 유상 서비스 비활성화 |
| 어린이/개인정보 | MVP는 성인 초대 그룹, 실제 차량 식별 정보 최소화 |
| 쿠폰 제휴 부재 | 비금전적 배지와 `교환 불가` 데모 쿠폰 |

## 17. 완료 정의 (Definition of Done)

- [ ] Android APK가 실제 Pleos Emulator v2.1.0(API 34)에서 실행된다.
- [ ] Vehicle SDK 기반 거리 읽기/변경 데모가 성공하거나, 실제 장애가 정확히 기록되어 있다.
- [ ] P0의 그룹/탑승/거리/연비·전비/정산/캐릭터/보고서가 일관되게 연결된다.
- [ ] 차량 데이터 `PLEOS`, 직접입력 `MANUAL`, 모의 데이터 `DEMO`가 모든 관련 화면에 구분 표시된다.
- [ ] 비용 결과의 합계가 항상 일치하며 이상값/중복/경계 누락에서 실패를 정확히 처리한다.
- [ ] 주행 중 주의 분산 UI가 제한된다.
- [ ] 적법성/사업성 관련 미구현 기능은 사용자에게 제공하지 않는다.
- [ ] 전체 실행 명령, 테스트 결과, 설정·비밀값 관리, 데모 스크립트, APK 경로가 README에 있다.
- [ ] 실제 미실행 항목을 성공했다고 주장하지 않는다.

## 18. 사업성과 수상 PT 메시지

**메인 메시지:** "자동차가 오늘의 공동 이동을 실제로 기록하고, 누구도 계산 때문에 어색해지지 않도록 정산을 만들며, 함께 달린 기억을 캐릭터로 남겨줍니다."

심사위원에게 보여줄 세 가지 증거:

1. **차량 데이터:** Virtual Console 거리 변화 → Vehicle SDK → 운행 기록.
2. **공정한 분담:** 중도 하차가 즉시 계산 결과에 반영되고 수치 검증 테스트가 있다.
3. **지속 이용:** 함께 이동할수록 변하는 팀 캐릭터와 장기 기록·비금전적 혜택.

사업 모델 가정은 가정으로 제시하고, 실제 파트너/쿠폰 계약·사용자 수·MAU를 허위로 적지 않는다. 차량의 특화 가치와 일반 비용정산 앱의 차이를 분명히 보여준다.

---

**문서 작성 원칙:** 상기 공식 문서에 확인된 공개 API/환경 정보를 토대로 하되 실제 빌드·에뮬레이터 검증을 완료했다고 주장하지 않는다. 이 명세는 구현 지시서이며 실행 결과 보고서는 별도로 생산해야 한다.
