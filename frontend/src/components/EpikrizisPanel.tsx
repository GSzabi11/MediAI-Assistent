import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api';
import type { DokumentumListItem, Ellatas, Epikrizis, EpikrizisForras } from '../api/types';
import { formatDatum, formatDatumIdo } from '../lib/format';
import { useAkcio } from '../lib/useAkcio';
import { useAsync } from '../lib/useAsync';
import { usePatient } from '../patient/PatientContext';
import { DokSzerkeszto, type Mentetlen } from './DokSzerkeszto';
import { Icon } from './Icon';
import { Haladas } from './KortortenetFul';
import { ErrorState, Loading } from './shell';

const DOK_TIPUS: Record<string, string> = { labor: 'Laborlelet', ambulans: 'Ambuláns lap', kepalkotas: 'Képalkotó lelet' };

// ÉÉÉÉ-HH-NN budapesti idő szerint (a backend EpicrisisFactsBuilder.inCarePeriod-jával azonos szabály)
const budapestNap = (d: Date) => new Intl.DateTimeFormat('sv-SE', { timeZone: 'Europe/Budapest' }).format(d);

/** Az ellátás időszakába eső lelet: klinikai dátum a kezdet és a zárás (nyitottnál a mai nap) napja között */
function idoszakban(d: DokumentumListItem, e: Ellatas) {
  if (!d.klinikaiDatum) return false;
  const kezdet = budapestNap(new Date(e.kezdet));
  const veg = budapestNap(e.veg ? new Date(e.veg) : new Date());
  return d.klinikaiDatum >= kezdet && d.klinikaiDatum <= veg;
}

const valaszthato = (d: DokumentumListItem) => d.allapot === 'kesz' && d.tipus !== null && d.klinikaiDatum !== null;

/**
 * Epikrízis egy ellátáshoz (docs/epicrisis.md): az orvos által kiválasztott leletekből, az ellátás
 * hangfelvételeinek átiratából és az aktuális gyógyszerekből. A fejléc (Beteg, TAJ, Dátum) az adatbázisból
 * jön; a három szakasz az AI javaslata — szerkeszthető, az eredeti megmarad, DOCX-be exportálható.
 */
export function EpikrizisPanel({ ellatas }: { ellatas: Ellatas }) {
  const { patient } = usePatient();
  const { state, reload, refresh, setData } = useAsync(() => api.listEpikrizisek(patient.id), [patient.id]);
  const sajat = useMemo(
    () => (state.status === 'ok' ? state.data.filter((e) => e.ellatasId === ellatas.id) : []),
    [state, ellatas.id],
  );
  const utolso = sajat[0];
  const keszul = utolso?.allapot === 'keszul';
  const kesz = sajat.find((e) => e.allapot === 'kesz');
  const keszites = useAkcio();

  async function keszit() {
    const e = await keszites.run(() => api.keszitEpikrizist(ellatas.id));
    if (e) await refresh();
  }

  // Amíg készül, 5 mp-enként csendben frissít
  useEffect(() => {
    if (!keszul) return;
    const t = setInterval(() => {
      refresh().catch(() => {
        /* átmeneti hiba: a következő kör újrapróbálja */
      });
    }, 5000);
    return () => clearInterval(t);
  }, [keszul, refresh]);

  const frissult = (e: Epikrizis) => setData((lista) => lista.map((x) => (x.id === e.id ? e : x)));
  const mentetlen = useRef<Mentetlen | null>(null);
  const regisztral = useCallback((m: Mentetlen | null) => {
    mentetlen.current = m;
  }, []);

  return (
    <div className="item__sub epikrizis">
      <div className="row-baseline">
        <h3 className="h2 h2--sm">Epikrízis</h3>
        <div className="actions push-right">
          <button
            type="button"
            className="btn btn--sm"
            onClick={() => void keszit()}
            disabled={keszites.busy || keszul || state.status !== 'ok'}
          >
            <Icon name="doc" size={14} />
            {keszul ? 'Készül…' : kesz ? 'Újragenerálás' : 'Epikrízis készítése'}
          </button>
          {kesz && <ExportGomb e={kesz} mentetlen={() => mentetlen.current} onSaved={frissult} />}
        </div>
      </div>

      {state.status === 'loading' && <Loading what="Epikrízis" />}
      {state.status === 'error' && <ErrorState error={state.error} onRetry={reload} />}
      {keszites.error && (
        <p className="form-error" role="alert">
          {keszites.error}
        </p>
      )}
      <EllatasAnyagai ellatas={ellatas} />
      {state.status === 'ok' && !utolso && (
        <p className="meta">Még nincs epikrízis ehhez az ellátáshoz.</p>
      )}
      {keszul && <Haladas k={utolso} />}
      {utolso?.allapot === 'hiba' && (
        <p className="form-error" role="alert">
          Az epikrízis nem készült el: {utolso.hiba}
        </p>
      )}
      {kesz && <KeszEpikrizis key={kesz.id} e={kesz} regisztral={regisztral} onSaved={frissult} />}
    </div>
  );
}

