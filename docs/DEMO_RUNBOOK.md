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
| 0:10 | `함께 타기` → **유가는 오피넷 전국 평균이 자동으로 채워진다**(`오피넷 전국 평균 · 자동 입력`, 숫자패드로 고칠 수도 있고 고치면 라벨이 사라진다) → `QR 만들기` | | 입력 없이 바로 QR. 연비는 묻지 않는다(아래 도착 요약에서 자동 표시) |
| 0:25 | 큰 QR 표시 | QR 스캔 → 닉네임 → 규칙 확인 → `참여 요청 보내기` | 차량에 `승인 대기 2명`이 뜸 → 큰 `승인` 버튼 |
| 0:35 | 차량에 `탔어요 1/2명` | 승인되면 바로 `탔어요` (**출발 전, 정차 중**) | 탄 곳부터 계산. 안 누르면 정산에서 빠지는 문제를 출발 전에 막는다 |
| 0:40 | `출발하기` → 안 누른 사람이 있으면 **`지호님도 탔나요?`** → `모두 탔어요 · 출발` (운전자가 대신 표시, 감사 로그 `by_driver`) | | 시작 주행거리 스냅샷에서 모두 탑승 |
| 0:50 | **Virtual Console 에서 Motion 주행거리 +16 km** → 차량에 큰 `16.0 km`, 탄 사람 이름 | 승객 B `내렸어요` | 구간 기록: 한 명이 중간 하차 |
| 1:05 | 주행거리 +8 km → `도착했어요` → `네, 도착했어요` | | 실수로 눌러도 확인 창이 한 번 더 묻는다 |
| 1:10 | 도착 요약: **`24km를 함께 왔어요`** + 사람별 **구간 막대**(지호 `16km에서 내림`). **연비 줄이 자동으로 나온다**(`연비 10.4 km/L · 표시연비 평균 추정`, 이 에뮬레이터는 차량 연비를 안 줘서 평균 추정). 유료도로를 지났다면 **통행료 카드와 지나온 길 지도가 자동으로 나오고 정산에 반영된다**(1-b). 입력할 것은 없다 → `정산안 만들기` | 정산 화면 | 큰 합계 + 산식 `24km ÷ 10.4km/L × 유가` + 통행료 줄(`· 추정`) + 사람별 카드(지호는 16 km만 탔음) |
| 1:20 | `내 몫 승인` | 정산서: 출처 줄(유가 `OPINET`, 연비 `표시연비 평균(추정)`, 통행료 `추정`), 구간 막대, 통행료 `동수원→서울 추정` → `승인할게요` | 전원 승인 → **`금액 합의 완료`** + 금액 복사(송금은 각자 직접) → 모아 XP +10 |
| 1:30 | (선택) 콘솔에서 기어 **Drive**, 속도 40 | | `주행 중 · 기능 제한` — 정적 모아만 |

콘솔 명령(브라우저 대신): 주행거리 `curl -X POST "http://127.0.0.1:8088/vehicle/odometer?traveled_distance_km=12510"`, 기어 `curl -X PUT "http://127.0.0.1:8088/vehicle/gear?gear=Drive"`, 속도 `curl -X PUT "http://127.0.0.1:8088/vehicle/controls?speed=40&accelerator=0&brake=0&steering=0"`.

화면 자동 조작(촬영/검증용): `python3 scripts/adb_ui.py texts|tap "텍스트"|xy X Y|type ascii|shot out.png` — adb 는 한글을 입력하지 못하므로 닉네임은 ASCII 로 넣는다. 차량 이미지는 멀티유저라 앱 파일은 `adb shell run-as io.moata.pleos --user 10 …`.

## 1-b. 통행료 시연 시나리오 (유료도로를 반드시 지나가는 케이스)

통행료는 GPS 궤적이 고속도로 영업소를 지나간 순서로 계산한다(영업소 좌표는 `backend/app/data/toll_gates.json`, 요금은 한국도로공사 API를 조회해 DB에 캐시). 시내 도로만 달리면 통행료는 없는 게 정상이므로, 시연에서는 아래 구간을 쓴다.

준비(한 번): `backend/.env` 에 `MOATA_OPINET_KEY`, `MOATA_EXCO_KEY`가 있어야 하고, 데이터 파일이 없으면 `cd backend && .venv/Scripts/python ../scripts/fetch_toll_data.py`(영업소 좌표와 코드), `../scripts/fetch_car_efficiency.py`(표시연비)를 한 번 돌린다. 앱에 위치 권한을 준다: `adb shell pm grant --user 10 io.moata.pleos android.permission.ACCESS_FINE_LOCATION` (앱이 처음 켜질 때 요청 창도 뜬다).

