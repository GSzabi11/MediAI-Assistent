import { getCurrentUser } from './currentUser';
import type { ProblemDetailDto } from './dto';
import { ApiError } from './types';

/** A Spring Boot backend alap-URL-je. Dev módban a Vite proxy /api-t továbbítja. */
const BASE = import.meta.env.VITE_API_BASE ?? '/api';

type Body = { json: unknown } | { form: FormData } | undefined;

async function request(method: string, path: string, body?: Body): Promise<Response> {
  const headers: Record<string, string> = {};
  // multipart-nál a böngésző állítja a Content-Type-ot (a boundary miatt), ezért ott nem
  if (body && 'json' in body) headers['Content-Type'] = 'application/json';
  // Hozzáférési napló: ki a hívó. Ideiglenes, a Security-fázisban auth váltja ki.
  const user = getCurrentUser();
  if (user) headers['X-User-Id'] = String(user.id);

  const res = await fetch(`${BASE}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : 'json' in body ? JSON.stringify(body.json) : body.form,
  });
  if (!res.ok) throw new ApiError(res.status, await errorMessage(res));
  return res;
}

/** A backend minden hibát ProblemDetail-ként ad (`detail`); ha mégsem, a nyers szöveg. */
async function errorMessage(res: Response): Promise<string> {
  const text = await res.text().catch(() => '');
  try {
    const pd = JSON.parse(text) as ProblemDetailDto;
    if (pd.detail) return pd.detail;
  } catch {
    /* nem JSON */
  }
  if (res.status === 502 || res.status === 503 || res.status === 504) {
    return 'A backend nem érhető el — fut a Spring Boot alkalmazás?';
  }
  return text || `${res.status} ${res.statusText}`;
}

export const http = {
  get: async <T>(path: string) => (await (await request('GET', path)).json()) as T,
  getText: async (path: string) => (await request('GET', path)).text(),
  /** Fájl letöltése a fejlécekkel együtt (X-User-Id → a naplóban látszik, ki töltötte le) */
  getBlob: async (path: string) => (await request('GET', path)).blob(),
  post: async <T>(path: string, json?: unknown) =>
    (await (await request('POST', path, json === undefined ? undefined : { json })).json()) as T,
  postForm: async <T>(path: string, form: FormData) => (await (await request('POST', path, { form })).json()) as T,
  put: async <T>(path: string, json: unknown) => (await (await request('PUT', path, { json })).json()) as T,
  patch: async <T>(path: string, json: unknown) => (await (await request('PATCH', path, { json })).json()) as T,
};
