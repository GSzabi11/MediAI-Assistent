import { useEffect, useMemo } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api';
import type { KlinikaiOsszefoglalo, LaborIdosor } from '../api/types';
import { formatDatum, formatDatumIdo } from '../lib/format';
import { ertekSzoveg, irany, tizedesOf } from '../lib/laborErtek';
import { useAkcio } from '../lib/useAkcio';
import { useAsync } from '../lib/useAsync';
import { usePatient } from '../patient/PatientContext';
import { Icon } from './Icon';
import { SulyossagCimke } from './GyogyszerInterakcio';
import { ErrorState, Loading } from './shell';

/**
 * Klinikai státusz: AI-összefoglaló + proaktív javaslatok a beteg elmúlt 6 hónapjáról (gombra,
 * a háttérben készül), és a "Fő eltérések" — ez utóbbi NEM a modelltől jön, hanem a
 * determinisztikus referencia-szabály állításaiból (CLAUDE.md: szabályt sosem LLM-mel).
 */
export function KlinikaiStatusz() {
  const { patient } = usePatient();
  const { state, reload, refresh } = useAsync(() => api.listOsszefoglalok(patient.id), [patient.id]);
  const keszites = useAkcio();
  const utolso = state.status === 'ok' ? state.data[0] : undefined;
  const keszul = utolso?.allapot === 'keszul';

  // Amíg készül, 5 mp-enként csendben frissít (a Python-sorban egymás után futnak a feladatok)
  useEffect(() => {
    if (!keszul) return;
    const t = setInterval(() => {
      refresh().catch(() => {
        /* átmeneti hiba: a következő kör újrapróbálja */
      });
    }, 5000);
    return () => clearInterval(t);
  }, [keszul, refresh]);

  async function keszit() {
    const uj = await keszites.run(() => api.keszitOsszefoglalot(patient.id));
    if (uj) await refresh();
  }

  return (
    <div className="tabbody klinstatus">
      <article className="klinstatus__card" aria-label="Klinikai státusz">
        <div className="row-baseline klinstatus__head">
          <Icon name="spark" size={18} />
          <h2 className="h2 h2--sm">Klinikai státusz</h2>
          <span className="klinstatus__badge">
            <Icon name="spark" size={12} />
            AI összefoglaló
          </span>
          <button
            type="button"
            className="btn btn--sm push-right"
            onClick={() => void keszit()}
            disabled={keszites.busy || keszul || state.status !== 'ok'}
          >
            <Icon name="history" size={14} />
            {keszul ? 'Készül…' : utolso ? 'Összefoglaló frissítése' : 'Összefoglaló készítése'}
          </button>
        </div>
        {keszites.error && (
          <p className="form-error" role="alert">
            {keszites.error}
          </p>
        )}

        <Interakciok />

        {state.status === 'loading' && <Loading what="Összefoglaló" />}
        {state.status === 'error' && <ErrorState error={state.error} onRetry={reload} />}
        {state.status === 'ok' && !utolso && (
          <p className="meta">
            Még nincs összefoglaló. A gomb a beteg elmúlt 6 hónapjának laboreredményeiből, leleteiből, gyógyszereiből és
            allergiáiból készít egyet (a háttérben, kb. 1–2 perc).
          </p>
        )}
        {utolso && <Osszefoglalo o={utolso} />}

        <FoElteresek />
      </article>
    </div>
  );
}

