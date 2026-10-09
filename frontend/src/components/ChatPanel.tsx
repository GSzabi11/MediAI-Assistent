import { Fragment, useCallback, useEffect, useId, useRef, useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api';
import { ApiError, type ChatForras, type ChatPont, type ChatValasz } from '../api/types';
import { forrasLink, formatTaj } from '../lib/format';
import { usePatient } from '../patient/PatientContext';
import { TypeTag, UncertaintyMeter } from './allitas/parts';
import { Icon, type IconName } from './Icon';

const GYORSKERDESEK = [
  'Foglald össze a beteg jelenlegi állapotát.',
  'Mik a legutóbbi kóros laboreredmények, és hogyan alakultak?',
  'Milyen gyógyszereket szed, és milyen allergiái vannak?',
  'Foglald össze a legutóbbi ambuláns lap véleményét.',
];

/** a modell ennyi korábbi kérdés-választ lát (a backend is legfeljebb ennyit fogad el) */
const ELOZMENY = 3;
const LEKERDEZES_MS = 1500;
/** ennyi után a felület feladja a várakozást (a sor elején is megvárja a futó feldolgozást) */
const MAX_VARAKOZAS_MS = 15 * 60 * 1000;

/**
 * Klinikai asszisztens (AI-chat, RAG). A backend a beteg dokumentumaiból visszakeresett
 * részletekből és a strukturált adatokból (labor, gyógyszer, allergia, kinyert állítások)
 * válaszoltat; a válasz minden pontja a hivatkozott forrásra mutat, forrás nélküli pont
 * külön jelölést kap. Érintett szabályok lakatos hivatkozásként jelennek meg.
 *
 * A beszélgetés nem tárolódik: csak ebben a komponensben él, újratöltés vagy betegváltás után
 * (és más felhasználónak) üres. A szülő `key={patient.id}`-vel rendereli, betegváltáskor újraindul.
 */
export function ChatPanel({ onCollapse }: { onCollapse: () => void }) {
  const { patient, dokumentumok, felvetelek } = usePatient();
  const [uzenetek, setUzenetek] = useState<ChatValasz[]>([]);
  const [kerdes, setKerdes] = useState('');
  const [kuldes, setKuldes] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [qqOpen, setQqOpen] = useState(false);
  const inputId = useId();
  const endRef = useRef<HTMLDivElement>(null);

  const lathato = uzenetek;
  const keszulo = lathato.find((u) => u.allapot === 'keszul');
  const busy = kuldes || keszulo !== undefined;

  // A készülő választ lekérdezi, amíg el nem készül
  const keszuloId = keszulo?.id;
  const keszuloKerve = keszulo?.kerve;
  useEffect(() => {
    if (keszuloId === undefined || keszuloKerve === undefined) return;
    let elo = true;
    const kezdet = new Date(keszuloKerve).getTime();
    const timer = window.setInterval(() => {
      if (Date.now() - kezdet > MAX_VARAKOZAS_MS) {
        window.clearInterval(timer);
        setError('A válasz túl sokáig készül — az AI-szolgáltatás valószínűleg leállt. Próbáld újra később.');
        return;
      }
      api
        .getChat(keszuloId)
        .then((v) => {
          if (!elo || v.allapot === 'keszul') return;
          setUzenetek((us) => us.map((u) => (u.id === v.id ? v : u)));
        })
        .catch(() => {
          /* átmeneti hiba: a következő körben újra */
        });
    }, LEKERDEZES_MS);
    return () => {
      elo = false;
      window.clearInterval(timer);
    };
  }, [keszuloId, keszuloKerve]);

  // Kapcsos zárójel kell: az újabb böngészőkben a scrollIntoView Promise-t ad vissza, és
  // ha az effect ezt visszaadná, a React takarítófüggvénynek hinné → „destroy is not a function”.
  const utolsoAllapot = lathato.map((u) => u.allapot).join();
  useEffect(() => {
    endRef.current?.scrollIntoView({ block: 'end', behavior: 'smooth' });
  }, [lathato.length, utolsoAllapot]);

  const kuld = useCallback(
    async (szoveg: string) => {
      const q = szoveg.trim();
      if (!q || busy) return;
      setKuldes(true);
      setQqOpen(false);
      setError(null);
      try {
        const elozmeny = lathato
          .filter((u) => u.allapot === 'kesz')
          .slice(-ELOZMENY)
          .map((u) => u.id);
        const uj = await api.kerdez(patient.id, q, elozmeny);
        if (uj.patientId !== patient.id) throw new Error('A válasz nem az aktív beteghez tartozik — elvetve.');
        setUzenetek((us) => [...us, uj]);
        setKerdes('');
      } catch (e) {
        setError(
          e instanceof ApiError && e.status === 409
            ? 'Már készül egy válasz ehhez a beteghez — várd meg.'
            : (e as Error).message,
        );
      } finally {
        setKuldes(false);
      }
    },
    [busy, lathato, patient.id],
  );

  function ujBeszelgetes() {
    setUzenetek([]);
    setError(null);
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
            aria-label="Új beszélgetés"
            title="Új beszélgetés (a korábbiak elrejtve, a modell nem látja őket)"
            onClick={ujBeszelgetes}
            disabled={busy || lathato.length === 0}
          >
            <Icon name="plus" size={18} />
          </button>
          <button
            type="button"
            className="iconbtn iconbtn--ghost iconbtn--sm"
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
        {lathato.length === 0 && (
          <p className="empty">
            Kérdezz a beteg állapotáról. A válasz a beteg dokumentumaiból és adataiból készül, minden pontja forrásra
            hivatkozik.
          </p>
        )}
        {lathato.map((u) => (
          <Fragment key={u.id}>
            <p className="chat-q">{u.kerdes}</p>
            <Valasz v={u} />
          </Fragment>
        ))}
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
            maxLength={2000}
            value={kerdes}
            placeholder="pl. Foglald össze a képalkotó leletek véleményét."
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
  if (v.allapot === 'keszul') {
    return (
      <p className="state" role="status">
        Válasz készül… (keresés a beteg dokumentumaiban, majd a modell válaszol)
      </p>
    );
  }
  if (v.allapot === 'hiba') {
    return (
      <p className="form-error" role="alert">
        A válasz nem készült el: {v.hiba}
      </p>
    );
  }
  return (
    <div className="chat-a">
      <div className="chat-a__head">
        <TypeTag type="modell" />
        {v.bizonytalansag && <UncertaintyMeter value={v.bizonytalansag} />}
      </div>
      <div className="chat-a__body">
        {csoportok(v.pontok).map((cs, i) =>
          cs.lista ? (
            <ol key={i} className="chat-a__list">
              {cs.pontok.map((p, j) => (
                <li key={j}>
                  <Pont p={p} />
                </li>
              ))}
            </ol>
          ) : (
            cs.pontok.map((p, j) => (
              <p key={`${i}-${j}`} className="chat-a__p">
                <Pont p={p} />
              </p>
            ))
          ),
        )}
        {v.figyelmeztetesek.length > 0 && (
          <ul className="chat-a__warn" aria-label="Ellenőrzési figyelmeztetések">
            {v.figyelmeztetesek.map((w) => (
              <li key={w}>
                <Icon name="info" size={12} strokeWidth={2.4} /> {w}
              </li>
            ))}
          </ul>
        )}
        {v.kapcsoltSzabalyok.length > 0 &&
          (v.kapcsoltSzabalyok.length <= 2 ? (
            <SzabalyLinkek v={v} patientId={patient.id} />
          ) : (
            // sok érintett szabálynál (pl. a kóros laborok listájánál) összecsukva, ne nyomja el a választ
            <details className="chat-a__rules">
              <summary>Érintett szabály-állítások ({v.kapcsoltSzabalyok.length})</summary>
              <SzabalyLinkek v={v} patientId={patient.id} />
            </details>
          ))}
      </div>
      <div className="chat-a__foot">
        <span className="meta" title={kontextusLeiras(v)}>
          AI-válasz döntéstámogatásra — nem jóváhagyott állítás{v.modell ? ` · ${v.modell.nev}` : ''}
        </span>
      </div>
    </div>
  );
}

/** A hivatkozott laborsorok szabálymotor-állításai (determinisztikus, nem az AI-szöveg része) */
function SzabalyLinkek({ v, patientId }: { v: ChatValasz; patientId: number }) {
  return (
    <div className="chat-a__rulelist">
      {v.kapcsoltSzabalyok.map((s) => (
        <Link key={s.allitasId} to={`/betegek/${patientId}?allitas=${s.allitasId}`} className="rule-ref">
          <TypeTag type="szabaly" />
          <span>
            <strong>{s.cim}</strong> —{' '}
            {s.sulyossag === 'critical' ? 'kritikus' : s.sulyossag === 'warning' ? 'figyelmeztetés' : 'info'}
          </span>
          <span className="meta push-right">állítás →</span>
        </Link>
      ))}
    </div>
  );
}

/** Egy pont: a szöveg (a **félkövér** címmel), utána a forrás-chipek — forrás nélkül jelölés */
function Pont({ p }: { p: ChatPont }) {
  return (
    <>
      {felkover(p.szoveg)}
      {p.forrasok.map((f, i) => (
        <Fragment key={i}>
          {' '}
          <ForrasChip f={f} />
        </Fragment>
      ))}
      {p.forrasok.length === 0 && !p.bevezeto && (
        <>
          {' '}
          <span className="nosrc">
            <Icon name="info" size={12} strokeWidth={2.4} />
            forrás nélküli következtetés
          </span>
        </>
      )}
    </>
  );
}

function ForrasChip({ f }: { f: ChatForras }) {
  const { patient } = usePatient();
  const icon: IconName =
    f.forras.tipus === 'labor' || f.cim.startsWith('Laborlelet')
      ? 'flask'
      : f.forras.tipus === 'megszolalas'
        ? 'mic'
        : 'doc';
  return (
    <Link
      to={forrasLink(patient.id, f.forras)}
      className="src src--small chat-src"
      aria-label={`Forrás megnyitása: ${f.cim}`}
    >
      <Icon name={icon} size={12} />
      {f.cim}
    </Link>
  );
}

/** az egymást követő listapontok egy <ol>-ba, a többi külön bekezdés */
function csoportok(pontok: ChatPont[]): { lista: boolean; pontok: ChatPont[] }[] {
  const out: { lista: boolean; pontok: ChatPont[] }[] = [];
  for (const p of pontok) {
    const utolso = out.at(-1);
    if (utolso && utolso.lista && p.lista) utolso.pontok.push(p);
    else out.push({ lista: p.lista, pontok: [p] });
  }
  return out;
}

/** a modell **félkövér** jelölése → <strong>; minden más szövegként (nincs HTML-beillesztés) */
function felkover(s: string): ReactNode[] {
  return s.split(/\*\*(.+?)\*\*/g).map((part, i) => (i % 2 === 1 ? <strong key={i}>{part}</strong> : part));
}

const KONTEXTUS_NEV: Record<string, string> = {
  CHUNK: 'dokumentum-részlet',
  LAB_RESULT: 'vizsgálat',
  STATEMENT: 'kinyert állítás',
  MEDICATION: 'gyógyszer',
  ALLERGY: 'allergia',
};

function kontextusLeiras(v: ChatValasz): string {
  const mibol = Object.entries(v.kontextus)
    .map(([k, n]) => `${n} ${KONTEXTUS_NEV[k] ?? k}`)
    .join(', ');
  const hogy = v.modell
    ? `${v.modell.nev}, seed ${v.modell.seed}, prompt ${v.modell.promptHash.slice(0, 12)}…, ${v.modell.verzio}` +
      (v.modell.embedding ? `, keresés: ${v.modell.embedding}` : '')
    : '';
  return `A modell bemenete: ${mibol || '—'}\n${hogy}`;
}