/** Miből készül: az ellátás időszakába eső leletek és az ellátás kész felvételei (+ az aktuális gyógyszerek) */
function EllatasAnyagai({ ellatas }: { ellatas: Ellatas }) {
  const { dokumentumok, felvetelek } = usePatient();
  const leletek = dokumentumok
    .filter((d) => valaszthato(d) && idoszakban(d, ellatas))
    .sort((a, b) => (b.klinikaiDatum ?? '').localeCompare(a.klinikaiDatum ?? '') || b.id - a.id);
  const atiratok = felvetelek.filter((f) => f.ellatasId === ellatas.id && f.allapot === 'kesz');
  return (
    <p className="meta">
      Az epikrízis az ellátás {leletek.length} leletéből
      {leletek.length > 0 &&
        ` (${leletek.map((d) => `${d.klinikaiDatum} ${DOK_TIPUS[d.tipus ?? ''] ?? 'lelet'}`).join(', ')})`}
      , {atiratok.length} kész hangfelvételének átiratából és a beteg aktuális gyógyszerlistájából készül. Az ellátás
      leletei: amelyek klinikai dátuma az ellátás időszakába esik.
    </p>
  );
}

type KeszProps = { e: Epikrizis; regisztral: (m: Mentetlen | null) => void; onSaved: (e: Epikrizis) => void };

function KeszEpikrizis({ e, regisztral, onSaved }: KeszProps) {
  return (
    <>
      <p className="meta">
        készült {formatDatumIdo(e.elkeszult ?? e.kerve)}
        {e.modell && (
          <>
            {' '}
            · <span className="mono">{e.modell.nev}</span>
          </>
        )}
        {' · '}
        {Object.entries(e.bemenet)
          .filter(([, n]) => n > 0)
          .map(([kulcs, n]) => `${n} ${BEMENET[kulcs] ?? kulcs}`)
          .join(', ')}
        {e.szerkesztve && <> · orvos szerkesztette: {formatDatumIdo(e.szerkesztve)}</>}
      </p>
      {e.figyelmeztetesek.length > 0 && (
        <details className="kortortenet__warn">
          <summary>
            <Icon name="alert" size={14} /> Ellenőrizendő ({e.figyelmeztetesek.length})
          </summary>
          <ul>
            {e.figyelmeztetesek.map((w, i) => (
              <li key={i}>{w}</li>
            ))}
          </ul>
        </details>
      )}
      <DokSzerkeszto
        key={e.szerkesztve ?? 'ai'}
        id={e.id}
        html={e.html}
        aiHtml={e.aiHtml}
        szerkesztve={e.szerkesztve}
        ment={async (html) => onSaved(await api.szerkesztEpikrizist(e.id, html))}
        regisztral={regisztral}
        cimke="Epikrízis"
        fejlec={
          // az adatbázisból, nem a modelltől — nem szerkeszthető
          <div className="kortortenet__doc epikrizis__fejlec">
            <p>Beteg: {e.fejlec.beteg}</p>
            <p>TAJ: {e.fejlec.taj}</p>
            <p>Dátum: {e.fejlec.datum}</p>
          </div>
        }
      />
      <Forrasok e={e} />
      <p className="meta klinstatus__disclaimer">
        AI által előkészített epikrízis döntéstámogatásra — nem jóváhagyott orvosi állítás; a javaslatok csak
        javaslatok, a döntés az orvosé. A megállapításokat a forrásukkal ellenőrizd.
      </p>
    </>
  );
}