```bash
python scripts/demo_geo_drive.py 동수원 서울 --park          # 1) 출발 전: 에뮬레이터 GPS 를 동수원 앞으로 옮겨 둔다(운행 시작 전에)
# 2) 앱에서 승객 승인 → 출발하기 (콘솔 주행거리 12500)
python scripts/demo_geo_drive.py 동수원 서울 --steps 60 --delay 1.5 --odo-start 12500 --driving   # 3) 주행: 실제 도로를 따라 GPS·주행거리(경로 실제 길이 약 14.9 km)가 움직이고 차량이 기어 Drive·속도 80 을 보고한다
# 4) 스크립트가 끝나면 기어 Park 로 돌아간다 → 앱에서 도착했어요 → 도착 요약에 통행료 카드 + 지나온 길 지도
```

`--driving` 이 있으면 주행 동안 앱이 실제 운행처럼 `주행 중 · 기능 제한` 화면(정적 모아)으로 바뀌고, 도착해 Park 가 되면 복원된다. 통행료 자동 반영은 주행 중에는 미루고 정차하면 반영된다. 콘솔 쓰기에는 하트비트(`scripts/console_heartbeat.sh`)가 필요하다. 속도 값은 시연용 표시일 뿐이다(GPS 는 실제 시간보다 빨리 재생된다). 이 옵션 없이도 돌아가지만 그러면 앱은 계속 정차 상태로 본다.

| 화면에 나오는 것 | 값 | 출처 |
|---|---|---|
| 통행료 | `동수원→서울` 1,900원 · 추정 (정산에 자동 반영) | 도로공사 영업소간 통행요금 API, 1종 |
| 연비 | 10.4 km/L · 표시연비 평균 추정 | 한국에너지공단 표시연비, 휘발유 승용차 2,092종 평균 |
| 유가 | 그날 오피넷 전국 평균 (2026-11-12: 1,855원/L) | 오피넷 |
| 정산 합계 예 | 주행거리 ÷ 10.4 × 유가 + 통행료 1,900원. 주행거리를 24 km 로 맞추려면 `--km 24` 를 붙인다: 24 ÷ 10.4 × 1,855 = 4,281원 + 1,900원 = **6,181원** | 유가는 매일 바뀌므로 합계도 달라진다 |

- 에뮬레이터 GPS 는 OSRM 공개 서버(OpenStreetMap 데이터, 시연용)에서 받은 **실제 도로 경로**를 따라 움직이므로 지도와 "이동 경로" 그림에 굽이가 보인다. 서버에 연결할 수 없거나 `--straight` 를 주면 두 영업소 사이 직선으로 움직인다. 시작·끝에 400 m 씩 접근·이탈 구간이 붙는다(`--lead-m`).
- 다른 구간을 쓰려면 `toll_gates.json` 에서 `unit_code` 가 있는 영업소 이름(예: 서울, 동수원, 수원신갈, 기흥, 오산, 판교)을 고른다. 이름 매칭률은 약 66% 라 코드가 없는 영업소는 요금을 못 구한다(카드가 나오지 않음). 한 번 지나면 정액인 개방식 영업소는 단독 통과로는 요금을 못 매긴다.
- 운행 중에는 `도착했어요`를 누르지 않는다(시연 중 에뮬레이터 창을 만지면 운행이 일찍 끝날 수 있다).
- 실시간 경로 안내는 Pleos OS 내비게이션 앱(`ai.umos.maps.android.navigation.app`)의 몫이라 이 앱에서는 만들지 않는다. 앱의 지도는 도착 후 회고용이며 서버에 저장되지 않는다.

### Windows 에서 실행할 때 (`run_demo.sh` 는 Mac/Linux 용)

```bash
cd backend && .venv/Scripts/python -m uvicorn app.main:app --host 127.0.0.1 --port 8000     # API (DB 스키마가 바뀌었으면 backend/moata.db 를 지우고 시작)
cd passenger-web && npm run dev                                                              # 승객 웹: http://localhost:3000 (127.0.0.1 로는 안 열린다)
```
`requirements.txt` 의 `uvloop` 는 Windows 에서 설치되지 않으므로 그 줄만 뺀 임시 목록으로 설치한다. 도로공사 API 는 User-Agent 없는 요청을 막는다(서버 코드는 붙여 둠).

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
