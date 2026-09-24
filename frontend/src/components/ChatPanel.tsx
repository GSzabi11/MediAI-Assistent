import { useEffect, useId, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api';
import { NotAvailableError, type ChatUzenet, type ChatValasz } from '../api/types';
import { formatTaj } from '../lib/format';
import { usePatient } from '../patient/PatientContext';
import { SourceChip, TypeTag, UncertaintyMeter } from './allitas/parts';
import { Icon } from './Icon';

const GYORSKERDESEK = [
  'Foglald össze a beteg jelenlegi állapotát.',
  'Van gyógyszer-interakció vagy kockázat?',
  'Foglald össze a felvételi beszélgetést.',
  'Mi változott az utolsó zárójelentés óta?',
];

/**
 * Klinikai asszisztens (AI-chat). A válasz minden pontja saját forrás-chipet
 * kap; forrás nélküli pont külön jelölést. Érintett szabályok lakatos
 * hivatkozásként jelennek meg, nem olvadnak bele az AI-szövegbe.
 *
 * A szülő `key={patient.id}`-vel rendereli, így betegváltáskor a beszélgetés
 * garantáltan törlődik.
 */
export function ChatPanel({ onCollapse }: { onCollapse: () => void }) {
  const { patient, dokumentumok, felvetelek } = usePatient();
  const [uzenetek, setUzenetek] = useState<ChatUzenet[]>([]);
  const [kerdes, setKerdes] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [qqOpen, setQqOpen] = useState(false);
  const inputId = useId();
  const endRef = useRef<HTMLDivElement>(null);

  useEffect(() => endRef.current?.scrollIntoView({ block: 'end', behavior: 'smooth' }), [uzenetek.length, busy]);

  async function kuld(szoveg: string) {
    const q = szoveg.trim();
    if (!q || busy) return;
    setUzenetek((u) => [...u, { id: crypto.randomUUID(), szerep: 'orvos', szoveg: q }]);
    setKerdes('');
    setQqOpen(false);
    setBusy(true);
    setError(null);
    try {
      const valasz = await api.kerdez(patient.id, q);
      if (valasz.patientId !== patient.id) throw new Error('A válasz nem az aktív beteghez tartozik — elvetve.');
      setUzenetek((u) => [...u, { id: valasz.id, szerep: 'ai', valasz }]);
    } catch (e) {
      setError(
        e instanceof NotAvailableError ? 'A klinikai asszisztenshez még nincs backend-végpont.' : (e as Error).message,
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <aside className="chat" aria-label="Klinikai asszisztens">
      <div className="chat__head">
        <div className="row-inline row-gap-10">
          <h2 className="h2 h2--sm">Klinikai asszisztens</h2>
          <TypeTag type="modell" />
          <button
            type="button"
            className="iconbtn iconbtn--ghost iconbtn--sm push-right"
            aria-label="Chat összecsukása"
            onClick={onCollapse}
          >
            <Icon name="expandRight" size={18} />
          </button>
        </div>
        <div className="pmatch pmatch--sm">
          <Icon name="checkCircle" size={14} />
          <span>
            Kontextus: <strong>{patient.nev}</strong> · <span className="mono">TAJ {formatTaj(patient.taj)}</span> ·{' '}
            {dokumentumok.length} dok., {felvetelek.length} felvétel
          </span>
        </div>
      </div>

      <div className="chat__log" aria-live="polite">
        {uzenetek.length === 0 && !busy && (
          <p className="empty">Kérdezz a beteg állapotáról. A válasz minden állítása forrásra hivatkozik.</p>
        )}
        {uzenetek.map((u) =>
          u.szerep === 'orvos' ? (
            <p key={u.id} className="chat-q">
              {u.szoveg}
            </p>
          ) : (
            u.valasz && <Valasz key={u.id} v={u.valasz} />
          ),
        )}
        {busy && (
          <p className="state" role="status">
            Válasz készül…
          </p>
        )}
        {error && (
          <p className="form-error" role="alert">
            {error}
          </p>
        )}
        <div ref={endRef} />
      </div>

      <div className="chat__qq">
        <button type="button" className="qq" aria-expanded={qqOpen} onClick={() => setQqOpen((o) => !o)}>
          <span className="accent">
            <Icon name="spark" size={14} />
          </span>
          <strong>Gyorskérdések ({GYORSKERDESEK.length})</strong>
          <span className="meta">a beteg adataihoz igazítva</span>
          <span className="push-right">
            <Icon name={qqOpen ? 'chevronUp' : 'chevronDown'} size={18} />
          </span>
        </button>
        {qqOpen && (
          <ul className="qq-list">
            {GYORSKERDESEK.map((q) => (
              <li key={q}>
                <button type="button" className="qq" onClick={() => void kuld(q)} disabled={busy}>
                  <span className="accent">
                    <Icon name="chevronRight" size={14} strokeWidth={2.4} />
                  </span>
                  {q}
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>

      <form
        className="chat__form"
        onSubmit={(e) => {
          e.preventDefault();
          void kuld(kerdes);
        }}
      >
        <label htmlFor={inputId} className="label-strong">
          Kérdés a beteg állapotáról
        </label>
        <div className="row-gap-8">
          <textarea
            id={inputId}
            className="textarea grow"
            rows={2}
            value={kerdes}
            placeholder="pl. Milyen allergiák vannak dokumentálva?"
            onChange={(e) => setKerdes(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                void kuld(kerdes);
              }
            }}
          />
          <button type="submit" className="btn btn--primary" aria-label="Küldés" disabled={busy || !kerdes.trim()}>
            <Icon name="send" size={18} />
          </button>
        </div>
      </form>
    </aside>
  );
}

function Valasz({ v }: { v: ChatValasz }) {
  const { patient } = usePatient();
  return (
    <div className="chat-a">
      <div className="chat-a__head">
        <TypeTag type="modell" />
        <UncertaintyMeter value={v.bizonytalansag} />
      </div>
      <div className="chat-a__body">
        <ol className="chat-a__list">
          {v.pontok.map((p, i) => (
            <li key={i}>
              {p.szoveg}
              <div className="srcs srcs--tight">
                {p.forrasok.length > 0 ? (
                  p.forrasok.map((f, j) => <SourceChip key={j} forras={f} small />)
                ) : (
                  <span className="nosrc">
                    <Icon name="info" size={12} strokeWidth={2.4} />
                    forrás nélküli következtetés
                  </span>
                )}
              </div>
            </li>
          ))}
        </ol>
        {v.kapcsoltSzabalyok.map((s) => (
          <Link key={s.allitasId} to={`/betegek/${patient.id}?allitas=${s.allitasId}`} className="rule-ref">
            <TypeTag type="szabaly" />
            <span>
              <strong>{s.cim}</strong> —{' '}
              {s.sulyossag === 'critical' ? 'kritikus' : s.sulyossag === 'warning' ? 'figyelmeztetés' : 'info'}
            </span>
            <span className="meta push-right">állítás →</span>
          </Link>
        ))}
      </div>
      <div className="chat-a__foot">
        {/* Architekturális döntés: chat-válaszból modell-állítás? Addig letiltva. */}
        <button type="button" className="btn btn--sm" disabled title="Ehhez még nincs backend-végpont">
          <Icon name="plus" size={14} />
          Mentés állításként
        </button>
        <span className="meta">jóváhagyásra kerülne a listába</span>
      </div>
    </div>
  );
}
