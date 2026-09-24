import { useRef, useState } from 'react';
import { api } from '../../api';
import { readJsonFile } from '../../api/importok';
import { useAkcio } from '../../lib/useAkcio';
import { usePatient } from '../../patient/PatientContext';
import { Icon } from '../Icon';

type Eredmeny = { index: number; ok: boolean; uzenet: string };

/**
 * Gépi (kinyerő/modell) állítás-kimenet importja — POST /api/statements elemenként.
 * Backend-formátum: tömb vagy { statements: [...] }. Más beteg eleme kimarad, szabály-
 * állítás nem küldhető (azt csak a szabálymotor hozhat létre). Elemenkénti eredmény:
 * a backend a MERRE/HOGY/MIKOR hiányát és a szó szerintiséget is ellenőrzi.
 */
export function AllitasImport({ onClose }: { onClose: () => void }) {
  const { patient, frissit } = usePatient();
  const { busy, error, run } = useAkcio();
  const [eredmeny, setEredmeny] = useState<Eredmeny[] | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);

  async function importal(fajl: File) {
    setEredmeny(null);
    const e = await run(async () => {
      const r = await api.importAllitasok(patient.id, await readJsonFile(fajl));
      if (r.some((x) => x.ok)) await frissit();
      return r;
    });
    if (e) setEredmeny(e);
  }

  const sikeres = eredmeny?.filter((e) => e.ok).length ?? 0;

  return (
    <div className="importbox" role="region" aria-label="Állítások importálása">
      <div className="row-baseline">
        <strong>Állítások importálása</strong>
        <button
          type="button"
          className="iconbtn iconbtn--ghost iconbtn--sm push-right"
          aria-label="Import bezárása"
          onClick={onClose}
        >
          <Icon name="x" size={16} />
        </button>
      </div>
      <p className="meta">
        Kinyerő vagy modell kimenete, backend-formátumban (<span className="mono">StatementCreateRequest</span> tömb).
        A modell-állításhoz kötelező a bizonytalanság és a modellnév + seed + prompt-hash.
      </p>
      <input
        ref={fileRef}
        type="file"
        accept=".json,application/json"
        className="sr-only"
        aria-label="Állítások JSON-fájlja"
        onChange={(ev) => {
          const f = ev.target.files?.[0];
          ev.target.value = '';
          if (f) void importal(f);
        }}
      />
      <div className="actions">
        <button type="button" className="btn btn--sm" onClick={() => fileRef.current?.click()} disabled={busy}>
          <Icon name="upload" size={14} />
          {busy ? 'Import…' : 'JSON-fájl kiválasztása'}
        </button>
      </div>
      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}
      {eredmeny && (
        <div role="status">
          <p className="meta">
            {sikeres} / {eredmeny.length} állítás létrehozva.
          </p>
          <ul className="importlog">
            {eredmeny.map((e) => (
              <li key={e.index} className={e.ok ? undefined : 'form-error'}>
                {e.ok ? `[${e.index}]: ${e.uzenet}` : e.uzenet}
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
