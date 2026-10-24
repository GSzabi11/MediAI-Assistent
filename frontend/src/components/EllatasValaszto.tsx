import { useEffect, useState } from 'react';
import type { EllatasTipus } from '../api/types';
import { formatDatumIdo } from '../lib/format';
import { useAkcio } from '../lib/useAkcio';
import { usePatient } from '../patient/PatientContext';
import { utolsoEllatas, utolsoTipus, type EllatasKapu, type Kontextus } from '../patient/EllatasKontextus';
import { FelhasznaloNev } from './FelhasznaloNev';
import { Icon } from './Icon';
import { ErrorState, Loading } from './shell';

export const ellatasRovid: Record<EllatasTipus, string> = { sbo: 'SBO', ambulans: 'AMBULÁNS', echo: 'ECHO' };
const ellatasHosszu: Record<EllatasTipus, string> = {
  sbo: 'Sürgősségi (SBO)',
  ambulans: 'Ambuláns',
  echo: 'Echokardiográfia',
};
const TIPUSOK: EllatasTipus[] = ['sbo', 'ambulans', 'echo'];

/** „nyitva 2 ó” / „nyitva 35 perce” — a kezdet óta eltelt idő */
export function nyitvaIdo(kezdet: string, most = Date.now()) {
  const perc = Math.max(0, Math.floor((most - new Date(kezdet).getTime()) / 60_000));
  if (perc < 60) return `nyitva ${perc} perce`;
  const ora = Math.floor(perc / 60);
  return ora < 48 ? `nyitva ${ora} ó` : `nyitva ${Math.floor(ora / 24)} napja`;
}

/**
 * A beteg kiválasztása után: melyik nyitott ellátásban dolgozik az orvos, vagy nyit újat (egy kattintás),
 * vagy csak informálódik (csak olvasás). Több nyitott ellátás is lehet, ezért a választás mindig explicit;
 * az utoljára használt nyitott ellátás előválasztva.
 */
export function EllatasValaszto({ kapu }: { kapu: EllatasKapu }) {
  const { patient } = usePatient();
  const { state, reload } = kapu.ellatasok;
  const nyitas = useAkcio();
  const [tipus] = useState(utolsoTipus);
  const nyitottak =
    state.status === 'ok' ? state.data.filter((e) => e.veg === null && e.patientId === patient.id) : [];
  const utolso = utolsoEllatas(patient.id);
  const elovalasztott = nyitottak.find((e) => e.id === utolso)?.id ?? nyitottak[0]?.id;

  return (
    <main className="ctxchooser" aria-label="Ellátás kiválasztása">
      <div className="ctxchooser__card">
        <h2 className="h2">Milyen minőségben nyitod meg a beteget?</h2>
        <p className="meta">
          Az ellátás a munkád kerete: a hangfelvétel és az epikrízis ehhez kötődik. Informálódás módban minden
          olvasható, de semmi nem módosítható.
        </p>
        {kapu.uzenet && (
          <p className="form-error" role="alert">
            {kapu.uzenet}
          </p>
        )}

        <section aria-label="Nyitott ellátások">
          <h3 className="label">Nyitott ellátások</h3>
          {state.status === 'loading' && <Loading what="Ellátások" />}
          {state.status === 'error' && <ErrorState error={state.error} onRetry={reload} />}
          {state.status === 'ok' && nyitottak.length === 0 && (
            <p className="meta">Ennek a betegnek nincs nyitott ellátása.</p>
          )}
          <ul className="ctxchooser__list">
            {nyitottak.map((e) => (
              <li key={e.id}>
                <button
                  type="button"
                  className={`ctxchooser__option${e.id === elovalasztott ? ' ctxchooser__option--pre' : ''}`}
                  // az előválasztott ellátás fókuszt kap: Enterrel folytatható
                  autoFocus={e.id === elovalasztott}
                  onClick={() => kapu.valaszt({ ellatasId: e.id })}
                >
                  <Icon name="stetho" size={18} />
                  <span className="ctxchooser__text">
                    <strong>
                      #{e.id} · {ellatasHosszu[e.tipus]}
                    </strong>
                    <span className="meta">
                      {formatDatumIdo(e.kezdet)} · {nyitvaIdo(e.kezdet)} · <FelhasznaloNev id={e.orvosId} />
                      {e.id === utolso && ' · utoljára használt'}
                    </span>
                  </span>
                  <span className="push-right ctxchooser__go">Folytatás</span>
                </button>
              </li>
            ))}
          </ul>
        </section>

        <section aria-label="Új ellátás">
          <h3 className="label">Új ellátás nyitása</h3>
          <div className="actions">
            {TIPUSOK.map((t) => (
              <button
                key={t}
                type="button"
                className={`btn${t === tipus ? ' btn--primary' : ''}`}
                disabled={nyitas.busy}
                onClick={() => void nyitas.run(() => kapu.ujEllatas(t))}
              >
                <Icon name="plus" size={16} strokeWidth={2.4} />
                Új {ellatasRovid[t]}-ellátás
              </button>
            ))}
          </div>
          {nyitas.error && (
            <p className="form-error" role="alert">
              {nyitas.error}
            </p>
          )}
        </section>

        <section aria-label="Informálódás">
          <h3 className="label">Csak olvasás</h3>
          <button type="button" className="btn" onClick={() => kapu.valaszt('informalodas')}>
            <Icon name="info" size={16} />
            Informálódás (csak olvasás)
          </button>
        </section>
      </div>
    </main>
  );
}

/** A fejléc chipje: az aktív kontextus, kattintásra vissza a választóhoz. */
export function KontextusChip({
  betegNev,
  kontextus,
  onValtas,
}: {
  betegNev: string;
  kontextus: Kontextus | null;
  onValtas: () => void;
}) {
  const [most, setMost] = useState(() => Date.now());
  useEffect(() => {
    const t = setInterval(() => setMost(Date.now()), 60_000);
    return () => clearInterval(t);
  }, []);
  if (!kontextus) return null;
  const felirat =
    kontextus.mod === 'informalodas'
      ? 'Informálódás · csak olvasás'
      : `${betegNev} · ${ellatasRovid[kontextus.ellatas.tipus]} · ${nyitvaIdo(kontextus.ellatas.kezdet, most)}`;
  return (
    <div className="pband__field">
      <span className="pband__label">KONTEXTUS</span>
      <button
        type="button"
        className={`ctxchip${kontextus.mod === 'informalodas' ? ' ctxchip--ro' : ''}`}
        onClick={onValtas}
        title="Ellátás vagy mód váltása"
      >
        <Icon name={kontextus.mod === 'informalodas' ? 'info' : 'stetho'} size={16} />
        {felirat}
        <Icon name="chevronDown" size={14} />
      </button>
    </div>
  );
}

/** Informálódás módban a munkaterület fölött: halvány „Csak olvasás” sáv + vissza az ellátás-választóhoz */
export function CsakOlvasasSav({ onMegnyitas }: { onMegnyitas: () => void }) {
  return (
    <div className="robanner" role="note">
      <Icon name="lock" size={14} />
      <span>
        <strong>Csak olvasás</strong> — Informálódás mód: a felvétel és a módosítás le van tiltva (dokumentum feltölthető — ideiglenes, amíg nincs EESZT-import).
      </span>
      <button type="button" className="btn btn--sm push-right" onClick={onMegnyitas}>
        Megnyitás ellátásban
      </button>
    </div>
  );
}