function Osszefoglalo({ o }: { o: KlinikaiOsszefoglalo }) {
  if (o.allapot === 'keszul') {
    return (
      <p className="meta" role="status">
        Az összefoglaló készül ({formatDatumIdo(o.kerve)} óta)… A feldolgozó egyszerre egy feladaton dolgozik, ezért egy
        éppen feldolgozott lelet után kerül sorra.
      </p>
    );
  }
  if (o.allapot === 'hiba') {
    return (
      <p className="form-error" role="alert">
        Az összefoglaló nem készült el: {o.hiba}
      </p>
    );
  }
  return (
    <>
      <p className="meta">
        {formatDatum(o.idoszakKezdete)} – {formatDatum(o.idoszakVege)} · készült{' '}
        {formatDatumIdo(o.elkeszult ?? o.kerve)}
        {o.modell && (
          <>
            {' '}
            · <span className="mono">{o.modell.nev}</span>
          </>
        )}
        {' · '}
        {Object.entries(o.bemenet)
          .filter(([, n]) => n > 0)
          .map(([k, n]) => `${n} ${BEMENET[k] ?? k}`)
          .join(', ')}
      </p>
      {o.elavult && (
        <p className="warnchip" role="status">
          <Icon name="alert" size={14} />
          Az összefoglaló óta új lelet vagy adat érkezett — érdemes frissíteni.
        </p>
      )}
      <p className="klinstatus__text">{o.osszefoglalo}</p>

      {o.javaslatok.length > 0 && (
        <section className="klinstatus__recs" aria-label="Proaktív javaslatok">
          <span className="klinstatus__label klinstatus__label--rec">
            <Icon name="spark" size={12} />
            Proaktív javaslat – javasolt következő vizsgálatok
          </span>
          <ul>
            {o.javaslatok.map((j, i) => (
              <li key={i}>
                <span aria-hidden="true" className="klinstatus__arrow">
                  →
                </span>
                {j}
              </li>
            ))}
          </ul>
        </section>
      )}

      {o.figyelmeztetesek.length > 0 && (
        <ul className="klinstatus__warn" aria-label="Ellenőrizendő">
          {o.figyelmeztetesek.map((w, i) => (
            <li key={i}>
              <Icon name="alert" size={12} /> {w}
            </li>
          ))}
        </ul>
      )}
      <p className="meta klinstatus__disclaimer">
        AI-összefoglaló döntéstámogatásra — nem jóváhagyott orvosi állítás; a döntés az orvosé. Az adatokat a
        Labor-idősor és a Dokumentum fül forrásaival ellenőrizd.
      </p>
    </>
  );
}

/**
 * Gyógyszer-interakciók: a szabálymotor meglévő állításai (MEDICATION forrás, docs/drug-interaction.md),
 * itt nem számolunk újra. Súlyos (kritikus) piros, közepes (figyelmeztetés) borostyán; a tájékoztatók
 * és az orvos által elutasítottak csak számként, a részletek a Gyógyszer és allergia fülön.
 */
function Interakciok() {
  const { patient, allitasok } = usePatient();
  const interakciok = allitasok.filter((a) => a.forrasok.some((f) => f.tipus === 'gyogyszer'));
  if (interakciok.length === 0) return null;
  const elutasitott = (a: (typeof interakciok)[number]) => a.jovahagyas?.dontes === 'elutasitva';
  const fontos = interakciok
    .filter((a) => a.sulyossag !== 'info' && !elutasitott(a))
    .sort((a, b) => (a.sulyossag === 'critical' ? 0 : 1) - (b.sulyossag === 'critical' ? 0 : 1));
  const tobbi = interakciok.length - fontos.length;
  const reszletek = `/betegek/${patient.id}?nezet=gyogyszer`;

  if (fontos.length === 0) {
    return (
      <p className="meta">
        {tobbi} tájékoztató vagy elutasított gyógyszer-interakció — <Link to={reszletek}>részletek</Link>
      </p>
    );
  }
  return (
    <section className="klinstatus__ddi" role="alert" aria-label="Gyógyszer-interakciók">
      <span className="klinstatus__label">
        <Icon name="alert" size={14} strokeWidth={2.4} />
        Gyógyszer-interakció · szabály-alapú (DDInter 2.0)
      </span>
      <ul>
        {fontos.map((a) => (
          <li key={a.id} className={`ddirow sevtone--${a.sulyossag}`}>
            <SulyossagCimke s={a.sulyossag} />
            <span>
              {a.ervenyesSzoveg || a.idezet || a.cim}
              {a.jovahagyas?.dontes === 'jovahagyva' && <span className="meta"> · orvos elfogadta</span>}
            </span>
          </li>
        ))}
      </ul>
      <Link to={reszletek} className="klinstatus__ddilink">
        {tobbi > 0 ? `További ${tobbi} tájékoztató / elutasított · ` : ''}Részletek a Gyógyszer és allergia fülön
      </Link>
    </section>
  );
}

