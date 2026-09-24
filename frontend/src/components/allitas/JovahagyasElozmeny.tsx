import { api } from '../../api';
import type { Dontes } from '../../api/types';
import { formatDatumIdo } from '../../lib/format';
import { useAsync } from '../../lib/useAsync';
import { ErrorState, Loading } from '../shell';

const felirat: Record<Dontes, string> = {
  jovahagyva: 'jóváhagyta',
  modositva: 'módosította',
  elutasitva: 'elutasította',
};

/**
 * GET /api/statements/{id}/approvals — a döntések teljes előzménye, legújabb elöl.
 * Az első sor az érvényes (a backend konvenciója: a legfrissebb döntés számít).
 */
export function JovahagyasElozmeny({ allitasId }: { allitasId: number }) {
  const { state, reload } = useAsync(() => api.listJovahagyasok(allitasId), [allitasId]);
  if (state.status === 'loading') return <Loading what="Előzmény" />;
  if (state.status === 'error') return <ErrorState error={state.error} onRetry={reload} />;
  if (state.data.length === 0) return <p className="meta history">Még nem született döntés.</p>;
  return (
    <ol className="history" aria-label="Döntési előzmény, legújabb elöl">
      {state.data.map((d, i) => (
        <li key={d.id} className={i === 0 ? 'history__current' : undefined}>
          <span className="mono">{formatDatumIdo(d.idopont)}</span> · <strong>{d.felhasznaloNev}</strong>{' '}
          {felirat[d.dontes]}
          {i === 0 && <span className="meta"> — érvényes</span>}
          {d.modositottSzoveg && <span className="history__text">„{d.modositottSzoveg}”</span>}
        </li>
      ))}
    </ol>
  );
}
