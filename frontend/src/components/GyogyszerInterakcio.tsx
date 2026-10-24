import { useId, useState, type FormEvent, type ReactNode } from 'react';
import { interakcioApi, type EllenorzesEredmeny, type Feloldas } from '../api/gyogyszerInterakcio';
import type { SeverityDto } from '../api/dto';
import type { ClaimResponse } from '../api/types';
import { formatDatum } from '../lib/format';
import { useAkcio } from '../lib/useAkcio';
import { useAsync } from '../lib/useAsync';
import { useIrhato } from '../patient/EllatasKontextus';
import { usePatient } from '../patient/PatientContext';
import { Icon } from './Icon';
import { ErrorState, Loading } from './shell';

/**
 * Gyógyszer-interakció (docs/drug-interaction.md). Determinisztikus szabály, nem AI: a szedett
 * gyógyszerek közötti interakció állításként jön (a jobb oldali listában jóváhagyható); itt a
 * forrásadat állapota, a nem ellenőrizhető gyógyszerek, és az ad hoc ellenőrzés látszik.
 */
export function GyogyszerInterakcio({ frissitesKulcs }: { frissitesKulcs: number }) {
  const { patient, allitasok } = usePatient();
  const riasztasok = allitasok.filter((a) => a.forrasok.some((f) => f.tipus === 'gyogyszer'));
  const lefedettseg = useAsync(() => interakcioApi.lefedettseg(patient.id), [patient.id, frissitesKulcs]);

  return (
    <section className="subsection" aria-label="Gyógyszer-interakciók">
      <div className="row-baseline">
        <h2 className="h2 h2--sm">Interakciók</h2>
        <span className="meta">szabály-alapú · forrás: DDInter 2.0 + NEAK PUPHA</span>
      </div>
      <ReferenciaAllapotSor />

      {riasztasok.length === 0 ? (
        <p className="meta">A szedett gyógyszerek között nincs ismert interakció.</p>
      ) : (
        <TajekoztatoLenyitva
          elemek={riasztasok.slice().sort((a, b) => rang(a) - rang(b))}
          tajekoztato={(a) => a.sulyossag === 'info'}
          render={(a) => <RiasztasSor key={a.id} a={a} />}
        />
      )}

      {lefedettseg.state.status === 'error' && (
        <ErrorState error={lefedettseg.state.error} onRetry={lefedettseg.reload} />
      )}
      {lefedettseg.state.status === 'ok' && (
        <NemEllenorizheto
          sorok={lefedettseg.state.data.medications
            .filter((m) => m.resolution.status !== 'RESOLVED')
            .map((m) => ({ nev: m.name, feloldas: m.resolution }))}
        />
      )}

      <AdHocEllenorzes />
    </section>
  );
}

/**
 * A tájékoztató szintű találatok (enyhe, ill. a DDInterben besorolatlan „Unknown” párok) lenyitva:
 * sokan vannak, és elfednék a kritikus / figyelmeztető riasztásokat.
 */
function TajekoztatoLenyitva<T>({
  elemek,
  tajekoztato,
  render,
}: {
  elemek: T[];
  tajekoztato: (x: T) => boolean;
  render: (x: T) => ReactNode;
}) {
  const fontos = elemek.filter((x) => !tajekoztato(x));
  const info = elemek.filter(tajekoztato);
  return (
    <>
      {fontos.length > 0 ? (
        <ul className="itemlist">{fontos.map(render)}</ul>
      ) : (
        <p className="meta">Nincs kritikus vagy figyelmeztető interakció.</p>
      )}
      {info.length > 0 && (
        <details>
          <summary className="meta">
            További {info.length} tájékoztató (enyhe vagy besorolatlan súlyosságú) interakció
          </summary>
          <ul className="itemlist">{info.map(render)}</ul>
        </details>
      )}
    </>
  );
}

const rang = (a: ClaimResponse) => ({ critical: 0, warning: 1, info: 2 })[a.sulyossag];

function RiasztasSor({ a }: { a: ClaimResponse }) {
  const dontes = a.jovahagyas?.dontes;
  // az orvos által elutasított interakció nem kap színt (szürke)
  const tonus = dontes === 'elutasitva' ? ' item--off' : ` sevtone--${a.sulyossag}`;
  return (
    <li className={`item${tonus}`}>
      <div className="item__row">
        <Icon name={a.sulyossag === 'info' ? 'pill' : 'alert'} size={16} strokeWidth={2.4} />
        <SulyossagCimke s={a.sulyossag} />
        <span>{a.ervenyesSzoveg || a.idezet || a.cim}</span>
      </div>
      <div className="item__sub">
        <span className="meta">
          szabály-állítás
          {dontes === 'jovahagyva' && ' · orvos elfogadta'}
          {dontes === 'elutasitva' && ' · orvos elutasította'}
        </span>
      </div>
    </li>
  );
}

