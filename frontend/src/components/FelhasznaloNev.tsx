import { api } from '../api';
import { useAsync } from '../lib/useAsync';

/** Felhasználó neve azonosító alapján (GET /users/{id}, gyorsítótárazva). */
export function FelhasznaloNev({ id }: { id: number | null }) {
  const { state } = useAsync(() => (id === null ? Promise.resolve(null) : api.felhasznaloNev(id)), [id]);
  if (id === null) return <span className="meta">ismeretlen</span>;
  if (state.status !== 'ok' || state.data === null) return <span className="meta">#{id}</span>;
  return <>{state.data}</>;
}
