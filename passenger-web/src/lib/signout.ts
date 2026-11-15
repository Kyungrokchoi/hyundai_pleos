import { api } from "@/api/client";
import { clearSession } from "./session";


export async function signOut(token: string | null | undefined): Promise<void> {
  try {
    if (token) await api.logout(token);
  } catch {

  } finally {
    clearSession();
  }
}