type Sulyossag = ClaimResponse['sulyossag'];
const sulyossagCimke: Record<Sulyossag, string> = { critical: 'Súlyos', warning: 'Közepes', info: 'Enyhe / tájékoztató' };
const dtoSulyossag: Record<SeverityDto, Sulyossag> = { CRITICAL: 'critical', WARNING: 'warning', INFO: 'info' };

/** Interakció súlyossága címkeként; a sor színét a `sevtone--{s}` osztály adja (app.css). Közös a Klinikai státusszal. */
export function SulyossagCimke({ s }: { s: Sulyossag }) {
  return <span className="sevtag">{sulyossagCimke[s]}</span>;
}

const allapotSzoveg: Record<Feloldas['status'], string> = {
  RESOLVED: 'ellenőrizve',
  PARTIAL: 'részben ellenőrizhető',
  NO_DATA: 'nincs interakciós adat',
  NOT_FOUND: 'nem azonosítható',
};

/** Ahol nincs (teljes) adat, ott a riasztás hiánya NEM jelenti, hogy nincs interakció. */
function NemEllenorizheto({ sorok }: { sorok: { nev: string; feloldas: Feloldas }[] }) {
  if (sorok.length === 0) return null;
  return (
    <div className="item__sub" role="note">
      <span className="warnchip">
        <Icon name="alert" size={14} />
        Nem (teljesen) ellenőrizhető — itt a riasztás hiánya nem jelent biztonságot:
      </span>
      <ul className="meta">
        {sorok.map((s) => (
          <li key={s.nev}>
            <strong>{s.nev}</strong>: {allapotSzoveg[s.feloldas.status]}
            {s.feloldas.unresolved.length > 0 && ` (${s.feloldas.unresolved.join('; ')})`}
          </li>
        ))}
      </ul>
    </div>
  );
}

function ReferenciaAllapotSor() {
  const { state, reload } = useAsync(() => interakcioApi.allapot(), []);
  const frissites = useAkcio();
  const irhato = useIrhato();
  if (state.status === 'loading') return <Loading what="Interakciós adatbázis" />;
  if (state.status === 'error') return <ErrorState error={state.error} onRetry={reload} />;
  const s = state.data;
  const pupha = s.imports.find((i) => i.source === 'PUPHA');
  const ddinter = s.imports.find((i) => i.source === 'DDINTER');

  async function indit() {
    const r = await frissites.run(() => interakcioApi.frissites());
    if (r) reload();
  }

  return (
    <div className="item__sub">
      {s.loaded ? (
        <span className="meta">
          {pupha?.versionLabel} · {ddinter?.rowCount.toLocaleString('hu-HU')} interakciós pár · betöltve{' '}
          {ddinter && formatDatum(ddinter.importedAt)}
        </span>
      ) : (
        <span className="warnchip">
          <Icon name="alert" size={14} />
          Az interakciós adatbázis még nincs betöltve — nincs ellenőrzés
        </span>
      )}
      {s.running ? (
        <span className="meta">frissítés folyamatban…</span>
      ) : (
        <button type="button" className="btn btn--sm" onClick={() => void indit()} disabled={irhato.tiltva || frissites.busy} title={irhato.title}>
          Adatbázis frissítése
        </button>
      )}
      {s.lastRun && !s.lastRun.success && <span className="form-error">Utolsó frissítés hibás: {s.lastRun.error}</span>}
      {frissites.error && <span className="form-error">{frissites.error}</span>}
    </div>
  );
}

