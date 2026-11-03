import type { NextConfig } from "next";

const isProd = process.env.NODE_ENV === "production";

// connect-src is pinned to NEXT_PUBLIC_API_BASE when set at build time. Otherwise the client derives
// <host>:8000 at runtime (LAN demo), so any host on :8000 is allowed.
function apiOrigin(): string | null {
  const base = process.env.NEXT_PUBLIC_API_BASE;
  if (!base) return null;
  try {
    return new URL(base).origin;
  } catch {
    return null;
  }
}

const connectSrc = ["'self'", apiOrigin() ?? "http://*:8000 https://*:8000"].join(" ");

const csp = [
  "default-src 'self'",
  "base-uri 'self'",
  "form-action 'self'",
  "frame-ancestors 'none'",
  "object-src 'none'",
  "img-src 'self' data:",
  "font-src 'self'",
  "style-src 'self' 'unsafe-inline'",
  "script-src 'self' 'unsafe-inline'", // Next.js hydration bootstrap is inline; there are no third-party scripts
  `connect-src ${connectSrc}`,
].join("; ");

const config: NextConfig = {
  distDir: process.env.NEXT_DIST_DIR ?? ".next", // e2e builds into .next-e2e so its baked API base never reaches the demo build
  poweredByHeader: false,
  reactStrictMode: true,
  allowedDevOrigins: (process.env.ALLOWED_DEV_ORIGINS ?? "").split(",").map((s) => s.trim()).filter(Boolean),
  async headers() {
    return [
      {
        source: "/:path*",
        headers: [
          { key: "X-Content-Type-Options", value: "nosniff" },
          { key: "X-Frame-Options", value: "DENY" },
          { key: "Referrer-Policy", value: "no-referrer" },
          { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=(), payment=()" },
          ...(isProd ? [{ key: "Content-Security-Policy", value: csp }] : []),
        ],
      },
    ];
  },
};

export default config;
