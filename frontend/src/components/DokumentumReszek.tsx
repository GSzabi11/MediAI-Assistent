import { useId, useRef, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api';
import { readJsonFile } from '../api/importok';
import type { DokumentumListItem } from '../api/types';
import { formatSzam } from '../lib/format';
import { useAkcio } from '../lib/useAkcio';
import { useAsync } from '../lib/useAsync';
import { useIrhato } from '../patient/EllatasKontextus';
import { usePatient } from '../patient/PatientContext';
import { Icon } from './Icon';
import { ErrorState, Loading } from './shell';

const MAX_MERET = 20 * 1024 * 1024; // a backend spring.servlet.multipart.max-file-size

/** A backend is a tartalom első bájtjaiból dönt (nem a kiterjesztésből): PDF = "%PDF-" */
async function isPdf(fajl: File): Promise<boolean> {
  try {
    return (await fajl.slice(0, 5).text()) === '%PDF-';
  } catch {
    return false;
  }
}

type FeltoltesSor = { fajl: File; pdf: boolean | null; allapot: 'var' | 'fut' | 'kesz' | 'hiba'; uzenet?: string };

function sorHiba(s: FeltoltesSor): string | null {
  if (s.fajl.size === 0) return 'a fájl üres';
  if (s.fajl.size > MAX_MERET) return 'nagyobb 20 MB-nál';
  if (s.pdf === false) return 'nem PDF';
  return null;
}

/**
 * POST /api/documents (multipart) — csak PDF, csak a fájl: a típust (labor / ambuláns), a
 * klinikai dátumot, a laborsorokat és az állításokat a háttérben futó AI-feldolgozás adja.
 * A hash-t, a méretet és a MIME-típust a szerver számolja.
 * Egyszerre több fájl is kijelölhető: fájlonként egy kérés, egymás után — egy hibás (pl. duplikátum,
 * 409) nem állítja meg a többit, az eredmény fájlonként látszik.
 */
export function DokumentumFeltoltes({ onDone, onCancel }: { onDone: (id: number) => void; onCancel?: () => void }) {
  const { patient, frissit } = usePatient();
  const [sorok, setSorok] = useState<FeltoltesSor[]>([]);
  const [tried, setTried] = useState(false);
  const [busy, setBusy] = useState(false);
  const [huzas, setHuzas] = useState(false);
  const [osszegzes, setOsszegzes] = useState<string | null>(null);
  const fajlId = useId();

  function valaszt(fajlok: File[]) {
    if (busy) return;
    setTried(false);
    setOsszegzes(null);
    setSorok(fajlok.map((fajl) => ({ fajl, pdf: null, allapot: 'var' })));
    fajlok.forEach((f, i) =>
      void isPdf(f).then((pdf) => setSorok((lista) => lista.map((s, j) => (j === i && s.fajl === f ? { ...s, pdf } : s)))),
    );
  }

  // feltöltendő: érvényes és még nem feltöltött (a sikertelen újrapróbálható)
  const ervenyes = sorok.filter((s) => s.allapot !== 'kesz' && !sorHiba(s));
  const ellenorzesAlatt = sorok.some((s) => s.pdf === null);
  // Kész köteg után nem hiba, hogy nincs több feltöltendő — csak ha a kijelölésben eleve nem volt érvényes PDF
  const vanFeltoltott = sorok.some((s) => s.allapot === 'kesz');
  const hiba =
    sorok.length === 0
      ? 'Válassz fájlt.'
      : ervenyes.length === 0 && !ellenorzesAlatt && !vanFeltoltott
        ? 'Nincs feltölthető PDF.'
        : null;

  const frissitSor = (i: number, uj: Partial<FeltoltesSor>) =>
    setSorok((lista) => lista.map((s, j) => (j === i ? { ...s, ...uj } : s)));

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (busy || ellenorzesAlatt) return;
    setTried(true);
    if (hiba || ervenyes.length === 0) return;
    setBusy(true);
    setOsszegzes(null);
    let utolsoId: number | null = null;
    let volthiba = false;
    let sikeres = 0;
    const osszes = sorok.filter((s) => s.allapot !== 'kesz' && !sorHiba(s)).length;
    for (const [i, s] of sorok.entries()) {
      if (s.allapot === 'kesz' || sorHiba(s)) continue;
      frissitSor(i, { allapot: 'fut', uzenet: undefined });
      try {
        const uj = await api.feltoltDokumentum({ patientId: patient.id, fajl: s.fajl, tipus: null, klinikaiDatum: null });
        utolsoId = uj.id;
        sikeres++;
        frissitSor(i, { allapot: 'kesz' });
      } catch (err) {
        volthiba = true;
        frissitSor(i, { allapot: 'hiba', uzenet: (err as Error).message });
      }
    }
    await frissit().catch(() => {
      /* a lista a következő frissítéskor pótolódik */
    });
    setBusy(false);
    setTried(false);
    setOsszegzes(`${sikeres}/${osszes} feltöltve`);
    // egy fájlnál sikeres feltöltés után megnyitjuk; több fájlnál / hibánál az űrlap marad, fájlonkénti eredménnyel
    if (utolsoId !== null && !volthiba && osszes === 1) onDone(utolsoId);
  }

  return (
    <form
      className={`tabform${huzas ? ' tabform--drop' : ''}`}
      onSubmit={submit}
      noValidate
      aria-label="Dokumentum feltöltése"
      // több fájl ide is behúzható
      onDragOver={(e) => {
        e.preventDefault();
        if (!busy) setHuzas(true);
      }}
      onDragLeave={(e) => {
        if (!e.currentTarget.contains(e.relatedTarget as Node | null)) setHuzas(false);
      }}
      onDrop={(e) => {
        e.preventDefault();
        setHuzas(false);
        valaszt(Array.from(e.dataTransfer.files));
      }}
    >
      <div className="row-baseline">
        <h2 className="h2 h2--sm">Dokumentum feltöltése</h2>
        <span className="meta">
          PDF, fájlonként legfeljebb 20 MB, egyszerre több is (kijelölve vagy ide húzva) — a típust és a dátumot az
          automatikus feldolgozás adja
        </span>
      </div>
      <div className="field">
        <label htmlFor={fajlId}>Fájlok</label>
        <input
          id={fajlId}
          type="file"
          multiple
          className="input input--file"
          accept=".pdf,application/pdf"
          disabled={busy}
          onChange={(e) => valaszt(Array.from(e.target.files ?? []))}
        />
      </div>
      {sorok.length > 1 || sorok.some((s) => s.allapot !== 'var' || sorHiba(s)) ? (
        <ul className="itemlist" aria-label="Kijelölt fájlok">
          {sorok.map((s, i) => {
            const h = sorHiba(s);
            return (
              <li key={i} className="item">
                <div className="item__row">
                  <Icon name={s.allapot === 'kesz' ? 'checkCircle' : h || s.allapot === 'hiba' ? 'alert' : 'doc'} size={16} />
                  <span>{s.fajl.name}</span>
                  <span className={`meta push-right${h || s.allapot === 'hiba' ? ' form-error' : ''}`}>
                    {h ??
                      (s.allapot === 'fut'
                        ? 'feltöltés…'
                        : s.allapot === 'kesz'
                          ? 'feltöltve — feldolgozás a háttérben'
                          : s.allapot === 'hiba'
                            ? s.uzenet
                            : 'feltöltésre vár')}
                  </span>
                </div>
              </li>
            );
          })}
        </ul>
      ) : null}
      {tried && hiba && <p className="form-error">{hiba}</p>}
      {osszegzes && (
        <p className="meta" role="status">
          {osszegzes}
        </p>
      )}
      <div className="actions">
        <button
          type="submit"
          className="btn btn--primary"
          disabled={busy || ellenorzesAlatt || (sorok.length > 0 && ervenyes.length === 0)}
        >
          <Icon name="upload" strokeWidth={2.4} />
          {busy ? 'Feltöltés…' : ervenyes.length > 1 ? `Feltöltés (${ervenyes.length} fájl)` : 'Feltöltés'}
        </button>
        {onCancel && (
          <button type="button" className="btn" onClick={onCancel} disabled={busy}>
            {sorok.some((s) => s.allapot === 'kesz') ? 'Bezárás' : 'Mégse'}
          </button>
        )}
      </div>
    </form>
  );
}

