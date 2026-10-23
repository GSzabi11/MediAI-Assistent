import { useCallback, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api';
import type { Kortortenet, KortortenetElem } from '../api/types';
import { formatDatumIdo } from '../lib/format';
import { useAkcio } from '../lib/useAkcio';
import { useAsync } from '../lib/useAsync';
import { usePatient } from '../patient/PatientContext';
import { Icon } from './Icon';
import { ErrorState, Loading } from './shell';

/**
 * Medical history: az AI anamnézise + kórtörténeti összefoglalója (szerkeszthető, DOCX-be exportálható)
 * és a kórtörténeti idővonal — minden elem a forrás-dokumentumára mutat (docs/medical-history.md).
 * Külön tábla, nem állítás; az orvos szerkesztése mellé kerül, az AI eredetije megmarad.
 */
export function KortortenetFul() {
  const { patient } = usePatient();
  const { state, reload, refresh, setData } = useAsync(() => api.listKortortenetek(patient.id), [patient.id]);
  const keszites = useAkcio();
  const utolso = state.status === 'ok' ? state.data[0] : undefined;
  const keszul = utolso?.allapot === 'keszul';
  // az utolsó elkészült (a készülő / hibás újragenerálás alatt is ez látszik)
  const kesz = state.status === 'ok' ? state.data.find((k) => k.allapot === 'kesz') : undefined;

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

  async function keszit() {
    const uj = await keszites.run(() => api.keszitKortortenetet(patient.id));
    if (uj) await refresh();
  }

  const frissult = (k: Kortortenet) => setData((lista) => lista.map((x) => (x.id === k.id ? k : x)));
  // a szerkesztő mentetlen tartalma — az export előbb elmenti
  const mentetlen = useRef<Mentetlen | null>(null);
  const regisztral = useCallback((m: Mentetlen | null) => {
    mentetlen.current = m;
  }, []);

  return (
    <div className="tabbody kortortenet">
      <header className="kortortenet__head">
        <span className="kortortenet__icon" aria-hidden="true">
          <Icon name="doc" size={22} />
        </span>
        <div className="kortortenet__title">
          <h2 className="h2">Medical History – Kórtörténeti összefoglaló</h2>
          <p className="meta">A feltöltött dokumentumokból generált anamnézis és idővonal</p>
        </div>
        <div className="kortortenet__actions">
          <button
            type="button"
            className="btn"
            onClick={() => void keszit()}
            disabled={keszites.busy || keszul || state.status !== 'ok'}
          >
            <Icon name="history" size={16} />
            {keszul ? 'Készül…' : kesz ? 'Újragenerálás' : 'Készítés'}
          </button>
          {kesz && <ExportGomb k={kesz} mentetlen={() => mentetlen.current} onSaved={frissult} />}
        </div>
      </header>
      {keszites.error && (
        <p className="form-error" role="alert">
          {keszites.error}
        </p>
      )}

      {state.status === 'loading' && <Loading what="Kórtörténet" />}
      {state.status === 'error' && <ErrorState error={state.error} onRetry={reload} />}
      {state.status === 'ok' && !utolso && (
        <p className="meta">
          Még nincs kórtörténeti összefoglaló. A gomb a beteg összes feldolgozott dokumentumából készít egyet (a
          háttérben; az első alkalommal minden leletből kivonat készül, ez hosszabb ideig tarthat).
        </p>
      )}
      {keszul && <Haladas k={utolso} />}
      {utolso?.allapot === 'hiba' && (
        <p className="form-error" role="alert">
          A kórtörténet nem készült el: {utolso.hiba}
        </p>
      )}

      {kesz && <KeszKortortenet key={kesz.id} k={kesz} regisztral={regisztral} onSaved={frissult} />}
    </div>
  );
}

/**
 * Készülés közben: a feldolgozó aktuális lépése ("Dokumentum-kivonat 5/16") sávval, és az eltelt idő.
 * Lépés nélkül (még a sorban vár, vagy régi docproc) csak az időt mutatja.
 */
function Haladas({ k }: { k: Kortortenet }) {
  const [most, setMost] = useState(() => Date.now());
  useEffect(() => {
    const t = setInterval(() => setMost(Date.now()), 1000);
    return () => clearInterval(t);
  }, []);
  const perc = Math.max(0, Math.floor((most - new Date(k.kerve).getTime()) / 60000));
  const h = k.haladas;
  const arany = h?.lepes && h.osszes ? Math.min(1, (h.lepes - 1) / h.osszes) : null;
  return (
    <div className="kortortenet__progress" role="status" aria-live="polite">
      <p className="meta">
        {h ? h.uzenet : 'Sorban vár — a feldolgozó egyszerre egy feladaton dolgozik'}
        {' · '}
        {perc} perce indult ({formatDatumIdo(k.kerve)})
      </p>
      {arany !== null && (
        <progress
          className="kortortenet__bar"
          max={h!.osszes!}
          value={h!.lepes! - 1}
          aria-label={`${h!.lepes! - 1} / ${h!.osszes} lépés kész`}
        />
      )}
      {arany !== null && (
        <p className="meta">
          {h!.lepes! - 1} / {h!.osszes} lépés kész. A medgemma:27b-vel egy lépés néhány perc; az elkészült
          dokumentum-kivonatok megmaradnak, a következő generálás gyorsabb.
        </p>
      )}
    </div>
  );
}

type Mentetlen = { id: number; html: () => string };
type SzerkesztoProps = { k: Kortortenet; regisztral: (m: Mentetlen | null) => void; onSaved: (k: Kortortenet) => void };

function KeszKortortenet({ k, regisztral, onSaved }: SzerkesztoProps) {
  return (
    <>
      <p className="meta">
        készült {formatDatumIdo(k.elkeszult ?? k.kerve)}
        {k.modell && (
          <>
            {' '}
            · <span className="mono">{k.modell.nev}</span>
          </>
        )}
        {' · '}
        {Object.entries(k.bemenet)
          .filter(([, n]) => n > 0)
          .map(([kulcs, n]) => `${n} ${BEMENET[kulcs] ?? kulcs}`)
          .join(', ')}
        {k.szerkesztve && <> · orvos szerkesztette: {formatDatumIdo(k.szerkesztve)}</>}
      </p>
      {k.elavult && (
        <p className="warnchip" role="status">
          <Icon name="alert" size={14} />
          Az összefoglaló óta új lelet vagy adat érkezett — érdemes újragenerálni.
        </p>
      )}
      {k.figyelmeztetesek.length > 0 && (
        <details className="kortortenet__warn">
          <summary>
            <Icon name="alert" size={14} /> Ellenőrizendő ({k.figyelmeztetesek.length})
          </summary>
          <ul>
            {k.figyelmeztetesek.map((w, i) => (
              <li key={i}>{w}</li>
            ))}
          </ul>
        </details>
      )}

      {/* mentés után újraindul (a tárolt szöveggel, "Mentve" állapotban) */}
      <Szerkeszto key={k.szerkesztve ?? 'ai'} k={k} regisztral={regisztral} onSaved={onSaved} />
      <Idovonal elemek={k.idovonal} />

      <p className="meta klinstatus__disclaimer">
        AI által előkészített összefoglaló döntéstámogatásra — nem jóváhagyott orvosi állítás; a javaslatok csak
        javaslatok, a döntés az orvosé. Az idővonal elemeit a forrás-dokumentummal ellenőrizd.
      </p>
    </>
  );
}

const BEMENET: Record<string, string> = {
  document: 'dokumentum',
  labResult: 'laborérték',
  medication: 'gyógyszer',
  allergy: 'allergia',
};

function ExportGomb({
  k,
  mentetlen,
  onSaved,
}: {
  k: Kortortenet;
  mentetlen: () => Mentetlen | null;
  onSaved: (k: Kortortenet) => void;
}) {
  const { patient } = usePatient();
  const letoltes = useAkcio();

  // A fájl fetch-csel jön (X-User-Id fejléccel → a naplóban EXPORT-ként látszik, ki vitte ki).
  // Mentetlen szerkesztést előbb elment: az export a tárolt szöveget teszi a fájlba.
  async function exportal() {
    const blob = await letoltes.run(async () => {
      const m = mentetlen();
      if (m?.id === k.id) onSaved(await api.szerkesztKortortenetet(k.id, m.html()));
      return api.letoltKortortenetDocx(k.id);
    });
    if (!blob) return;
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `Medical_history_${patient.nev.replace(/\s+/g, '_')}_${new Date().toISOString().slice(0, 10)}.docx`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 60_000);
  }

  return (
    <>
      <button type="button" className="btn btn--primary" onClick={() => void exportal()} disabled={letoltes.busy}>
        <Icon name="download" size={16} />
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

const ESZKOZOK: { parancs: string; cimke: string; jel: string; className?: string }[] = [
  { parancs: 'bold', cimke: 'Félkövér', jel: 'B', className: 'rte__b' },
  { parancs: 'italic', cimke: 'Dőlt', jel: 'I', className: 'rte__i' },
  { parancs: 'underline', cimke: 'Aláhúzott', jel: 'U', className: 'rte__u' },
  { parancs: 'insertUnorderedList', cimke: 'Felsorolás', jel: '•≡' },
  { parancs: 'insertOrderedList', cimke: 'Számozott lista', jel: '1≡' },
];

/**
 * A dokumentum alapból olvasható nézetben jelenik meg; a "Szerkesztés" gomb nyitja meg a szerkesztőt.
 * Egyszerű rich text szerkesztő (contentEditable + execCommand): félkövér, dőlt, aláhúzott, felsorolás,
 * számozott lista. A mentett HTML-t a Spring szűri (csak ezek az elemek maradnak meg).
 */
function Szerkeszto({ k, regisztral, onSaved }: SzerkesztoProps) {
  const ref = useRef<HTMLDivElement>(null);
  const [modositott, setModositott] = useState(false);
  const [eredeti, setEredeti] = useState(false);
  // olvasási nézet az alapértelmezés; mentés után a key újraindítja → ismét olvasási nézet
  const [szerkeszt, setSzerkeszt] = useState(false);
  const mentes = useAkcio();

  // A tartalmat csak a szerkesztő megnyitásakor írjuk be: gépelés közben a React nem nyúl hozzá
  useEffect(() => {
    if (szerkeszt && ref.current) ref.current.innerHTML = k.html;
  }, [k.html, szerkeszt]);

  useEffect(() => {
    regisztral(modositott ? { id: k.id, html: () => ref.current?.innerHTML ?? k.html } : null);
    return () => regisztral(null);
  }, [modositott, k.id, k.html, regisztral]);

  function parancs(p: string) {
    ref.current?.focus();
    // Enter után <p> legyen, ne <div> (a Word-export és a szűrő is a bekezdést várja)
    document.execCommand('defaultParagraphSeparator', false, 'p');
    document.execCommand(p);
    setModositott(true);
  }

  async function ment() {
    const html = ref.current?.innerHTML ?? '';
    const uj = await mentes.run(() => api.szerkesztKortortenetet(k.id, html));
    if (uj) onSaved(uj);
  }

  function megse() {
    if (modositott && !window.confirm('Elveted a nem mentett módosításokat?')) return;
    setModositott(false);
    setSzerkeszt(false);
  }

  function visszaallit() {
    if (ref.current) ref.current.innerHTML = k.aiHtml;
    setModositott(true);
  }

  return (
    <section className="kortortenet__card" aria-label="Anamnézis és kórtörténeti összefoglaló">
      {!szerkeszt && (
        <div className="rte">
          <div className="rte__bar">
            <span className="push-right" />
            {k.szerkesztve && (
              <button type="button" className="btn btn--sm" onClick={() => setEredeti((v) => !v)}>
                {eredeti ? 'AI eredeti elrejtése' : 'AI eredeti megtekintése'}
              </button>
            )}
            <button type="button" className="btn btn--sm btn--primary" onClick={() => setSzerkeszt(true)}>
              <Icon name="pencil" size={14} />
              Szerkesztés
            </button>
          </div>
          {/* a Spring által előállított (escape-elt) vagy szűrt HTML (MedicalHistoryHtml) */}
          <div className="rte__body kortortenet__doc" dangerouslySetInnerHTML={{ __html: k.html }} />
        </div>
      )}
      {szerkeszt && (
        <div className="rte">
          <div className="rte__bar" role="toolbar" aria-label="Formázás">
            {ESZKOZOK.map((e, i) => (
              <button
                key={e.parancs}
                type="button"
                className={`rte__btn ${e.className ?? ''}${i === 3 ? ' rte__btn--sep' : ''}`}
                title={e.cimke}
                aria-label={e.cimke}
                // mousedown: a kijelölés ne vesszen el a gombra kattintáskor
                onMouseDown={(ev) => {
                  ev.preventDefault();
                  parancs(e.parancs);
                }}
              >
                {e.jel}
              </button>
            ))}
            <span className="push-right" />
            {k.szerkesztve && (
              <button type="button" className="btn btn--sm" onClick={() => setEredeti((v) => !v)}>
                {eredeti ? 'AI eredeti elrejtése' : 'AI eredeti megtekintése'}
              </button>
            )}
            <button type="button" className="btn btn--sm" onClick={visszaallit} title="Az AI szövegére">
              Visszaállítás
            </button>
            <button type="button" className="btn btn--sm" onClick={megse}>
              Mégse
            </button>
            <button
              type="button"
              className="btn btn--sm btn--primary"
              onClick={() => void ment()}
              disabled={!modositott || mentes.busy}
            >
              <Icon name="check" size={14} />
              {mentes.busy ? 'Mentés…' : modositott ? 'Mentés' : 'Mentve'}
            </button>
          </div>
          <div
            ref={ref}
            className="rte__body kortortenet__doc"
            contentEditable
            suppressContentEditableWarning
            role="textbox"
            aria-multiline="true"
            aria-label="Anamnézis és kórtörténeti összefoglaló (szerkeszthető)"
            spellCheck
            onInput={() => setModositott(true)}
          />
        </div>
      )}
      {mentes.error && (
        <p className="form-error" role="alert">
          {mentes.error}
        </p>
      )}
      {eredeti && (
        <div className="kortortenet__orig">
          <span className="klinstatus__label">
            <Icon name="spark" size={12} /> Az AI eredeti szövege (nem szerkeszthető)
          </span>
          {/* a Spring által előállított, escape-elt HTML (MedicalHistoryHtml.render) */}
          <div className="kortortenet__doc" dangerouslySetInnerHTML={{ __html: k.aiHtml }} />
        </div>
      )}
    </section>
  );
}

function Idovonal({ elemek }: { elemek: KortortenetElem[] }) {
  const { patient } = usePatient();
  const [nyitva, setNyitva] = useState(true);
  return (
    <section className="kortortenet__card" aria-label="Kórtörténeti idővonal">
      <button type="button" className="kortortenet__tlhead" aria-expanded={nyitva} onClick={() => setNyitva((v) => !v)}>
        <Icon name="clock" size={18} />
        <span className="kortortenet__tltitle">Kórtörténeti idővonal</span>
        <span className="kortortenet__count">{elemek.length}</span>
        <span className="push-right" aria-hidden="true">
          <Icon name={nyitva ? 'chevronUp' : 'chevronDown'} size={16} />
        </span>
      </button>
      {nyitva && (
        <ol className="timeline">
          {/* a legfrissebb felül (a backend időrendben adja) */}
          {[...elemek].reverse().map((e, i) => (
            <li key={i} className={`timeline__item${e.jeloles ? ' timeline__item--flag' : ''}`}>
              <span className="timeline__dot" aria-hidden="true" />
              <span className="timeline__date">{e.datum}</span>
              <span className="timeline__title">
                {e.cim}
                {e.hely && <span className="timeline__place"> · {e.hely}</span>}
              </span>
              {e.szoveg && <span className="timeline__text">{e.szoveg}</span>}
              {e.jeloles && (
                <span className="warnchip" role="note">
                  <Icon name="alert" size={12} /> Ellenőrizendő: {e.jeloles}
                </span>
              )}
              <Link
                className="timeline__src"
                to={`/betegek/${patient.id}?nezet=dokumentum&dok=${e.dokumentumId}${e.fajta === 'labor' ? '&pdf=1' : ''}`}
              >
                <Icon name="quote" size={12} />
                {e.fajta === 'elozmeny' ? 'Forrás: anamnézis a leletben' : 'Forrás-dokumentum'}
              </Link>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}
