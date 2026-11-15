import { tmpdir } from "node:os";
import { join } from "node:path";
import { defineConfig, devices } from "@playwright/test";



const API_PORT = 8010;
const WEB_PORT = 3100;
const external = process.env.E2E_EXTERNAL === "1";
const API = process.env.E2E_API_BASE ?? `http://127.0.0.1:${API_PORT}/api/v1`;
const WEB = process.env.E2E_WEB_BASE ?? `http://127.0.0.1:${WEB_PORT}`;
const DB = join(tmpdir(), "moata-e2e.db");

export default defineConfig({
  testDir: "./e2e",
  timeout: 90_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [["list"]],
  use: { baseURL: WEB, trace: "retain-on-failure", locale: "ko-KR", timezoneId: "Asia/Seoul", ...devices["Pixel 7"] },
  webServer: external ? undefined : [
    {
      command: `rm -f "${DB}" && .venv/bin/uvicorn app.main:app --host 127.0.0.1 --port ${API_PORT}`,
      cwd: "../backend",
      env: { MOATA_DATABASE_URL: `sqlite:///${DB}`, MOATA_CORS_ORIGINS: WEB, PYTHONDONTWRITEBYTECODE: "1" },
      url: `http://127.0.0.1:${API_PORT}/api/v1/health`,
      reuseExistingServer: false,
      timeout: 60_000,
    },
    {
      command: `npm run build && npx next start -H 127.0.0.1 -p ${WEB_PORT}`,

      env: { NEXT_PUBLIC_API_BASE: API, NEXT_DIST_DIR: ".next-e2e" },
      url: WEB,
      reuseExistingServer: false,
      timeout: 180_000,
    },
  ],
});
