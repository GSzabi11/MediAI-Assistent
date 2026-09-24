import { useEffect, useRef } from 'react';
import { api } from '../api';
import type { Beszelo } from '../api/types';
import { formatMp } from '../lib/format';
import { useAsync } from '../lib/useAsync';
import { usePatient } from '../patient/PatientContext';
import { Icon, type IconName } from './Icon';
import { ErrorState, Loading, PatientMatch } from './shell';

const beszeloMeta: Record<Beszelo, { label: string; icon: IconName }> = {
  orvos: { label: 'Orvos', icon: 'stetho' },
  beteg: { label: 'Beteg', icon: 'user' },
  egyeb: { label: 'Hozzátartozó / egyéb', icon: 'user' },
};

/**
 * Hangfelvétel-átirat. A beszélő háromértékű: orvos / beteg / egyéb (hozzátartozó) —
 * mindhárom más buborékstílus + felirat. A hozzátartozó kijelentése nem a betegé
 * (anamnézis-hiba lenne összemosni). A kiemelés a turn-sorszámra (backend turnId) megy.
 */
export function TranscriptView({ felvetelId, kiemeltTurnIds }: { felvetelId: number; kiemeltTurnIds: number[] }) {
  const { patient, felvetelek } = usePatient();
  const felvetel = felvetelek.find((f) => f.id === felvetelId);
  const { state, reload } = useAsync(() => api.listMegszolalasok(felvetelId), [felvetelId]);
  const firstRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    firstRef.current?.scrollIntoView({ block: 'center', behavior: 'smooth' });
  }, [state.status, kiemeltTurnIds.join(',')]);

  if (!felvetel) {
    return (
      <div className="docview">
        <PatientMatch active={patient} contentPatientId={-1} what="Felvétel" />
      </div>
    );
  }

  const beszelok = new Set(state.status === 'ok' ? state.data.map((m) => m.beszelo) : []);

  return (
    <div className="docview">
      <div className="panel__head">
        <div className="row-baseline">
          <h2 className="h2 h2--sm">Felvétel #{felvetel.id}</h2>
          <span className="meta">
            {felvetel.mod === 'surgossegi' ? 'Sürgősségi felvétel' : 'Echokardiográfia diktálás'} ·{' '}
            {formatMp(felvetel.hosszMp)}
            {beszelok.size > 0 && ` · ${beszelok.size} beszélő`}
          </span>
        </div>
        <PatientMatch active={patient} contentPatientId={felvetel.patientId} what="Felvétel" />
        <div className="legend" aria-label="Beszélők jelmagyarázata">
          {(Object.keys(beszeloMeta) as Beszelo[]).map((b) => (
            <span key={b} className="legend__item">
              <span className={`legend__swatch bubble--${b}`} />
              {beszeloMeta[b].label}
            </span>
          ))}
        </div>
      </div>
      {state.status === 'loading' && <Loading what="Átirat" />}
      {state.status === 'error' && <ErrorState error={state.error} onRetry={reload} />}
      {state.status === 'ok' && felvetel.patientId === patient.id && (
        <ol className="transcript">
          {state.data
            .slice()
            .sort((a, b) => a.sorszam - b.sorszam)
            .map((m) => {
              const kiemelt = kiemeltTurnIds.includes(m.sorszam);
              const first =
                kiemelt && m.sorszam === kiemeltTurnIds.find((t) => state.data.some((x) => x.sorszam === t));
              const meta = beszeloMeta[m.beszelo];
              return (
                <li key={m.id} className={`turn turn--${m.beszelo}`}>
                  <div ref={first ? firstRef : undefined} className="turn__inner">
                    <span className="turn__who">
                      <Icon name={meta.icon} size={14} />
                      {meta.label}
                      <span className="mono turn__time">
                        #{String(m.sorszam).padStart(2, '0')} · {formatMp(m.kezdetMp)}
                      </span>
                      {kiemelt && <span className="turn__ref">hivatkozott megszólalás</span>}
                    </span>
                    <p className={`bubble bubble--${m.beszelo}${kiemelt ? ' bubble--ref' : ''}`}>{m.szoveg}</p>
                  </div>
                </li>
              );
            })}
        </ol>
      )}
    </div>
  );
}
