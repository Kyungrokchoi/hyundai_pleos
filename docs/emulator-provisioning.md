# Pleos Connect 에뮬레이터 프로비저닝 기록

사용자 승인(2026-11-02): *CLI로 설치, 42dot SDK 라이선스 동의 확인, 새 AVD만 추가*. 기존 AVD `Qure_API36` 및 그 이미지는 건드리지 않았다.

## 공식 요구사항 대조

| 항목 | 공식 문서(setup-connect-sdk-emulator) | 이 머신에서 확인한 값 |
|---|---|---|
| minSdk | API 28 이상 | 앱 `minSdk=28` (car-app) |
| Gradle | 8.0 이상 | 9.4.1 (캐시된 배포판) |
| Pleos Connect Emulator | 2.1.0 | 이미지 `ro.build.version.incremental=release_connect_v2.1.0` ✔ |
| Android API | 34, Automotive | `ro.build.version.sdk=34`, 릴리스 14, tag `android-automotive` ✔ |
| RAM / Boot | 4 GB / Cold Boot | `hw.ramSize=4096`, `fastboot.forceColdBoot=yes`, `-no-snapshot-load` ✔ |
| 에뮬레이터 바이너리 | 시스템 이미지가 요구하는 `emulator` 리비전 ≥ 24 | 설치된 `emulator` 37.1.11 ✔ |

## 설치 절차 (실제 실행한 것)

1. Apple Silicon이므로 Mac ARM64 업데이트 사이트 XML을 사용: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml`
   - 패키지 경로 `system-images;android-34;pleos_car_emulator;arm64-v8a`, 아카이브 `crp_car_arm64_emulator.zip` (4,669,762,816 B).
2. **`sdkmanager`(cmdline-tools 22.0)는 `~/.android/sites-settings.cfg`에 등록한 사용자 사이트를 목록에 올리지 않았다**(키 이름 `src0`로 수정해도 동일). 공식 문서에도 CLI 절차가 없다. 그래서 Android Studio가 하는 일과 같은 작업을 수동으로 수행했다:
   - `curl -C -` 로 아카이브 다운로드 → **SHA-1 `24d28eb2f18eba5e87c861a7eb2bdccebb049419` 가 XML 값과 일치** 확인.
   - `unzip` → `$ANDROID_SDK_ROOT/system-images/android-34/pleos_car_emulator/arm64-v8a/` (zip 최상위 `crp_avd_out_arm64-v8a/` 를 `arm64-v8a/`로 이름 변경).
   - 아카이브에 `package.xml`이 없어 직접 만든 버전은 스키마 검증에 실패(“corrupted”)하여 제거했다. 레거시 `source.properties`만으로 `sdkmanager --list_installed` 와 `avdmanager` 가 이미지를 인식한다(패키지 ID는 `system-images;android-34;android-automotive;arm64-v8a` 로 보이고 위치 불일치 경고가 뜨지만 동작에는 영향 없음).
3. 라이선스 파일(`$SDK/licenses/pleos-connect-sdk-license`)은 **만들지 않았다**. 동의는 사용자가 별도로 확인했으며, Android Studio에서 이 패키지를 갱신할 때는 Studio가 다시 동의를 요청할 수 있다.
4. AVD 생성: `avdmanager create avd -n MOATA_Pleos_API34 -k "system-images;android-34;android-automotive;arm64-v8a" -d Pleos_Connect_v2_0` (장치 정의 `Pleos_Connect_v2_0`은 이미지의 `devices.xml`에서 제공: 2560×1440, 12.8", 160dpi). 이후 `~/.android/avd/MOATA_Pleos_API34.avd/config.ini` 에서 `hw.ramSize=4096`, `fastboot.forceColdBoot=yes`, `fastboot.forceFastBoot=no`, `hw.gpu.enabled=yes`, `hw.gpu.mode=host`, `hw.initialOrientation=landscape`, `hw.keyboard=yes` 로 수정(원본 백업은 임시 작업 폴더).
5. 변경한 전역 항목: `~/.android/sites-settings.cfg`(새 파일이었으나 효과가 없어 **작업 종료 시 삭제함**), `~/.android/avd/MOATA_Pleos_API34.*`, SDK의 `system-images/android-34/`.

**디스크 사용량:** 다운로드 4.67 GB, 압축 해제 후 약 **19 GB** (`system.img` 18.3 GB). 필요 없으면 `rm -rf $ANDROID_SDK_ROOT/system-images/android-34 ~/.android/avd/MOATA_Pleos_API34.*`.

## 부팅 / 확인 명령

```bash
export ANDROID_SDK_ROOT=/opt/homebrew/share/android-commandlinetools
$ANDROID_SDK_ROOT/emulator/emulator -avd MOATA_Pleos_API34 -no-snapshot-load -no-snapshot-save \
  -no-boot-anim -no-audio -gpu host -port 5554          # 창을 보려면 -no-window 제거
adb -s emulator-5554 shell getprop ro.build.version.incremental   # release_connect_v2.1.0
adb -s emulator-5554 shell dumpsys package ai.pleos.playground.service.vehicle | grep versionName   # 2.3.22
adb forward tcp:8088 tcp:8088                                      # Virtual Console
```

## 실제 에뮬레이터에서 확인된 사실 (VERIFIED, 앱 코드 실행 전)

| 확인 | 결과 |
|---|---|
| `adb devices` | `emulator-5554 device` (AND-01 ✔) |
| 차량 서비스 패키지 | `ai.pleos.playground.service.vehicle` versionName **2.3.22** (versionCode 74). SDK 2.3.2 AAR가 요구하는 최소 `2.3.13` 충족 |
| 권한 정의 | `pleos.car.permission.CAR_DRIVE`, `pleos.car.permission.CAR_ENERGY` 모두 `protectionLevel:normal`, `ai.pleos.playground.service.vehicle.VEHICLE_BINDING` 도 `normal` — SDK KDoc(“protection level: normal”)과 일치 |
| Virtual Console | `http://127.0.0.1:8088/` “Pleos Connect Virtual Console” 응답 200 |

## Virtual Console 사용 시 주의 (문서에 없던 동작)

- **브라우저 세션 하트비트가 있어야 차량 브리지가 활성화된다.** 콘솔 페이지는 1초마다 `POST /browser/session?client=<id>` 를 보낸다. 하트비트가 없으면 모든 쓰기가 `HTTP 503 {"error":"emulator_inactive"}` 이고 `/vehicle/state` 의 `emulator_active/bridge_ready` 는 `false` 다. 브라우저 탭을 열어 두거나 `scripts/console_heartbeat.sh` 를 실행한다.
- 하트비트 후 `emulator_active=true, bridge_ready=true` 가 되었고, `POST /vehicle/odometer?traveled_distance_km=12345` 가 HTTP 200으로 반영됨(`source:"web"`). 소수 입력도 받지만 **float32로 저장**된다(12345.6 → 12345.599609).
- 콘솔 카탈로그(63개 항목)에는 **연비(Fuel economy)·전비(EV efficiency) 항목이 없다.** 있는 에너지 항목은 EV 배터리 수준/SoC/용량/충전 한도/남은 주행거리뿐이다. 따라서 `Fuel.getCurrentFuelEconomy`, `EvBattery.getCurrentEvEfficiency` 가 에뮬레이터에서 값을 돌려줄지는 **앱 프로브로 확인해야 한다**(콘솔로는 값을 만들 수 없음). 확인 전에는 NOT_RUN.
- 콘솔 값을 바꾼 것과 앱이 SDK로 읽은 것은 별개로 기록한다(PRD §4.2).
