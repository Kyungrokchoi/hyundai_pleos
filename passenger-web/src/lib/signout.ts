import { api } from "@/api/client";
import { clearSession } from "./session";

// Revoke server-side so a copied token stops working. Best effort: offline users can still sign out locally.
export async function signOut(token: string | null | undefined): Promise<void> {
  try {
    if (token) await api.logout(token);
  } catch {
    // offline, or the token is already revoked
  } finally {
    clearSession();
  }
}