/**
 * PUT /api/documents/{id}/text — egyszer állítható be: az állítások és a RAG-darabok
 * karakterpozícióval hivatkoznak rá, egy utólagos módosítás rossz szövegrészre mutatna.
 */
export function KinyertSzovegForm({ dokumentum, onSaved }: { dokumentum: DokumentumListItem; onSaved: () => void }) {
  const [szoveg, setSzoveg] = useState('');
  const { busy, error, run } = useAkcio();
  const betoltes = useAkcio();
  const mezoId = useId();
  const irhato = useIrhato();

  // Szöveges fájlnál maga a fájl a szöveg: átvehető, nem kell begépelni
  async function atvetel() {
    const blob = await betoltes.run(() => api.letoltFajl(dokumentum.id));
    if (blob) setSzoveg(await blob.text());
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!szoveg.trim()) return;
    const ok = await run(async () => {
      await api.setKinyertSzoveg(dokumentum.id, szoveg);
      return true;
    });
    if (ok) onSaved();
  }

  return (
    <form className="tabform" onSubmit={submit} aria-label="Kinyert szöveg rögzítése">
      <p className="state">
        <Icon name="info" />
        Ehhez a dokumentumhoz még nincs kinyert szöveg — idézet és RAG-darab csak szöveggel hivatkozható.
      </p>
      <div className="field">
        <label htmlFor={mezoId}>Kinyert szöveg (OCR / PDF-szöveg)</label>
        <textarea
          id={mezoId}
          className="textarea mono"
          rows={8}
          value={szoveg}
          onChange={(e) => setSzoveg(e.target.value)}
        />
        <span className="meta">
          Csak egyszer rögzíthető és utána nem módosítható: a hivatkozások karakterpozícióval mutatnak rá.
        </span>
      </div>
      {(error || betoltes.error) && (
        <p className="form-error" role="alert">
          {error ?? betoltes.error}
        </p>
      )}
      <div className="actions">
        <button
          type="submit"
          className="btn btn--primary"
          disabled={busy || !szoveg.trim() || irhato.tiltva}
          title={irhato.title}
        >
          <Icon name="check" strokeWidth={2.6} />
          {busy ? 'Rögzítés…' : 'Szöveg rögzítése'}
        </button>
        {dokumentum.mimeTipus === 'text/plain' && (
          <button type="button" className="btn" onClick={() => void atvetel()} disabled={betoltes.busy}>
            <Icon name="download" size={14} />
            Átvétel a szövegfájlból
          </button>
        )}
      </div>
    </form>
  );
}

