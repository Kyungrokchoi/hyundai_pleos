# 90초 데모 런북

두 가지 모드가 있다. **Real Emulator Mode** 는 Pleos 에뮬레이터의 실제 Vehicle SDK 값을 쓰고, **Offline Demo Mode** 는 DEMO 가상 데이터(화면에 `DEMO / 가상 데이터` 상시 표시)로 같은 24 km 시나리오를 재현한다. 발표에서 둘을 구분해서 설명한다.

## 0. 준비 (한 번, 약 3분)

```bash
# 터미널 1 — API + 승객 웹 (처음 한 번만 passenger-web 에서 npm ci)
scripts/run_demo.sh                       # API :8000, 웹 :3000 (LAN IP 가 출력됨)

# 터미널 2 — 에뮬레이터
export ANDROID_SDK_ROOT=/opt/homebrew/share/android-commandlinetools
$ANDROID_SDK_ROOT/emulator/emulator -avd MOATA_Pleos_API34 -no-snapshot-load -no-boot-anim -gpu host   # 창이 뜸
adb forward tcp:8088 tcp:8088             # Virtual Console: http://127.0.0.1:8088/
scripts/console_heartbeat.sh &            # 하트비트 없으면 콘솔 쓰기가 503 emulator_inactive (또는 브라우저 탭을 열어 둠)
adb install -r apk/moata-debug.apk
curl -X POST "http://127.0.0.1:8088/vehicle/odometer?traveled_distance_km=12500"
```

승객 휴대폰은 같은 Wi-Fi 에서 `http://<LAN IP>:3000` 을 연다. **차량 앱의 QR 은 에뮬레이터 기준 주소(`10.0.2.2`)** 를 인코딩하므로 실제 폰으로 스캔하려면 앱을 다시 빌드한다: `cd car-app && ./gradlew :app:assembleDebug -Pmoata.webBaseUrl=http://<LAN IP>:3000`. QR 아래의 주소 글자는 없앴다(초대 토큰이 화면에 그대로 보이고 운전자에게는 잡음). 에뮬레이터 화면만으로 시연할 때는 스크린샷의 QR 을 디코딩해 `10.0.2.2` 를 `<LAN IP>` 로 바꾼다(검증 때는 Gradle 캐시의 ZXing core 로 디코딩).

## 1. Real Emulator Mode (90초)

| 시간 | 차량(에뮬레이터) | 동승자 폰 | 보여줄 포인트 |
|---|---|---|---|
| 0:00 | 앱 `모아타` → 닉네임 → `동의하고 시작하기` → `그룹 만들기` | | 화면마다 **할 일이 하나**, 큰 글씨 |
| 0:10 | `함께 타기` → (처음 한 번만) **유가**를 숫자패드로 입력 `1800` → `QR 만들기` | | 설정은 유가 하나. 다음부터는 기억해서 `함께 타기` → **바로 QR** |
| 0:25 | 큰 QR 표시 | QR 스캔 → 닉네임 → 규칙 확인 → `참여 요청 보내기` | 차량에 `승인 대기 2명`이 뜸 → 큰 `승인` 버튼 |
| 0:35 | 차량에 `탔어요 1/2명` | 승인되면 바로 `탔어요` (**출발 전, 정차 중**) | 탄 곳부터 계산. 안 누르면 정산에서 빠지는 문제를 출발 전에 막는다 |
| 0:40 | `출발하기` → 안 누른 사람이 있으면 **`지호님도 탔나요?`** → `모두 탔어요 · 출발` (운전자가 대신 표시, 감사 로그 `by_driver`) | | 시작 주행거리 스냅샷에서 모두 탑승 |
| 0:50 | **Virtual Console 에서 Motion 주행거리 +16 km** → 차량에 큰 `16.0 km`, 탄 사람 이름 | 승객 B `내렸어요` | 구간 기록: 한 명이 중간 하차 |
| 1:05 | 주행거리 +8 km → `도착했어요` → `네, 도착했어요` | | 실수로 눌러도 확인 창이 한 번 더 묻는다 |
| 1:10 | 도착 요약: **`24km를 함께 왔어요`** + 사람별 **구간 막대**(지호 `16km에서 내림`). 이 에뮬레이터는 연비를 안 줘서 `연비 입력`(숫자패드 `12`), `+ 통행료` `2400` → `정산안 만들기` | 정산 화면 | 큰 합계 `6,000원` + 산식 + 사람별 카드 **2,200 / 2,200 / 1,600** (지호는 16 km만 탔음) |
| 1:20 | `내 몫 승인` | 정산서: 출처 줄(`PLEOS`/`MANUAL`), 구간 막대, `2,400원 ÷ 3명 = 800원` → `승인할게요` | 전원 승인 → **`금액 합의 완료`** + `1,600원 복사`(송금은 각자 직접) → 모아 XP +10 |
| 1:30 | (선택) 콘솔에서 기어 **Drive**, 속도 40 | | `주행 중 · 기능 제한` — 정적 모아만 |

