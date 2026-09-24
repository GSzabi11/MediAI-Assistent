import { getCurrentUser } from './currentUser';
import type { ProblemDetailDto } from './dto';
import { ApiError } from './types';

/** A Spring Boot backend alap-URL-je. Dev módban a Vite proxy /api-t továbbítja. */
const BASE = import.meta.env.VITE_API_BASE ?? '/api';

async function request(method: string, path: string, body?: unknown): Promise<Response> {
  const headers: Record<string, string> = {};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  // Hozzáférési napló: ki a hívó. Ideiglenes, a Security-fázisban auth váltja ki.
  const user = getCurrentUser();
  if (user) headers['X-User-Id'] = String(user.id);

  const res = await fetch(`${BASE}${path}`, {
    method,
    headers,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  if (!res.ok) throw new ApiError(res.status, await errorMessage(res));
  return res;
}

/** A backend saját kivételei ProblemDetail-t adnak (`detail`), a validációs hibák nem. */
async function errorMessage(res: Response): Promise<string> {
  const text = await res.text().catch(() => '');
  try {
    const pd = JSON.parse(text) as ProblemDetailDto;
    if (pd.detail) return pd.detail;
    if (res.status === 400) return 'Hibás kérés — a backend elutasította a megadott adatokat.';
  } catch {
    /* nem JSON */
  }
  return text || `${res.status} ${res.statusText}`;
}

export const http = {
  get: async <T>(path: string) => (await (await request('GET', path)).json()) as T,
  getText: async (path: string) => (await request('GET', path)).text(),
  post: async <T>(path: string, body: unknown) => (await (await request('POST', path, body)).json()) as T,
};