/**
 * GET/POST /api/documents/{id}/chunks — a RAG-darabok. A darabolást és az embeddinget
 * a Python végzi; itt a kimenete (JSON) importálható, és a darabok a szövegben
 * kiemelhetők.
 */
export function RagDarabok({ dokumentum }: { dokumentum: DokumentumListItem }) {
  const { patient } = usePatient();
  const [nyitva, setNyitva] = useState(false);
  const { state, reload } = useAsync(() => api.listRagDarabok(dokumentum.id), [dokumentum.id]);
  const importAkcio = useAkcio();
  const irhato = useIrhato();
  const [uzenet, setUzenet] = useState<string | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);

  async function importal(fajl: File) {
    setUzenet(null);
    const db = await importAkcio.run(async () => api.importRagDarabok(dokumentum.id, await readJsonFile(fajl)));
    if (db !== undefined) {
      setUzenet(`${db} RAG-darab importálva.`);
      reload();
    }
  }

  const darabok = state.status === 'ok' ? state.data : [];

  return (
    <section className="subsection" aria-label="RAG-darabok">
      <div className="row-baseline">
        <button type="button" className="linkbtn" aria-expanded={nyitva} onClick={() => setNyitva((n) => !n)}>
          <Icon name={nyitva ? 'chevronUp' : 'chevronDown'} size={14} />
          RAG-darabok {state.status === 'ok' && `(${darabok.length})`}
        </button>
        <span className="meta">a kereséshez (BGE-M3) darabolt szövegrészek</span>
      </div>
      {nyitva && (
        <div className="stack-12">
          {state.status === 'loading' && <Loading what="RAG-darabok" />}
          {state.status === 'error' && <ErrorState error={state.error} onRetry={reload} />}
          {state.status === 'ok' && darabok.length === 0 && (
            <p className="meta">
              Még nincs darabolva. A Python darabolás kimenete importálható:{' '}
              <span className="mono">{'{ embeddingModel, chunks: [{ charStart, charEnd, embedding[1024] }] }'}</span>
            </p>
          )}
          {darabok.length > 0 && (
            <ol className="chunklist">
              {darabok.map((d) => (
                <li key={d.id}>
                  <Link
                    to={`/betegek/${patient.id}?nezet=dokumentum&dok=${dokumentum.id}&kezdet=${d.kezdet}&veg=${d.veg}`}
                    className="mono"
                  >
                    kar. {d.kezdet}–{d.veg}
                  </Link>{' '}
                  <span>{d.szoveg.length > 140 ? `${d.szoveg.slice(0, 140)}…` : d.szoveg}</span>{' '}
                  <span className="meta">
                    {d.embeddingModell} · {formatSzam(d.veg - d.kezdet)} kar.
                  </span>
                </li>
              ))}
            </ol>
          )}
          {state.status === 'ok' && darabok.length === 0 && dokumentum.vanSzoveg && (
            <div className="actions">
              <input
                ref={fileRef}
                type="file"
                accept=".json,application/json"
                className="sr-only"
                aria-label="RAG-darabok JSON-fájlja"
                onChange={(e) => {
                  const f = e.target.files?.[0];
                  e.target.value = '';
                  if (f) void importal(f);
                }}
              />
              <button
                type="button"
                className="btn btn--sm"
                onClick={() => fileRef.current?.click()}
                disabled={importAkcio.busy || irhato.tiltva}
                title={irhato.title}
              >
                <Icon name="upload" size={14} />
                {importAkcio.busy ? 'Import…' : 'Darabok importálása (JSON)'}
              </button>
            </div>
          )}
          {importAkcio.error && (
            <p className="form-error" role="alert">
              {importAkcio.error}
            </p>
          )}
          {uzenet && (
            <p className="meta" role="status">
              {uzenet}
            </p>
          )}
        </div>
      )}
    </section>
  );
}
