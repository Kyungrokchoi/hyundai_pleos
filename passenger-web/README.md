# MOATA 승객 웹

모바일 우선 한국어 Next.js(App Router, TypeScript strict) 앱. 설계·보안·검증 증거는 [`../docs/passenger-web.md`](../docs/passenger-web.md), 토큰은 [`../docs/design-tokens.md`](../docs/design-tokens.md).

## 실행

```bash
cd passenger-web
npm ci
cp .env.example .env.local        # 선택. NEXT_PUBLIC_API_BASE 를 비워 두면 <현재 호스트>:8000/api/v1 을 씁니다
npm run build && npm run start    # http://0.0.0.0:3000  (시연 권장: 프로덕션 모드)
npm run dev                       # 개발. 휴대폰에서 열면 ALLOWED_DEV_ORIGINS=<PC LAN IP> 필요할 수 있음
```

백엔드는 별도로 실행합니다(`backend/`). **휴대폰으로 열려면** 백엔드에 `MOATA_CORS_ORIGINS=http://<PC-LAN-IP>:3000` 를 추가하세요(기본값은 localhost 뿐). 초대 QR 주소는 `http://<PC-LAN-IP>:3000/join#<token>` 형태이며 토큰은 `#` 뒤에 둡니다.

## 점검 명령

| 목적 | 명령 |
|---|---|
| 린트 | `npm run lint` |
| 타입체크(앱+e2e) | `npm run typecheck` |
| 단위 테스트 | `npm test` |
| 빌드 | `npm run build` (다른 빌드와 섞이지 않게 `NEXT_DIST_DIR=.next-dev npm run build` 처럼 출력 폴더를 바꿀 수 있음. e2e 는 `.next-e2e` 사용, 데모 서버는 `.next`) |
| API 타입 재생성 | `npm run gen:api` (`../contracts/openapi.yaml` → `src/api/schema.d.ts`) |
| 브라우저 e2e | `npm run e2e` |

### e2e

`npm run e2e` 는 Playwright가 **백엔드(uvicorn :8010, 임시 SQLite)와 프로덕션 빌드(:3100)를 직접 띄우고 종료**합니다. 개발 중인 :8000 백엔드/DB는 건드리지 않습니다. 필요한 것: `backend/.venv` 가 만들어져 있을 것, Chromium(`npx playwright install chromium`, 이미 캐시에 있으면 생략).
이미 띄워 둔 서버를 쓰려면 `E2E_EXTERNAL=1 E2E_API_BASE=... E2E_WEB_BASE=... npm run e2e` (웹 빌드에 같은 `NEXT_PUBLIC_API_BASE` 가 들어 있어야 하고 백엔드 CORS 에 웹 출처가 있어야 함).
화면 검토용 스크린샷: `E2E_SHOTS=1 E2E_SHOTS_DIR=/tmp/shots npx playwright test e2e/screens.spec.ts`.

## 구조

```
src/app/        라우트: /, /join, /trip/[id], /trip/[id]/bill, /group/[id], 404, error, globals.css(토큰)
src/api/        client.ts(유일한 fetch), types.ts(생성 타입 별칭), schema.d.ts(생성물)
src/components/ Moa(SVG 마스코트), Frame, Notice, SourceBadge/DemoBar, StateViews, icons
src/lib/        format, fragment, session/useSession, usePolling(ETag), useLoad, errors, labels, share, membership, signout …
tests/          Vitest 단위 테스트   e2e/  Playwright (driver.ts = 차량 앱 역할을 하는 API 클라이언트)
```

의존성은 런타임 `next`·`react`·`react-dom`·`pretendard`(폰트, OFL-1.1) 뿐입니다. 개발 의존성의 `@axe-core/playwright` 는 MPL-2.0(테스트 전용, 배포물에 포함되지 않음).
