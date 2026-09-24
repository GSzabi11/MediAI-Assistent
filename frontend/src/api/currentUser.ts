import { useSyncExternalStore } from 'react';

/**
 * A bejelentkezett felhasználó. A backend még nem ad munkamenetet vagy tokent:
 * a belépés csak a jelszót ellenőrzi, utána a felhasználó azonosítója megy
 * minden kérés `X-User-Id` fejlécében (hozzáférési napló) és a jóváhagyások
 * `userId`-jában. A Security-fázisban ezt valódi munkamenet váltja ki.
 */
export type Szerepkor = 'DOCTOR' | 'ADMIN';

export interface CurrentUser {
  id: number;
  username: string;
  role: Szerepkor;
}

const KEY = 'mediai.currentUser';
const listeners = new Set<() => void>();

function read(): CurrentUser | null {
  try {
    const raw = localStorage.getItem(KEY);
    const u = raw ? (JSON.parse(raw) as Partial<CurrentUser>) : null;
    // Régebbi (szerepkör nélküli) tárolt érték: jelentkezzen be újra
    return u && typeof u.id === 'number' && u.username && u.role ? (u as CurrentUser) : null;
  } catch {
    return null;
  }
}

let current: CurrentUser | null = read();

export function getCurrentUser(): CurrentUser | null {
  return current;
}

export function setCurrentUser(user: CurrentUser | null) {
  current = user;
  try {
    if (user) localStorage.setItem(KEY, JSON.stringify(user));
    else localStorage.removeItem(KEY);
  } catch {
    /* privát mód: csak a munkamenet idejére marad meg */
  }
  listeners.forEach((l) => l());
}

export function useCurrentUser(): CurrentUser | null {
  return useSyncExternalStore(
    (l) => {
      listeners.add(l);
      return () => listeners.delete(l);
    },
    () => current,
  );
}

export const szerepkorFelirat: Record<Szerepkor, string> = { DOCTOR: 'Orvos', ADMIN: 'Admin' };
