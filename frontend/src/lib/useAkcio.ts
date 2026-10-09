import { useCallback, useState } from 'react';

/**
 * Egy felhasználói művelet (mentés, lezárás, import) állapota: fut-e, és ha elbukott,
 * a backend hibaüzenete. `run` hibánál `undefined`-ot ad vissza, és a hibát kiírja —
 * így minden gomb ugyanúgy viselkedik, és dupla kattintás sem indít két kérést.
 */
export function useAkcio() {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const run = useCallback(async <T>(fn: () => Promise<T>): Promise<T | undefined> => {
    setBusy(true);
    setError(null);
    try {
      return await fn();
    } catch (e) {
      setError((e as Error).message);
      return undefined;
    } finally {
      setBusy(false);
    }
  }, []);

  return { busy, error, setError, run };
}