const BEMENET: Record<string, string> = {
  document: 'lelet',
  labResult: 'laborérték',
  recording: 'hangfelvétel',
  medication: 'gyógyszer',
  allergy: 'allergia',
};

/** A kritikus megállapítások és diagnózisok forrásai (az AI eredetijéből) */
function Forrasok({ e }: { e: Epikrizis }) {
  const [nyitva, setNyitva] = useState(false);
  return (
    <section className="kortortenet__card" aria-label="Források">
      <button type="button" className="kortortenet__tlhead" aria-expanded={nyitva} onClick={() => setNyitva((v) => !v)}>
        <Icon name="quote" size={18} />
        <span className="kortortenet__tltitle">Források (AI eredeti)</span>
        <span className="kortortenet__count">{e.megallapitasok.length + e.diagnozisok.length}</span>
        <span className="push-right" aria-hidden="true">
          <Icon name={nyitva ? 'chevronUp' : 'chevronDown'} size={16} />
        </span>
      </button>
      {nyitva && (
        <ol className="timeline">
          {e.megallapitasok.map((m, i) => (
            <li key={`m${i}`} className={`timeline__item${m.jeloles ? ' timeline__item--flag' : ''}`}>
              <span className="timeline__dot" aria-hidden="true" />
              <span className="timeline__title">Kritikus megállapítás</span>
              <span className="timeline__text">{m.szoveg}</span>
              {m.idezet && <span className="timeline__text meta">„{m.idezet}”</span>}
              {m.jeloles && (
                <span className="warnchip" role="note">
                  <Icon name="alert" size={12} /> Ellenőrizendő: {m.jeloles}
                </span>
              )}
              <ForrasLink f={m} />
            </li>
          ))}
          {e.diagnozisok.map((d, i) => (
            <li key={`d${i}`} className="timeline__item">
              <span className="timeline__dot" aria-hidden="true" />
              <span className="timeline__title">Diagnózis</span>
              <span className="timeline__text">
                {d.bno && <b>{d.bno} – </b>}
                {d.nev}
              </span>
              <ForrasLink f={d} />
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}

function ForrasLink({ f }: { f: EpikrizisForras }) {
  const { patient } = usePatient();
  if (f.dokumentumId !== null) {
    return (
      <Link className="timeline__src" to={`/betegek/${patient.id}?nezet=dokumentum&dok=${f.dokumentumId}`}>
        <Icon name="quote" size={12} />
        Forrás-lelet
      </Link>
    );
  }
  if (f.felvetelId !== null) {
    return (
      <Link
        className="timeline__src"
        to={`/betegek/${patient.id}?nezet=atirat&felvetel=${f.felvetelId}&turn=${f.turnIds.join(',')}`}
      >
        <Icon name="mic" size={12} />
        Forrás: átirat #{f.felvetelId}
      </Link>
    );
  }
  return <span className="meta">nincs forrás</span>;
}

function ExportGomb({
  e,
  mentetlen,
  onSaved,
}: {
  e: Epikrizis;
  mentetlen: () => Mentetlen | null;
  onSaved: (e: Epikrizis) => void;
}) {
  const { patient } = usePatient();
  const letoltes = useAkcio();

  // Mentetlen szerkesztést előbb elment: az export a tárolt szöveget teszi a fájlba
  async function exportal() {
    const blob = await letoltes.run(async () => {
      const m = mentetlen();
      if (m?.id === e.id) onSaved(await api.szerkesztEpikrizist(e.id, m.html()));
      return api.letoltEpikrizisDocx(e.id);
    });
    if (!blob) return;
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `Epikrizis_${patient.nev.replace(/\s+/g, '_')}_${formatDatum(e.elkeszult ?? e.kerve).replace(/[.\s]+/g, '-')}.docx`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 60_000);
  }

  return (
    <>
      <button type="button" className="btn btn--sm btn--primary" onClick={() => void exportal()} disabled={letoltes.busy}>
        <Icon name="download" size={14} />
        Exportálás (DOCX)
      </button>
      {letoltes.error && (
        <p className="form-error" role="alert">
          {letoltes.error}
        </p>
      )}
    </>
  );
}