const BEMENET: Record<string, string> = {
  labResult: 'laborérték',
  statement: 'állítás',
  document: 'dokumentum',
  medication: 'gyógyszer',
  allergy: 'allergia',
};

/**
 * Fő eltérések: vizsgálatonként a legutóbbi olyan mérés, amelyről a referencia-szabály állítást
 * hozott létre (tehát az utolsó normál érték óta tartományon kívül van). Determinisztikus.
 */
function FoElteresek() {
  const { patient, allitasok } = usePatient();
  const labor = useAsync(() => api.getLaborIdosor(patient.id), [patient.id, allitasok.length]);
  const szabalyMeresek = useMemo(
    () =>
      new Set(
        allitasok
          .filter((a) => a.claimType === 'szabaly')
          .flatMap((a) => a.forrasok)
          .flatMap((f) => (f.tipus === 'labor' ? [f.laborId] : [])),
      ),
    [allitasok],
  );
  const chipek = labor.state.status === 'ok' ? elteresek(labor.state.data, szabalyMeresek) : [];

  return (
    <section className="klinstatus__dev" aria-label="Fő eltérések">
      <span className="klinstatus__label">
        <Icon name="lock" size={12} />
        Fő eltérések · referencia-szabály · elmúlt 6 hónap
      </span>
      {labor.state.status === 'loading' && <Loading what="Eltérések" />}
      {labor.state.status === 'error' && <ErrorState error={labor.state.error} onRetry={labor.reload} />}
      {labor.state.status === 'ok' && chipek.length === 0 && (
        <p className="meta">Az elmúlt 6 hónapban nincs a referenciatartományon kívüli legutóbbi laborérték.</p>
      )}
      {chipek.length > 0 && (
        <div className="klinstatus__chips">
          {chipek.map((c) => (
            <Link
              key={c.id}
              to={`/betegek/${patient.id}?nezet=labor&meres=${c.id}`}
              className="devchip"
              title={`${c.datum} — mérés részletei`}
            >
              {c.szoveg}
            </Link>
          ))}
        </div>
      )}
    </section>
  );
}

// Ugyanaz az időszak, amiről az összefoglaló szól (elmúlt 6 hónap)
const IDOSZAK_NAP = 183;

function elteresek(idosor: LaborIdosor, szabalyMeresek: Set<number>) {
  const out: { id: number; szoveg: string; datum: string }[] = [];
  const hatar = Date.now() - IDOSZAK_NAP * 864e5;
  const friss = (datum: string) => new Date(datum).getTime() >= hatar;
  for (const p of idosor.parameterek) {
    const m = [...p.meresek]
      .filter((x) => szabalyMeresek.has(x.id) && friss(x.datum))
      .sort((a, b) => new Date(b.datum).getTime() - new Date(a.datum).getTime())[0];
    if (!m) continue;
    const i = irany(p, m.ertek, m.jel);
    const nyil = i === 'felett' ? '↑' : i === 'alatt' ? '↓' : '';
    out.push({
      id: m.id,
      datum: formatDatum(m.datum),
      szoveg: `${p.nev} ${nyil} ${ertekSzoveg(m.ertek, tizedesOf(m.ertek), m.jel)}${p.mertekegyseg ? ' ' + p.mertekegyseg : ''}`,
    });
  }
  for (const p of idosor.szovegesek) {
    const m = [...p.meresek]
      .filter((x) => szabalyMeresek.has(x.id) && friss(x.datum))
      .sort((a, b) => new Date(b.datum).getTime() - new Date(a.datum).getTime())[0];
    if (m) out.push({ id: m.id, datum: formatDatum(m.datum), szoveg: `${p.nev}: ${m.szoveg}` });
  }
  return out;
}
