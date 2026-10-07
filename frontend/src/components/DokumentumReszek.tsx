import { useId, useRef, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api';
import { readJsonFile } from '../api/importok';
import type { DokumentumListItem } from '../api/types';
import { formatSzam } from '../lib/format';
import { useAkcio } from '../lib/useAkcio';
import { useAsync } from '../lib/useAsync';
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

/**
 * POST /api/documents (multipart) — csak PDF, csak a fájl: a típust (labor / ambuláns), a
 * klinikai dátumot, a laborsorokat és az állításokat a háttérben futó AI-feldolgozás adja.
 * A hash-t, a méretet és a MIME-típust a szerver számolja.
 */
export function DokumentumFeltoltes({ onDone, onCancel }: { onDone: (id: number) => void; onCancel?: () => void }) {
  const { patient, frissit } = usePatient();
  const [fajl, setFajl] = useState<File | null>(null);
  const [pdf, setPdf] = useState<boolean | null>(null);
  const [tried, setTried] = useState(false);
  const { busy, error, run } = useAkcio();
  const fajlId = useId();

  const hiba = !fajl
    ? 'Válassz fájlt.'
    : fajl.size === 0
      ? 'A fájl üres.'
      : fajl.size > MAX_MERET
        ? 'A fájl nagyobb 20 MB-nál.'
        : pdf === false
          ? 'Csak PDF tölthető fel.'
          : null;

  async function submit(e: FormEvent) {
    e.preventDefault();
    setTried(true);
    if (hiba || !fajl || !pdf) return;
    const d = await run(async () => {
      const uj = await api.feltoltDokumentum({ patientId: patient.id, fajl, tipus: null, klinikaiDatum: null });
      await frissit();
      return uj;
    });
    if (d) onDone(d.id);
  }

  return (
    <form className="tabform" onSubmit={submit} noValidate aria-label="Dokumentum feltöltése">
      <div className="row-baseline">
        <h2 className="h2 h2--sm">Dokumentum feltöltése</h2>
        <span className="meta">PDF, legfeljebb 20 MB — a típust és a dátumot az automatikus feldolgozás adja</span>
      </div>
      <div className="field">
        <label htmlFor={fajlId}>Fájl</label>
        <input
          id={fajlId}
          type="file"
          className="input input--file"
          accept=".pdf,application/pdf"
          onChange={(e) => {
            const f = e.target.files?.[0] ?? null;
            setFajl(f);
            setPdf(null);
            if (f) void isPdf(f).then(setPdf);
          }}
        />
      </div>
      {tried && hiba && <p className="form-error">{hiba}</p>}
      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}
      <div className="actions">
        <button type="submit" className="btn btn--primary" disabled={busy}>
          <Icon name="upload" strokeWidth={2.4} />
          {busy ? 'Feltöltés…' : 'Feltöltés'}
        </button>
        {onCancel && (
          <button type="button" className="btn" onClick={onCancel} disabled={busy}>
            Mégse
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
        <button type="submit" className="btn btn--primary" disabled={busy || !szoveg.trim()}>
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
                disabled={importAkcio.busy}
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