콘솔 명령(브라우저 대신): 주행거리 `curl -X POST "http://127.0.0.1:8088/vehicle/odometer?traveled_distance_km=12510"`, 기어 `curl -X PUT "http://127.0.0.1:8088/vehicle/gear?gear=Drive"`, 속도 `curl -X PUT "http://127.0.0.1:8088/vehicle/controls?speed=40&accelerator=0&brake=0&steering=0"`.

화면 자동 조작(촬영/검증용): `python3 scripts/adb_ui.py texts|tap "텍스트"|xy X Y|type ascii|shot out.png` — adb 는 한글을 입력하지 못하므로 닉네임은 ASCII 로 넣는다. 차량 이미지는 멀티유저라 앱 파일은 `adb shell run-as io.moata.pleos --user 10 …`.

## 2. Offline Demo Mode (서버만 있으면 됨)

```bash
python3 scripts/demo_e2e.py        # 12개 확인 PASS/FAIL, 모든 차량 값은 DEMO 로 표기
```

차량 앱 debug 빌드의 `함께 타기 준비` 화면에서 **데이터 출처 = `DEMO (가상)`** 을 고르면 화면에 `DEMO / 가상 데이터` 표시가 상시 붙는다(릴리스 빌드에서는 비활성).

## 3. 문제 해결

| 증상 | 원인 / 조치 |
|---|---|
| 콘솔 쓰기 503 `emulator_inactive` | 하트비트 필요: `scripts/console_heartbeat.sh` 또는 콘솔 탭을 열어 둔다 |
| `run-as … No such file` | `--user 10` 추가 |
| 앱에 “로그인이 만료되었어요” | 서버 DB 를 새로 만들어 토큰이 사라짐 → 앱 데이터 삭제 `adb shell pm clear --user 10 io.moata.pleos` |
| 승객 폰이 API 에 접속 못 함 | `MOATA_CORS_ORIGINS` 에 `http://<LAN IP>:3000` 포함(`run_demo.sh` 가 자동 설정), 같은 Wi-Fi 확인 |
| 차량 앱이 `서버에 연결할 수 없어요` (logcat `MOATA: offline: … 10.0.2.2:8000`) | 에뮬레이터 Wi-Fi 연결이 끊김(`dumpsys connectivity` 에 `Active default network: none`). 차량 `설정 → 연결 → Wi-Fi → AndroidWifi` 를 누른다. 프로덕션 이미지라 `adb shell cmd wifi connect-network` 는 SecurityException |
| 폰에서 `지금은 탑승·하차를 기록할 수 없는 상태입니다` (출발 전) | 백엔드가 옛 코드로 떠 있음(`--reload` 없음). 백엔드를 고친 뒤에는 :8000 을 같은 `MOATA_DATABASE_URL` 로 재시작 |
| 같은 그룹 두 번째 운행에서 승객 폰이 지난 운행만 보임 | 고쳤음(`current_trip_id`). 폰 홈 `오늘 이동 보기` 또는 새 QR. 승인된 멤버는 다시 승인받지 않는다 |
| 차량에 `차량 주행거리를 제대로 읽지 못했어요` | SDK 가 0 이나 이전보다 작은 값을 줌(앱 재시작 직후 관찰). 몇 초 뒤 다시 누른다. 값을 임의로 바꾸지 않는다 |
| `보내지 못한 기록 n건` 이 사라지지 않음 | 서버가 거절한 기록(예: 취소된 운행). 그 운행을 `측정 정보 자세히 → 이 운행 닫기` 하면 정리되고 몇 건인지 알려 준다 |
| 연비가 항상 “확인 불가” | 에뮬레이터가 연비/전비를 제공하지 않는다(실제 확인됨). `직접 입력` 사용 |
