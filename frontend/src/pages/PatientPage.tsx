import { useCallback, useEffect, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { api } from '../api';
import type { ClaimResponse, DokumentumListItem, Forras, JovahagyasResponse } from '../api/types';
import { AllitasLista } from '../components/allitas/AllitasLista';
import { ChatPanel } from '../components/ChatPanel';
import { DocumentViewer } from '../components/DocumentViewer';
import { Icon, type IconName } from '../components/Icon';
import { LabTrend } from '../components/LabTrend';
import { AppBar, ErrorState, Loading, PatientBand, PlannedFeatures } from '../components/shell';
import { TranscriptView } from '../components/TranscriptView';
import { formatDatum, formatIdo } from '../lib/format';
import { useAsync } from '../lib/useAsync';
import { megszolalasKulcs, PatientContext, usePatient, type PatientCtx } from '../patient/PatientContext';

type Nezet = 'dokumentum' | 'labor' | 'atirat';

/** Becsukott/kinyitott panel állapot — kényelmi, böngészőnként tárolva. */
function usePersistentFlag(key: string, initial: boolean) {
  const [v, setV] = useState<boolean>(() => {
    try {
      const s = localStorage.getItem(key);
      return s === null ? initial : s === '1';
    } catch {
      return initial;
    }
  });
  const set = useCallback(
    (next: boolean) => {
      setV(next);
      try {
        localStorage.setItem(key, next ? '1' : '0');
      } catch {
        /* privát mód: nem baj */
      }
    },
    [key],
  );
  return [v, set] as const;
}

export function PatientPage() {
  const patientId = Number(useParams().patientId);

  // Minden betegfüggő adat egyetlen betöltésben — így nem lehet olyan köztes
  // állapot, ahol a fejléc már az új, a tartalom még a régi beteget mutatja.
  const { state, reload, setData } = useAsync(async () => {
    const [patient, dokumentumok, felvetelek, allitasok] = await Promise.all([
      api.getPatient(patientId),
      api.listDokumentumok(patientId),
      api.listFelvetelek(patientId),
      api.listAllitasok(patientId),
    ]);
    const megsz = (await Promise.all(felvetelek.map((f) => api.listMegszolalasok(f.id)))).flat();
    const bad = [...dokumentumok, ...felvetelek, ...allitasok].find((x) => x.patientId !== patient.id);
    if (bad)
      throw new Error(
        'Beteg-azonosítási hiba: a betöltött tartalom egy része más beteghez tartozik. A nézet nem jelenik meg.',
      );
    return {
      ctx: {
        patient,
        dokumentumok,
        felvetelek,
        megszolalasByTurn: new Map(megsz.map((m) => [megszolalasKulcs(m.felvetelId, m.sorszam), m])),
      } satisfies PatientCtx,
      allitasok,
    };
  }, [patientId]);

  const onDecided = useCallback(
    (id: number, j: JovahagyasResponse) =>
      setData((d) => ({ ...d, allitasok: d.allitasok.map((a) => (a.id === id ? { ...a, jovahagyas: j } : a)) })),
    [setData],
  );

  return (
    <div className="app">
      <AppBar />
      <PatientBand patient={state.status === 'ok' ? state.data.ctx.patient : null} />
      {state.status === 'loading' && <Loading what="Beteg" />}
      {state.status === 'error' && <ErrorState error={state.error} onRetry={reload} />}
      {state.status === 'ok' && (
        <PatientContext.Provider value={state.data.ctx}>
          <Workspace allitasok={state.data.allitasok} onDecided={onDecided} />
        </PatientContext.Provider>
      )}
    </div>
  );
}

function Workspace({
  allitasok,
  onDecided,
}: {
  allitasok: ClaimResponse[];
  onDecided: (id: number, j: JovahagyasResponse) => void;
}) {
  const [params, setParams] = useSearchParams();
  const [forrasokNyitva, setForrasokNyitva] = usePersistentFlag('mediai.sources.open', false);
  const [chatNyitva, setChatNyitva] = usePersistentFlag('mediai.chat.open', true);
  const { patient, dokumentumok, felvetelek } = usePatient();
  useScrollToAllitas();
  const nezet = (params.get('nezet') as Nezet) || 'dokumentum';
  const alapDok = dokumentumok.find((d) => d.tipus !== 'labor') ?? dokumentumok[0];
  const dokId = Number(params.get('dok')) || alapDok?.id;
  const kezdet = params.get('kezdet');
  const veg = params.get('veg');
  const kiemeles = kezdet !== null && veg !== null ? { kezdet: Number(kezdet), veg: Number(veg) } : null;
  const felvetelId = Number(params.get('felvetel')) || felvetelek[0]?.id;
  const turnIds = (params.get('turn') ?? '').split(',').filter(Boolean).map(Number);
  const laborDokIds = new Set(dokumentumok.filter((d) => d.tipus === 'labor').map((d) => d.id));

  const activeForras = (f: Forras) =>
    (nezet === 'dokumentum' &&
      f.tipus === 'dokumentum' &&
      !!kiemeles &&
      f.dokumentumId === dokId &&
      f.kezdet === kiemeles.kezdet &&
      f.veg === kiemeles.veg) ||
    (nezet === 'atirat' &&
      f.tipus === 'megszolalas' &&
      f.felvetelId === felvetelId &&
      f.turnIds.some((t) => turnIds.includes(t)));

  const setNezet = (n: Nezet) => setParams(n === 'dokumentum' ? {} : { nezet: n });

  const cols = [
    forrasokNyitva ? 'var(--sources-w)' : 'var(--rail-w)',
    'minmax(0, 1fr)',
    'var(--claims-w)',
    chatNyitva ? 'var(--chat-w)' : 'var(--rail-w)',
  ].join(' ');

  return (
    <>
      <div className="toolbar">
        <span className="meta">
          {patient.nev} · {dokumentumok.length} dokumentum · {felvetelek.length} felvétel
        </span>
        <div className="push-right">
          <PlannedFeatures />
        </div>
      </div>
      <main className="workspace" style={{ gridTemplateColumns: cols }}>
        {forrasokNyitva ? (
          <SourcesPanel
            onCollapse={() => setForrasokNyitva(false)}
            dokId={nezet === 'dokumentum' ? dokId : undefined}
            felvetelId={nezet === 'atirat' ? felvetelId : undefined}
          />
        ) : (
          <SourcesRail
            onExpand={() => setForrasokNyitva(true)}
            dokId={nezet === 'dokumentum' ? dokId : undefined}
            nezet={nezet}
          />
        )}

        <section className="panel" aria-label="Munkaterület">
          <div role="tablist" aria-label="Munkaterület nézetei" className="ptabs">
            <Tab on={nezet === 'dokumentum'} icon="doc" onClick={() => setNezet('dokumentum')}>
              Dokumentum
            </Tab>
            <Tab on={nezet === 'labor'} icon="trend" onClick={() => setNezet('labor')}>
              Labor-idősor
            </Tab>
            <Tab
              on={nezet === 'atirat'}
              icon="mic"
              onClick={() => setNezet('atirat')}
              disabled={felvetelek.length === 0}
            >
              Átirat
            </Tab>
          </div>
          {nezet === 'dokumentum' &&
            (dokId ? (
              <DocumentViewer key={dokId} dokumentumId={dokId} kiemeles={kiemeles} />
            ) : (
              <p className="empty">Nincs dokumentum.</p>
            ))}
          {nezet === 'labor' && <LabTrend />}
          {nezet === 'atirat' &&
            (felvetelId ? (
              <TranscriptView felvetelId={felvetelId} kiemeltTurnIds={turnIds} />
            ) : (
              <p className="empty">Nincs hangfelvétel.</p>
            ))}
        </section>

        <AllitasLista
          key={nezet === 'labor' ? 'labor' : 'mind'}
          allitasok={allitasok}
          activeForras={activeForras}
          onDecided={onDecided}
          laborDokIds={laborDokIds}
          defaultSzuro={nezet === 'labor' ? 'labor' : 'mind'}
        />

        {chatNyitva ? (
          <ChatPanel key={patient.id} onCollapse={() => setChatNyitva(false)} />
        ) : (
          <nav className="rail rail--chat" aria-label="Klinikai asszisztens (összecsukva)">
            <button
              type="button"
              className="iconbtn iconbtn--ghost"
              aria-label="Klinikai asszisztens kinyitása"
              onClick={() => setChatNyitva(true)}
            >
              <Icon name="expandLeft" size={20} />
            </button>
            <span className="tag tag--modell tag--icon" aria-hidden="true">
              <Icon name="spark" size={14} />
            </span>
            <span className="rail__vertical">Klinikai asszisztens</span>
          </nav>
        )}
      </main>
    </>
  );
}

function Tab({
  on,
  icon,
  onClick,
  children,
  disabled,
}: {
  on: boolean;
  icon: IconName;
  onClick: () => void;
  children: string;
  disabled?: boolean;
}) {
  return (
    <button
      type="button"
      role="tab"
      aria-selected={on}
      className={`ptab${on ? ' ptab--on' : ''}`}
      onClick={onClick}
      disabled={disabled}
    >
      <Icon name={icon} />
      {children}
    </button>
  );
}

function dokLink(patientId: number, d: DokumentumListItem) {
  return `/betegek/${patientId}?nezet=dokumentum&dok=${d.id}`;
}

function SourcesRail({ onExpand, dokId, nezet }: { onExpand: () => void; dokId?: number; nezet: Nezet }) {
  const { patient, dokumentumok, felvetelek } = usePatient();
  const labor = dokumentumok.filter((d) => d.tipus === 'labor');
  return (
    <nav className="rail" aria-label="Források (összecsukva)">
      <button type="button" className="iconbtn iconbtn--ghost" aria-label="Források kinyitása" onClick={onExpand}>
        <Icon name="expandRight" size={20} />
      </button>
      <span className="rail__sep" />
      {dokumentumok
        .filter((d) => d.tipus !== 'labor')
        .map((d) => (
          <Link
            key={d.id}
            to={dokLink(patient.id, d)}
            className={`iconbtn${d.id === dokId ? ' iconbtn--on' : ''}`}
            aria-label={`${d.cim}, ${formatDatum(d.datum)}`}
          >
            <Icon name="doc" size={20} strokeWidth={2} />
          </Link>
        ))}
      {labor.length > 0 && (
        <Link
          to={`/betegek/${patient.id}?nezet=labor`}
          className={`iconbtn${nezet === 'labor' ? ' iconbtn--on' : ''}`}
          aria-label={`Laborleletek (${labor.length})`}
        >
          <Icon name="flask" size={20} strokeWidth={2} />
        </Link>
      )}
      <span className="rail__sep" />
      {felvetelek.map((f) => (
        <Link
          key={f.id}
          to={`/betegek/${patient.id}?nezet=atirat&felvetel=${f.id}`}
          className={`iconbtn${nezet === 'atirat' ? ' iconbtn--on' : ''}`}
          aria-label={`Felvétel #${f.id}`}
        >
          <Icon name="mic" size={20} strokeWidth={2} />
        </Link>
      ))}
    </nav>
  );
}

function SourcesPanel({
  onCollapse,
  dokId,
  felvetelId,
}: {
  onCollapse: () => void;
  dokId?: number;
  felvetelId?: number;
}) {
  const { patient, dokumentumok, felvetelek } = usePatient();
  const csoport = (label: string, items: DokumentumListItem[]) =>
    items.length > 0 && (
      <div className="navgroup">
        <span className="label">{label}</span>
        {items.map((d) => (
          <Link
            key={d.id}
            to={dokLink(patient.id, d)}
            className={`navitem${d.id === dokId ? ' navitem--on' : ''}`}
            aria-current={d.id === dokId ? 'page' : undefined}
          >
            <strong>{d.cim}</strong>
            <span className="meta">{[d.osztaly, formatDatum(d.datum)].filter(Boolean).join(' · ')}</span>
          </Link>
        ))}
      </div>
    );
  return (
    <aside className="sources" aria-label="Források">
      <div className="row-inline sources__head">
        <span className="label">Források</span>
        <button
          type="button"
          className="iconbtn iconbtn--ghost iconbtn--sm push-right"
          aria-label="Források összecsukása"
          onClick={onCollapse}
        >
          <Icon name="expandLeft" size={18} />
        </button>
      </div>
      {csoport(
        'Dokumentumok',
        dokumentumok.filter((d) => d.tipus !== 'labor'),
      )}
      {csoport(
        `Laborleletek · ${dokumentumok.filter((d) => d.tipus === 'labor').length}`,
        dokumentumok.filter((d) => d.tipus === 'labor').sort((a, b) => b.datum.localeCompare(a.datum)),
      )}
      {felvetelek.length > 0 && (
        <div className="navgroup">
          <span className="label">Hangfelvételek</span>
          {felvetelek.map((f) => (
            <Link
              key={f.id}
              to={`/betegek/${patient.id}?nezet=atirat&felvetel=${f.id}`}
              className={`navitem${f.id === felvetelId ? ' navitem--on' : ''}`}
            >
              <strong>Felvétel #{f.id}</strong>
              <span className="meta">
                {f.mod === 'surgossegi' ? 'Sürgősségi felvétel' : 'Echo diktálás'} · {formatDatum(f.kezdes)}{' '}
                {formatIdo(f.kezdes)}
              </span>
            </Link>
          ))}
        </div>
      )}
    </aside>
  );
}

/** ?allitas=ID → a kártyához görget (pl. a chat szabály-hivatkozásából). */
function useScrollToAllitas() {
  const [params] = useSearchParams();
  const id = params.get('allitas');
  useEffect(() => {
    if (id) document.getElementById(`allitas-${id}`)?.scrollIntoView({ block: 'center', behavior: 'smooth' });
  }, [id]);
}