/** Az orvos 1–5 gyógyszert ír be: egymással és a szedettekkel vetjük össze. Nem tárolódik. */
function AdHocEllenorzes() {
  const { patient } = usePatient();
  const [beiras, setBeiras] = useState('');
  const [szerek, setSzerek] = useState<string[]>([]);
  const [javaslatok, setJavaslatok] = useState<string[]>([]);
  const [eredmeny, setEredmeny] = useState<EllenorzesEredmeny | null>(null);
  const ellenorzes = useAkcio();
  const inputId = useId();
  const listId = useId();

  async function gepeles(v: string) {
    setBeiras(v);
    if (v.trim().length >= 2) {
      try {
        setJavaslatok(await interakcioApi.javaslatok(v.trim()));
      } catch {
        setJavaslatok([]);
      }
    }
  }

  function hozzaad() {
    const v = beiras.trim();
    if (!v || szerek.length >= 5 || szerek.some((s) => s.toLowerCase() === v.toLowerCase())) return;
    setSzerek([...szerek, v]);
    setBeiras('');
    setEredmeny(null);
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    const lista = beiras.trim() && szerek.length < 5 ? [...szerek, beiras.trim()] : szerek;
    if (lista.length === 0) return;
    setSzerek(lista);
    setBeiras('');
    const r = await ellenorzes.run(() => interakcioApi.ellenoriz(patient.id, lista));
    if (r) setEredmeny(r);
  }

  return (
    <form className="tabform tabform--inset" onSubmit={submit} aria-label="Interakció-ellenőrzés">
      <strong>Interakció-ellenőrzés új gyógyszerre</strong>
      <span className="meta">
        Írj be 1–5 gyógyszert (márkanév vagy hatóanyag) — a beteg szedett gyógyszereivel és egymással vetjük össze.
      </span>
      <div className="row-end">
        <div className="field grow">
          <label htmlFor={inputId}>Gyógyszer</label>
          <input
            id={inputId}
            className="input"
            maxLength={255}
            list={listId}
            placeholder="pl. Nurofen vagy ibuprofen"
            value={beiras}
            onChange={(e) => void gepeles(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && beiras.trim() && !e.nativeEvent.isComposing) {
                e.preventDefault();
                hozzaad();
              }
            }}
          />
          <datalist id={listId}>
            {javaslatok.map((j) => (
              <option key={j} value={j} />
            ))}
          </datalist>
        </div>
        <button type="button" className="btn" onClick={hozzaad} disabled={!beiras.trim() || szerek.length >= 5}>
          <Icon name="plus" size={16} strokeWidth={2.4} />
          Hozzáad
        </button>
        <button
          type="submit"
          className="btn btn--primary"
          disabled={ellenorzes.busy || (szerek.length === 0 && !beiras.trim())}
        >
          {ellenorzes.busy ? 'Ellenőrzés…' : 'Ellenőrzés'}
        </button>
      </div>
      {szerek.length > 0 && (
        <div className="chips">
          {szerek.map((s) => (
            <span key={s} className="chip chip--on">
              {s}
              <button
                type="button"
                className="chip__btn"
                aria-label={`${s} eltávolítása`}
                onClick={() => {
                  setSzerek(szerek.filter((x) => x !== s));
                  setEredmeny(null);
                }}
              >
                <Icon name="x" size={12} />
              </button>
            </span>
          ))}
        </div>
      )}
      {ellenorzes.error && (
        <p className="form-error" role="alert">
          {ellenorzes.error}
        </p>
      )}
      {eredmeny && <EllenorzesEredmenye e={eredmeny} />}
    </form>
  );
}

function EllenorzesEredmenye({ e }: { e: EllenorzesEredmeny }) {
  if (!e.loaded) {
    return <p className="form-error">Az interakciós adatbázis még nincs betöltve — nem ellenőrizhető.</p>;
  }
  const hianyos = [...e.entered, ...e.current]
    .filter((x) => x.resolution.status !== 'RESOLVED')
    .map((x) => ({ nev: x.label, feloldas: x.resolution }));
  return (
    <div aria-live="polite">
      <ul className="meta">
        {e.entered.map((x) => (
          <li key={x.label}>
            <strong>{x.label}</strong> →{' '}
            {x.resolution.ingredients.length > 0
              ? x.resolution.ingredients.map((i) => i.name).join(', ')
              : allapotSzoveg[x.resolution.status]}
            {x.resolution.products.length > 0 && ` (pl. ${x.resolution.products[0]})`}
          </li>
        ))}
      </ul>
      {e.findings.length === 0 ? (
        <p className="meta">
          <Icon name="check" size={14} /> Nincs ismert interakció a beírt és a szedett gyógyszerek között
          {e.current.length === 0 && ' (a betegnek nincs szedett gyógyszere)'}.
        </p>
      ) : (
        <TajekoztatoLenyitva
          elemek={e.findings}
          tajekoztato={(f) => f.severity === 'INFO'}
          render={(f) => (
            <li key={`${f.labelA}|${f.labelB}`} className={`item sevtone--${dtoSulyossag[f.severity]}`}>
              <div className="item__row">
                <Icon name="alert" size={16} strokeWidth={2.4} />
                <SulyossagCimke s={dtoSulyossag[f.severity]} />
                <span>{f.text}</span>
              </div>
              {(f.medicationIdA ?? f.medicationIdB) !== null && (
                <div className="item__sub">
                  <span className="meta">szedett gyógyszerrel</span>
                </div>
              )}
            </li>
          )}
        />
      )}
      <p className="meta">
        Minden pár össze van vetve; amelyik nem szerepel a listán, arra a DDInterben nincs interakciós bejegyzés.
      </p>
      <NemEllenorizheto sorok={hianyos} />
    </div>
  );
}
