// Invite tokens travel in the URL fragment (`/join#<token>`) so they never reach server or proxy logs.
// `#t=<token>` is tolerated but never produced by the app.
const TOKEN_RE = /^[A-Za-z0-9_-]{16,128}$/;

export function parseTokenFragment(hash: string): string | null {
  let h = hash.startsWith("#") ? hash.slice(1) : hash;
  if (h.startsWith("t=")) h = h.slice(2);
  try { h = decodeURIComponent(h); } catch { return null; }
  return TOKEN_RE.test(h) ? h : null;
}
