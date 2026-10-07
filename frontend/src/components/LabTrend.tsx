import { useEffect, useId, useRef, useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api';
import type { LaborJel, LaborMeres, LaborParameter, LaborSzovegesParameter, UjLaborSor } from '../api/types';
import { formatDatum, formatDatumIdo, formatHonap, formatSzam, helyiToIso, parseSzam } from '../lib/format';
import { useAkcio } from '../lib/useAkcio';
import { useAsync } from '../lib/useAsync';
import { usePatient } from '../patient/PatientContext';
import { Icon } from './Icon';
import { Segmented } from './Segmented';
import { ErrorState, Loading } from './shell';

type Idoszak = '6ho' | '1ev' | 'mind';
const napok: Record<Idoszak, number> = { '6ho': 183, '1ev': 366, mind: Infinity };

/** Egy érték tizedesjegyei (a backend legfeljebb 4-et tárol) */
const tizedesOf = (v: number) => {
  const s = String(v);
  const i = s.indexOf('.');
  return i < 0 ? 0 : Math.min(4, s.length - i - 1);
};

/**
 * A megjelenítés annyi tizedesjegyet mutat, amennyi a mért értékekben és a
 * határokban ténylegesen van — egy 0,123-as TSH nem jelenhet meg „0,1”-ként
 * (CLAUDE.md: nem kerekítünk csendben, a megjelenítésben sem).
 */
const tizedes = (p: LaborParameter) =>
  Math.max(
    0,
    ...p.meresek.map((m) => tizedesOf(m.ertek)),
    ...(p.refAlso !== null ? [tizedesOf(p.refAlso)] : []),
    ...(p.refFelso !== null ? [tizedesOf(p.refFelso)] : []),
  );
/**
 * Tartományon kívül? Cenzorált eredménynél (">90") csak akkor jelzünk, ha a jelből biztosan
 * következik — pl. ">90" és ref. felső 60 → felett; ">90" és ref. felső 120 → nem tudható.
 */
function irany(p: LaborParameter, v: number, jel: LaborJel | null = null): 'alatt' | 'felett' | null {
  const alatt =
    p.refAlso !== null &&
    (jel === null ? v < p.refAlso : jel === '<' ? v <= p.refAlso : jel === '<=' ? v < p.refAlso : false);
  const felett =
    p.refFelso !== null &&
    (jel === null ? v > p.refFelso : jel === '>' ? v >= p.refFelso : jel === '>=' ? v > p.refFelso : false);
  return alatt ? 'alatt' : felett ? 'felett' : null;
}

const kivul = (p: LaborParameter, v: number, jel: LaborJel | null = null) => irany(p, v, jel) !== null;

function statusz(p: LaborParameter, v: number, jel: LaborJel | null = null) {
  const i = irany(p, v, jel);
  if (i === 'alatt') return p.celtartomany ? '↓ cél alatt' : '↓ ref. alatt';
  if (i === 'felett') return p.celtartomany ? '↑ cél felett' : '↑ ref. felett';
  return null;
}

const JEL: Record<LaborJel, string> = { '<': '<', '<=': '≤', '>': '>', '>=': '≥' };

/** Megjelenített érték: cenzoráltnál a jellel együtt (">90"), hogy ne tűnjön pontos mérésnek */
const ertekSzoveg = (v: number, d: number, jel: LaborJel | null) => `${jel ? JEL[jel] : ''}${formatSzam(v, d)}`;

function refSzoveg(p: LaborParameter) {
  const d = tizedes(p);
  const pre = p.celtartomany ? 'cél' : 'ref.';
  if (p.refAlso !== null && p.refFelso !== null)
    return `${pre} ${formatSzam(p.refAlso, d)}–${formatSzam(p.refFelso, d)}`;
  if (p.refAlso !== null) return `${pre} > ${formatSzam(p.refAlso, d)}`;
  if (p.refFelso !== null) return `${pre} < ${formatSzam(p.refFelso, d)}`;
  return '';
}

/**
 * Labor-idősor: kis diagramok paraméterenként (egy tengely, egy sorozat),
 * referenciasávval. A tartományon kívüli pont alakban is más (teli + gyűrű),
 * nem csak színben. Pontra kattintva a mérés részletei nyílnak meg.
 *
 * Egy vizsgálatra szűrve a backend szűr (GET /lab-results?patientId=&name=).
 */
export function LabTrend() {
  const { patient, frissit } = usePatient();
  const [nevSzuro, setNevSzuro] = useState('');
  const osszes = useAsync(() => api.getLaborIdosor(patient.id), [patient.id]);
  const szurt = useAsync(
    () => (nevSzuro ? api.getLaborIdosor(patient.id, nevSzuro) : Promise.resolve(null)),
    [patient.id, nevSzuro],
  );
  const [idoszak, setIdoszak] = useState<Idoszak>('mind');
  const [nezet, setNezet] = useState<'diagram' | 'tablazat'>('diagram');
  const [rogzites, setRogzites] = useState(false);
  const [reszletId, setReszletId] = useState<number | null>(null);
  const szuroId = useId();

  const rogzitve = async () => {
    setRogzites(false);
    await Promise.all([osszes.refresh(), szurt.refresh(), frissit()]);
  };

  if (osszes.state.status === 'loading') return <Loading what="Labor-idősor" />;
  if (osszes.state.status === 'error') return <ErrorState error={osszes.state.error} onRetry={osszes.reload} />;
  if (osszes.state.data.patientId !== patient.id) {
    return <ErrorState error={new Error('A labor-adat nem az aktív beteghez tartozik — rejtve.')} />;
  }

  const nevek = [
    ...new Set([...osszes.state.data.parameterek, ...osszes.state.data.szovegesek].map((p) => p.nev)),
  ].sort((a, b) => a.localeCompare(b, 'hu'));
  const adat = nevSzuro ? (szurt.state.status === 'ok' ? szurt.state.data : null) : osszes.state.data;

  const minden = [...(adat?.parameterek ?? []), ...(adat?.szovegesek ?? [])]
    .flatMap((p) => p.meresek.map((m) => m.datum))
    .sort();
  const utolso = minden.at(-1);
  const hatar = utolso && idoszak !== 'mind' ? new Date(new Date(utolso).getTime() - napok[idoszak] * 864e5) : null;
  const params = (adat?.parameterek ?? []).map((p) => ({
    ...p,
    meresek: p.meresek
      .filter((m) => !hatar || new Date(m.datum) >= hatar)
      .sort((a, b) => new Date(a.datum).getTime() - new Date(b.datum).getTime()),
  }));
  const szovegesek = (adat?.szovegesek ?? [])
    .map((p) => ({
      ...p,
      meresek: p.meresek
        .filter((m) => !hatar || new Date(m.datum) >= hatar)
        .sort((a, b) => new Date(a.datum).getTime() - new Date(b.datum).getTime()),
    }))
    .filter((p) => p.meresek.length > 0);
  const leletSzam = new Set(
    [...params, ...szovegesek]
      .flatMap((p) => p.meresek.map((m) => m.dokumentumId))
      .filter((id): id is number => id !== null),
  ).size;
  const meresSzam = [...params, ...szovegesek].reduce((n, p) => n + p.meresek.length, 0);
  // Számos vizsgálat = egy grafikon (a kiválasztott időszakban mérttel); szöveges csak táblázatban
  const grafikonSzam = params.filter((p) => p.meresek.length > 0).length;

  return (
    <div className="labtrend">
      <div className="panel__head panel__head--row">
        <div className="stack-4">
          <h2 className="h2 h2--sm">Laboreredmények időben</h2>
          <span className="meta row-inline">
            <Icon name="doc" />
            {meresSzam} mért érték{leletSzam > 0 && `, ${leletSzam} laborleletből`}, {grafikonSzam + szovegesek.length}{' '}
            különböző vizsgálat
          </span>
        </div>
        <div className="row-gap-10 push-right labtrend__controls">
          <label className="sr-only" htmlFor={szuroId}>
            Vizsgálat
          </label>
          <select
            id={szuroId}
            className="input input--sm"
            value={nevSzuro}
            onChange={(e) => setNevSzuro(e.target.value)}
          >
            <option value="">Minden vizsgálat</option>
            {nevek.map((n) => (
              <option key={n} value={n}>
                {n}
              </option>
            ))}
          </select>
          <Segmented
            label="Időszak"
            value={idoszak}
            onChange={setIdoszak}
            options={[
              ['6ho', '6 hó'],
              ['1ev', '1 év'],
              ['mind', 'Mind'],
            ]}
          />
          <Segmented
            label="Nézet"
            value={nezet}
            onChange={setNezet}
            options={[
              ['diagram', 'Diagram'],
              ['tablazat', 'Táblázat'],
            ]}
          />
          {!rogzites && (
            <button type="button" className="btn btn--sm" onClick={() => setRogzites(true)}>
              <Icon name="plus" size={14} strokeWidth={2.4} />
              Labor rögzítése
            </button>
          )}
        </div>
      </div>

      {rogzites && <LaborRogzites onDone={() => void rogzitve()} onCancel={() => setRogzites(false)} />}
      {reszletId !== null && <LaborReszletDoboz id={reszletId} onClose={() => setReszletId(null)} />}

      {nevSzuro && szurt.state.status === 'loading' && <Loading what={nevSzuro} />}
      {nevSzuro && szurt.state.status === 'error' && <ErrorState error={szurt.state.error} onRetry={szurt.reload} />}
      {adat && params.length === 0 && szovegesek.length === 0 && (
        <p className="empty docview__hint">Nincs laboradat.</p>
      )}

      {adat &&
        (params.length > 0 || szovegesek.length > 0) &&
        (nezet === 'diagram' ? (
          <>
            <div className="labgrid">
              {params.map((p) => (
                <LabChart key={p.kod} p={p} onValaszt={setReszletId} />
              ))}
            </div>
            <div className="lablegend">
              <span className="row-inline">
                <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden="true">
                  <circle cx="8" cy="8" r="5" fill="var(--c-surface)" stroke="var(--c-ink)" strokeWidth="2" />
                </svg>
                tartományon belül
              </span>
              <span className="row-inline">
                <svg width="18" height="18" viewBox="0 0 18 18" aria-hidden="true">
                  <circle cx="9" cy="9" r="5" fill="var(--c-ink)" />
                  <circle cx="9" cy="9" r="7.5" fill="none" stroke="var(--c-ink)" strokeWidth="1.5" />
                </svg>
                tartományon kívül
              </span>
              <span className="row-inline">
                <span className="lablegend__band" />
                referencia- / céltartomány
              </span>
              <span className="push-right">Pontra kattintva a mérés részletei nyílnak meg.</span>
            </div>
            {szovegesek.length > 0 && (
              <>
                <h3 className="h3">Szöveges eredmények</h3>
                <LabTable params={[]} szovegesek={szovegesek} onValaszt={setReszletId} />
              </>
            )}
          </>
        ) : (
          <LabTable params={params} szovegesek={szovegesek} onValaszt={setReszletId} />
        ))}
    </div>
  );
}

/** GET /api/lab-results/{id} — egy mérés minden adata, a lelet megnyitásával. */
function LaborReszletDoboz({ id, onClose }: { id: number; onClose: () => void }) {
  const dobozRef = useRef<HTMLElement>(null);
  // Megnyitáskor a görgető panel tetejére hozza a dobozt — a ragadós fülsáv (.ptabs) ALÁ, ami a
  // szélességtől függően egy vagy két sor magas, ezért a tényleges magasságával számolunk
  useEffect(() => {
    const doboz = dobozRef.current;
    const panel = doboz?.closest<HTMLElement>('.workspace > *');
    if (!doboz || !panel) return;
    const fulsav = panel.querySelector<HTMLElement>('.ptabs')?.offsetHeight ?? 0;
    const cel = panel.scrollTop + doboz.getBoundingClientRect().top - panel.getBoundingClientRect().top - fulsav - 12;
    panel.scrollTo({ top: Math.max(0, cel), behavior: 'smooth' });
  }, [id]);
  const { patient } = usePatient();
  const navigate = useNavigate();
  const { state, reload } = useAsync(() => api.getLaborMeres(id), [id]);

  return (
    <section ref={dobozRef} className="detailbox" aria-label="Mérés részletei">
      <div className="row-baseline">
        <strong>Mérés részletei</strong>
        <button
          type="button"
          className="iconbtn iconbtn--ghost iconbtn--sm push-right"
          aria-label="Részletek bezárása"
          onClick={onClose}
        >
          <Icon name="x" size={16} />
        </button>
      </div>
      {state.status === 'loading' && <Loading what="Mérés" />}
      {state.status === 'error' && <ErrorState error={state.error} onRetry={reload} />}
      {state.status === 'ok' && state.data.patientId !== patient.id && (
        <ErrorState error={new Error('A mérés nem az aktív beteghez tartozik — rejtve.')} />
      )}
      {state.status === 'ok' && state.data.patientId === patient.id && (
        <dl className="deflist">
          <dt>Vizsgálat</dt>
          <dd>
            {state.data.nev}
            {state.data.csoport && <span className="meta"> · {state.data.csoport}</span>}
            {state.data.leletNev !== state.data.nev && (
              <span className="meta"> · a leleten: „{state.data.leletNev}”</span>
            )}
          </dd>
          <dt>Érték</dt>
          <dd className="mono">
            {state.data.ertek === null
              ? state.data.szoveg
              : ertekSzoveg(state.data.ertek, tizedesOf(state.data.ertek), state.data.jel)}{' '}
            {state.data.mertekegyseg ?? ''}
          </dd>
          <dt>Referencia</dt>
          <dd className="mono">
            {state.data.refAlso === null && state.data.refFelso === null
              ? (state.data.refSzoveg ?? '—')
              : `${state.data.refAlso === null ? '' : formatSzam(state.data.refAlso, tizedesOf(state.data.refAlso))}–${
                  state.data.refFelso === null ? '' : formatSzam(state.data.refFelso, tizedesOf(state.data.refFelso))
                }`}
          </dd>
          <dt>Mérés ideje</dt>
          <dd className="mono">{formatDatumIdo(state.data.idopont)}</dd>
          <dt>Kód</dt>
          <dd className="mono">{state.data.kod ?? '—'}</dd>
          <dt>Lelet</dt>
          <dd>
            {state.data.dokumentumId !== null ? (
              <button
                type="button"
                className="linkbtn"
                onClick={() => {
                  const d = state.data.forrasDoboz;
                  const doboz = d ? `&oldal=${d.oldal}&doboz=${[d.x, d.y, d.szelesseg, d.magassag].join(',')}` : '';
                  navigate(`/betegek/${patient.id}?nezet=dokumentum&dok=${state.data.dokumentumId}&pdf=1${doboz}`);
                }}
              >
                Dokumentum #{state.data.dokumentumId} megnyitása →
              </button>
            ) : (
              <span className="meta">dokumentum nélkül rögzítve</span>
            )}
          </dd>
        </dl>
      )}
    </section>
  );
}

interface SorBevitel {
  csoport: string;
  nev: string;
  ertek: string;
  egyseg: string;
  refAlso: string;
  refFelso: string;
  kod: string;
}

const uresSor = (): SorBevitel => ({ csoport: '', nev: '', ertek: '', egyseg: '', refAlso: '', refFelso: '', kod: '' });

/** Egy sor hibája a backend LabResultRequest szabályai szerint (null: rendben) */
function sorHiba(s: SorBevitel): string | null {
  if (!s.nev.trim()) return 'hiányzik a vizsgálat neve';
  if (s.nev.trim().length > 255) return 'a név legfeljebb 255 karakter';
  const e = parseSzam(s.ertek, 4);
  if (e === null) return 'az érték szám legyen (legfeljebb 4 tizedes) — cenzorált/kvalitatív eredmény nem rögzíthető';
  if (Math.abs(e) >= 1e8) return 'az érték túl nagy';
  const also = s.refAlso.trim() ? parseSzam(s.refAlso, 4) : null;
  const felso = s.refFelso.trim() ? parseSzam(s.refFelso, 4) : null;
  if (s.refAlso.trim() && also === null) return 'a ref. alsó határ nem szám';
  if (s.refFelso.trim() && felso === null) return 'a ref. felső határ nem szám';
  if (also !== null && felso !== null && also > felso) return 'az alsó határ nagyobb a felsőnél';
  if (s.egyseg.length > 32) return 'a mértékegység legfeljebb 32 karakter';
  if (s.csoport.length > 128) return 'a csoport legfeljebb 128 karakter';
  if (s.kod.length > 64) return 'a kód legfeljebb 64 karakter';
  return null;
}

/**
 * POST /api/lab-results — egy panel egy kérésben, egy tranzakcióban: ha egy sor hibás,
 * egyik sem kerül be. Dokumentum megadásakor a backend a beteg-egyezést is ellenőrzi.
 */
function LaborRogzites({ onDone, onCancel }: { onDone: () => void; onCancel: () => void }) {
  const { patient, dokumentumok } = usePatient();
  const leletek = dokumentumok.filter((d) => d.tipus === 'labor');
  const [dokId, setDokId] = useState('');
  const [ido, setIdo] = useState('');
  const [sorok, setSorok] = useState<SorBevitel[]>([uresSor()]);
  const [tried, setTried] = useState(false);
  const { busy, error, run } = useAkcio();
  const ids = { dok: useId(), ido: useId() };

  const lelet = leletek.find((d) => String(d.id) === dokId);
  // A backend csak a lelet KLINIKAI dátumából pótolja a mérési időt
  const idoKell = !lelet?.klinikaiDatum;
  const hibak = sorok.map(sorHiba);
  const idoHiba = idoKell && !ido ? 'Add meg a mintavétel idejét (a leletnek nincs klinikai dátuma).' : null;
  const ok = hibak.every((h) => h === null) && !idoHiba;

  const frissitSor = (i: number, mezo: keyof SorBevitel, ertek: string) =>
    setSorok((s) => s.map((sor, j) => (j === i ? { ...sor, [mezo]: ertek } : sor)));

  async function submit(e: FormEvent) {
    e.preventDefault();
    setTried(true);
    if (!ok) return;
    const idopont = ido ? helyiToIso(ido) : null;
    const ujSorok: UjLaborSor[] = sorok.map((s) => ({
      csoport: s.csoport.trim() || null,
      nev: s.nev.trim(),
      ertek: parseSzam(s.ertek, 4)!,
      mertekegyseg: s.egyseg.trim() || null,
      refAlso: s.refAlso.trim() ? parseSzam(s.refAlso, 4) : null,
      refFelso: s.refFelso.trim() ? parseSzam(s.refFelso, 4) : null,
      idopont,
      kod: s.kod.trim() || null,
    }));
    const db = await run(() =>
      api.createLaborPanel({ patientId: patient.id, dokumentumId: dokId ? Number(dokId) : null, sorok: ujSorok }),
    );
    if (db !== undefined) onDone();
  }

  return (
    <form className="tabform" onSubmit={submit} noValidate aria-label="Labor rögzítése">
      <div className="row-baseline">
        <h2 className="h2 h2--sm">Labor-panel rögzítése</h2>
        <span className="meta">egy panel = egy mentés; hibás sornál egyik sem kerül be</span>
      </div>
      <div className="row-end">
        <div className="field grow">
          <label htmlFor={ids.dok}>
            Lelet <span className="meta">(nem kötelező)</span>
          </label>
          <select id={ids.dok} className="input" value={dokId} onChange={(e) => setDokId(e.target.value)}>
            <option value="">— dokumentum nélkül —</option>
            {leletek.map((d) => (
              <option key={d.id} value={d.id}>
                #{d.id} · {d.cim} · {formatDatum(d.datum)}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor={ids.ido}>
            Mintavétel ideje {!idoKell && <span className="meta">(üresen: a lelet dátuma)</span>}
          </label>
          <input
            id={ids.ido}
            type="datetime-local"
            className="input mono"
            value={ido}
            onChange={(e) => setIdo(e.target.value)}
            aria-invalid={tried && !!idoHiba}
          />
        </div>
      </div>
      {tried && idoHiba && <p className="form-error">{idoHiba}</p>}

      <div className="labtable-wrap labtable-wrap--form">
        <table className="labtable labtable--form">
          <thead>
            <tr>
              <th scope="col">Vizsgálat*</th>
              <th scope="col">Érték*</th>
              <th scope="col">Egység</th>
              <th scope="col">Ref. alsó</th>
              <th scope="col">Ref. felső</th>
              <th scope="col">Csoport</th>
              <th scope="col">Kód</th>
              <th scope="col">
                <span className="sr-only">Sor törlése</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {sorok.map((s, i) => (
              <tr key={i}>
                {(['nev', 'ertek', 'egyseg', 'refAlso', 'refFelso', 'csoport', 'kod'] as const).map((m) => (
                  <td key={m}>
                    <input
                      className={`input input--sm${m === 'nev' || m === 'csoport' ? '' : ' mono'}`}
                      aria-label={`${i + 1}. sor: ${m}`}
                      inputMode={m === 'ertek' || m === 'refAlso' || m === 'refFelso' ? 'decimal' : undefined}
                      value={s[m]}
                      onChange={(e) => frissitSor(i, m, e.target.value)}
                      aria-invalid={tried && !!hibak[i]}
                    />
                  </td>
                ))}
                <td>
                  <button
                    type="button"
                    className="iconbtn iconbtn--ghost iconbtn--sm"
                    aria-label={`${i + 1}. sor törlése`}
                    onClick={() => setSorok((all) => all.filter((_, j) => j !== i))}
                    disabled={sorok.length === 1}
                  >
                    <Icon name="x" size={16} />
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {tried &&
        hibak.map(
          (h, i) =>
            h && (
              <p key={i} className="form-error">
                {i + 1}. sor: {h}
              </p>
            ),
        )}
      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}
      <div className="actions">
        <button type="button" className="btn btn--sm" onClick={() => setSorok((s) => [...s, uresSor()])}>
          <Icon name="plus" size={14} strokeWidth={2.4} />
          Új sor
        </button>
        <button type="submit" className="btn btn--primary" disabled={busy}>
          <Icon name="check" strokeWidth={2.6} />
          {busy ? 'Mentés…' : `Panel mentése (${sorok.length} sor)`}
        </button>
        <button type="button" className="btn" onClick={onCancel} disabled={busy}>
          Mégse
        </button>
      </div>
    </form>
  );
}

const TIP_W = 200;
const H = 170,
  Y0 = 16,
  Y1 = 136;

/** A diagram a valós pixelszélességével rajzol, így nem skálázódik a betű. */
function useWidth<T extends HTMLElement>() {
  const ref = useRef<T>(null);
  const [w, setW] = useState(440);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const ro = new ResizeObserver(([e]) => setW(Math.max(240, Math.round(e.contentRect.width))));
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  return [ref, w] as const;
}

function LabChart({ p, onValaszt }: { p: LaborParameter; onValaszt: (id: number) => void }) {
  const [hover, setHover] = useState<number | null>(null);
  // Pontra kattintva a tooltip rögzül (nem nyitja meg rögtön a részleteket); a részleteket a
  // tooltip "Részletek →" gombja nyitja. Újabb kattintás ugyanarra a pontra / Esc: elengedi.
  const [kijelolt, setKijelolt] = useState<number | null>(null);
  const valt = (i: number) => setKijelolt((k) => (k === i ? null : i));
  const [wrapRef, W] = useWidth<HTMLDivElement>();
  const X0 = 60,
    X1 = W - 28,
    L = 44,
    R = W - 8;
  const ms = p.meresek;
  const d = tizedes(p);
  if (ms.length === 0) return null;
  const Hh = H + 20;

  const last = ms[ms.length - 1];
  const first = ms[0];
  const st = statusz(p, last.ertek, last.jel);

  // y-tartomány: mérések + referenciahatárok, kis ráhagyással
  const vals = [
    ...ms.map((m) => m.ertek),
    ...(p.refAlso !== null ? [p.refAlso] : []),
    ...(p.refFelso !== null ? [p.refFelso] : []),
  ];
  const span = Math.max(...vals) - Math.min(...vals) || 1;
  const lo = Math.min(...vals) - span * 0.25;
  const hi = Math.max(...vals) + span * 0.25;
  const y = (v: number) => Y1 - ((v - lo) / (hi - lo)) * (Y1 - Y0);

  const t0 = new Date(first.datum).getTime();
  const t1 = new Date(last.datum).getTime();
  const x = (m: LaborMeres) =>
    t1 === t0 ? (X0 + X1) / 2 : X0 + ((new Date(m.datum).getTime() - t0) / (t1 - t0)) * (X1 - X0);

  const bandTop = y(Math.min(p.refFelso ?? hi, hi));
  const bandBot = y(Math.max(p.refAlso ?? lo, lo));
  const ticks = [p.refAlso, p.refFelso].filter((v): v is number => v !== null);

  const aktiv = kijelolt ?? hover;
  const h = aktiv !== null ? (ms[aktiv] ?? null) : null;
  // Tooltip a pont MELLETT — sosem takarhatja a saját pontját (különben a pontra kattintás a
  // tooltip "Részletek →" gombjára esne): balra, ha ott elfér, különben jobbra; a grafikonon belül
  const tipX = h ? (x(h) - TIP_W - 10 >= L ? x(h) - TIP_W - 10 : Math.min(x(h) + 10, W - TIP_W - 2)) : 0;

  return (
    <article className="chart" aria-label={`${p.nev} idősor`}>
      <div className="row-baseline">
        <h3 className="h3">{p.nev}</h3>
        {p.mertekegyseg && <span className="meta">{p.mertekegyseg}</span>}
        <span className="meta push-right">{refSzoveg(p)}</span>
      </div>
      <div className="row-inline row-gap-10">
        <span className="big">{ertekSzoveg(last.ertek, d, last.jel)}</span>
        {st && <span className="flag">{st}</span>}
        <span className="meta push-right">
          {ertekSzoveg(first.ertek, d, first.jel)} → {ertekSzoveg(last.ertek, d, last.jel)} · {ms.length} mérés
        </span>
      </div>
      <div ref={wrapRef}>
        <svg
          viewBox={`0 0 ${W} ${Hh}`}
          width={W}
          height={Hh}
          role="img"
          aria-label={`${p.nev}: ${ms.map((m) => `${formatDatum(m.datum)} ${ertekSzoveg(m.ertek, d, m.jel)}`).join('; ')}`}
          onMouseLeave={() => setHover(null)}
          onKeyDown={(e) => e.key === 'Escape' && setKijelolt(null)}
        >
          <rect x={L} y={bandTop} width={R - L} height={Math.max(0, bandBot - bandTop)} fill="var(--c-ref-band)" />
          {ticks.map((t) => (
            <g key={t}>
              <line x1={L} x2={R} y1={y(t)} y2={y(t)} stroke="var(--c-line)" />
              <text
                x={38}
                y={y(t) + 4}
                fontSize={11}
                textAnchor="end"
                fill="var(--c-ink-muted)"
                fontFamily="var(--font-mono)"
              >
                {formatSzam(t, d)}
              </text>
            </g>
          ))}
          <line x1={L} x2={R} y1={Y1} y2={Y1} stroke="var(--c-line-input)" />
          {ms.map((m) => (
            <text
              key={m.id}
              x={x(m)}
              y={Y1 + 20}
              fontSize={11}
              textAnchor="middle"
              fill="var(--c-ink-muted)"
              fontFamily="var(--font-mono)"
            >
              {formatHonap(m.datum)}
            </text>
          ))}
          {h && <line x1={x(h)} x2={x(h)} y1={18} y2={Y1} stroke="var(--c-ink)" strokeDasharray="3 3" />}
          <polyline
            points={ms.map((m) => `${x(m)},${y(m.ertek)}`).join(' ')}
            fill="none"
            stroke="var(--c-ink)"
            strokeWidth={2}
            strokeLinejoin="round"
          />
          {ms.map((m, i) => {
            const out = kivul(p, m.ertek, m.jel);
            return (
              <g key={m.id}>
                <circle
                  cx={x(m)}
                  cy={y(m.ertek)}
                  r={5.5}
                  fill={out ? 'var(--c-ink)' : 'var(--c-surface)'}
                  stroke={out ? 'var(--c-surface)' : 'var(--c-ink)'}
                  strokeWidth={2}
                />
                {out && <circle cx={x(m)} cy={y(m.ertek)} r={7} fill="none" stroke="var(--c-ink)" strokeWidth={1.5} />}
                {/* nagyobb, láthatatlan találati felület + billentyűzettel is elérhető */}
                <circle
                  cx={x(m)}
                  cy={y(m.ertek)}
                  r={16}
                  fill="transparent"
                  className="hit"
                  tabIndex={0}
                  role="button"
                  aria-pressed={kijelolt === i}
                  aria-label={`${formatDatum(m.datum)}: ${p.nev} ${ertekSzoveg(m.ertek, d, m.jel)}${statusz(p, m.ertek, m.jel) ? ', ' + statusz(p, m.ertek, m.jel) : ''}. Kiválasztás.`}
                  onMouseEnter={() => setHover(i)}
                  onFocus={() => setHover(i)}
                  onBlur={() => setHover(null)}
                  onClick={() => valt(i)}
                  onKeyDown={(e) => (e.key === 'Enter' || e.key === ' ') && (e.preventDefault(), valt(i))}
                />
              </g>
            );
          })}
          {!h && (
            <text
              x={x(last) - 12}
              y={y(last.ertek) - 10}
              fontSize={13}
              fontWeight={700}
              textAnchor="end"
              fill="var(--c-ink)"
            >
              {ertekSzoveg(last.ertek, d, last.jel)}
            </text>
          )}
          {h && (
            /* HTML-tooltip (foreignObject): a hosszú név több sorba törik, ami 3 sorba sem fér,
               azt "…" helyettesíti — az SVG <text> nem tud tördelni. A foreignObject átlátszó és
               nem fogja el a kattintást (a mögötte lévő pontok elérhetők), csak a gomb. */
            <foreignObject x={tipX} y={10} width={TIP_W} height={Hh - 14} pointerEvents="none">
              <div className={`charttip${kijelolt !== null ? ' charttip--pinned' : ''}`}>
                <div className="charttip__meta">
                  {formatDatum(h.datum)} · {h.dokumentumId !== null ? 'Laborlelet' : 'kézi rögzítés'}
                </div>
                <div className="charttip__title" title={`${p.nev} ${ertekSzoveg(h.ertek, d, h.jel)}`}>
                  {p.nev} {ertekSzoveg(h.ertek, d, h.jel)} {statusz(p, h.ertek, h.jel) ?? ''}
                </div>
                {/* csak ez nyitja meg a Mérés részleteit */}
                <button
                  type="button"
                  className="charttip__more"
                  aria-label={`${p.nev} ${formatDatum(h.datum)}: mérés részletei`}
                  onMouseEnter={() => aktiv !== null && setHover(aktiv)}
                  onClick={() => onValaszt(h.id)}
                >
                  Részletek →
                </button>
              </div>
            </foreignObject>
          )}
        </svg>
      </div>
    </article>
  );
}

/**
 * Kvalitatív eredmény eltér-e a szöveges referenciától ("Pozitív" vs. "Negatív") —
 * kis/nagybetű-független összevetés; referencia nélkül nem jelzünk semmit.
 */
const szovegEltér = (p: LaborSzovegesParameter, szoveg: string) =>
  p.refSzoveg !== null && szoveg.trim().toLocaleLowerCase('hu') !== p.refSzoveg.trim().toLocaleLowerCase('hu');

function LabTable({
  params,
  szovegesek = [],
  onValaszt,
}: {
  params: LaborParameter[];
  szovegesek?: LaborSzovegesParameter[];
  onValaszt: (id: number) => void;
}) {
  const datumok = [...new Set([...params, ...szovegesek].flatMap((p) => p.meresek.map((m) => m.datum)))].sort(
    (a, b) => new Date(a).getTime() - new Date(b).getTime(),
  );
  return (
    <div className="labtable-wrap">
      <table className="labtable">
        <caption className="sr-only">Laboreredmények dátum szerint</caption>
        <thead>
          <tr>
            <th scope="col">Paraméter</th>
            <th scope="col">Tartomány</th>
            {datumok.map((d) => (
              <th key={d} scope="col" className="mono">
                {formatDatum(d)}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {params.map((p) => (
            <tr key={p.kod}>
              <th scope="row">
                {p.nev} <span className="meta">{p.mertekegyseg}</span>
              </th>
              <td className="meta">{refSzoveg(p)}</td>
              {datumok.map((d) => {
                const m = p.meresek.find((x) => x.datum === d);
                const st = m ? statusz(p, m.ertek, m.jel) : null;
                return (
                  <td key={d} className={`mono${st ? ' labtable__out' : ''}`}>
                    {m ? (
                      <button type="button" className="linkbtn mono" onClick={() => onValaszt(m.id)}>
                        {ertekSzoveg(m.ertek, tizedes(p), m.jel)} {st && <span aria-label={st}>{st.slice(0, 1)}</span>}
                      </button>
                    ) : (
                      '—'
                    )}
                  </td>
                );
              })}
            </tr>
          ))}
          {szovegesek.map((p) => (
            <tr key={p.kod}>
              <th scope="row">
                {p.nev} <span className="meta">{p.mertekegyseg}</span>
              </th>
              <td className="meta">{p.refSzoveg ? `ref. ${p.refSzoveg}` : '—'}</td>
              {datumok.map((d) => {
                const m = p.meresek.find((x) => x.datum === d);
                const elter = m ? szovegEltér(p, m.szoveg) : false;
                return (
                  <td key={d} className={elter ? 'labtable__out' : undefined}>
                    {m ? (
                      <button type="button" className="linkbtn" onClick={() => onValaszt(m.id)}>
                        {m.szoveg} {elter && <span aria-label="eltér a referenciától">≠</span>}
                      </button>
                    ) : (
                      '—'
                    )}
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
