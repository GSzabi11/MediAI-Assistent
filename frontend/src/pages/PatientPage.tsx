import { useCallback, useEffect, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { api } from '../api';
import type { ClaimResponse, DokumentumListItem, Forras } from '../api/types';
import { AllitasLista } from '../components/allitas/AllitasLista';
import { AtiratFul } from '../components/AtiratFul';
import { ChatPanel } from '../components/ChatPanel';
import { DokumentumFul } from '../components/DokumentumFul';
import { EllatasPanel } from '../components/EllatasPanel';
import { GyogyszerAllergiaPanel } from '../components/GyogyszerAllergiaPanel';
import { KlinikaiStatusz } from '../components/KlinikaiStatusz';
import { Icon, type IconName } from '../components/Icon';
import { LabTrend } from '../components/LabTrend';
import { AppBar, ErrorState, Loading, PatientBand, PlannedFeatures } from '../components/shell';
import { allapotFelirat } from '../lib/feliratok';
import { formatDatum, formatIdo } from '../lib/format';
import { useAsync } from '../lib/useAsync';
import { megszolalasKulcs, PatientContext, usePatient, type PatientCtx } from '../patient/PatientContext';

const NEZETEK = ['statusz', 'dokumentum', 'labor', 'atirat', 'ellatas', 'gyogyszer'] as const;
type Nezet = (typeof NEZETEK)[number];

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
  const { state, reload, refresh, setData } = useAsync(async () => {
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
      patient,
      dokumentumok,
      felvetelek,
      megszolalasByTurn: new Map(megsz.map((m) => [megszolalasKulcs(m.felvetelId, m.sorszam), m])),
      allitasok,
    };
  }, [patientId]);

  // Amíg van feldolgozás alatt álló PDF, 5 mp-enként csendben frissít: a típus, a laborsorok
  // és az állítások a háttérben érkeznek, az orvosnak nem kell újratöltenie az oldalt.
  const feldolgozasAlatt = state.status === 'ok' && state.data.dokumentumok.some((d) => d.allapot === 'feldolgozas');
  useEffect(() => {
    if (!feldolgozasAlatt) return;
    const t = setInterval(() => {
      refresh().catch(() => {
        /* átmeneti hiba: a következő kör újrapróbálja, a látható adat marad */
      });
    }, 5000);
    return () => clearInterval(t);
  }, [feldolgozasAlatt, refresh]);

  // Döntés után a backend számolja az érvényes állapotot (legfrissebb döntés): onnan kérjük vissza
  const onDecided = useCallback(
    async (id: number) => {
      const friss = await api.getAllitas(id);
      setData((d) => ({ ...d, allitasok: d.allitasok.map((a) => (a.id === id ? friss : a)) }));
    },
    [setData],
  );

  return (
    <div className="app">
      <AppBar />
      <PatientBand patient={state.status === 'ok' ? state.data.patient : null} />
      {state.status === 'loading' && <Loading what="Beteg" />}
      {state.status === 'error' && <ErrorState error={state.error} onRetry={reload} />}
      {state.status === 'ok' && (
        <PatientContext.Provider value={{ ...state.data, frissit: refresh } satisfies PatientCtx}>
          <Workspace allitasok={state.data.allitasok} onDecided={onDecided} />
        </PatientContext.Provider>
      )}
    </div>
  );
}

function Workspace({ allitasok, onDecided }: { allitasok: ClaimResponse[]; onDecided: (id: number) => Promise<void> }) {
  const [params, setParams] = useSearchParams();
  const [forrasokNyitva, setForrasokNyitva] = usePersistentFlag('mediai.sources.open', false);
  const [chatNyitva, setChatNyitva] = usePersistentFlag('mediai.chat.open', true);
  const { patient, dokumentumok, felvetelek } = usePatient();
  useScrollToAllitas();
  const nezetParam = params.get('nezet');
  // alapértelmezés: a Klinikai státusz (az első fül)
  const nezet: Nezet = NEZETEK.includes(nezetParam as Nezet) ? (nezetParam as Nezet) : 'statusz';
  const alapDok = dokumentumok.find((d) => d.tipus !== 'labor') ?? dokumentumok[0];
  const dokParam = Number(params.get('dok'));
  // Csak az aktív beteg dokumentuma nyitható meg URL-ből (a nézet maga is ellenőrzi)
  const dokId = dokumentumok.some((d) => d.id === dokParam) ? dokParam : alapDok?.id;
  const kezdet = params.get('kezdet');
  const veg = params.get('veg');
  const kiemeles = kezdet !== null && veg !== null ? { kezdet: Number(kezdet), veg: Number(veg) } : null;
  // Laborsorból nyitva: az eredeti PDF, a sor kiemelésével (oldal + normált téglalap)
  const pdfNezet = params.get('pdf') === '1';
  const dobozParam = (params.get('doboz') ?? '').split(',').map(Number);
  const oldalParam = Number(params.get('oldal'));
  const pdfKiemeles =
    dobozParam.length === 4 && dobozParam.every(Number.isFinite) && oldalParam >= 1
      ? { oldal: oldalParam, x: dobozParam[0], y: dobozParam[1], szelesseg: dobozParam[2], magassag: dobozParam[3] }
      : null;
  const felvetelParam = Number(params.get('felvetel'));
  const felvetelId = felvetelek.some((f) => f.id === felvetelParam) ? felvetelParam : felvetelek[0]?.id;
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

  const fulek: { n: Nezet; icon: IconName; label: string }[] = [
    { n: 'statusz', icon: 'spark', label: 'Klinikai státusz' },
    // A Dokumentum nézet nincs a fülsorban: a bal oldali (összecsukható) forráslistából nyílik
    { n: 'labor', icon: 'trend', label: 'Labor-idősor' },
    { n: 'atirat', icon: 'mic', label: 'Átirat' },
    { n: 'ellatas', icon: 'stetho', label: 'Ellátások' },
    { n: 'gyogyszer', icon: 'pill', label: 'Gyógyszer és allergia' },
  ];

  return (
    <>
      <div className="toolbar">
        <span className="meta">
          {patient.nev} · {dokumentumok.length} dokumentum · {felvetelek.length} felvétel · {allitasok.length} állítás
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
          <div role="tablist" aria-label="Munkaterület nézetei" className="ptabs ptabs--wrap">
            {fulek.map((f) => (
              <Tab key={f.n} on={nezet === f.n} icon={f.icon} onClick={() => setNezet(f.n)}>
                {f.label}
              </Tab>
            ))}
          </div>
          {nezet === 'statusz' && <KlinikaiStatusz />}
          {nezet === 'dokumentum' && (
            <DokumentumFul dokId={dokId} kiemeles={kiemeles} pdfNezet={pdfNezet} pdfKiemeles={pdfKiemeles} />
          )}
          {nezet === 'labor' && <LabTrend />}
          {nezet === 'atirat' && <AtiratFul felvetelId={felvetelId} turnIds={turnIds} />}
          {nezet === 'ellatas' && <EllatasPanel />}
          {nezet === 'gyogyszer' && <GyogyszerAllergiaPanel />}
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
      <Link
        to={`/betegek/${patient.id}?nezet=dokumentum&feltoltes=1`}
        className="iconbtn iconbtn--ghost"
        aria-label="Dokumentum feltöltése"
      >
        <Icon name="upload" size={20} strokeWidth={2} />
      </Link>
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
            <span className="meta">
              {[
                d.osztaly,
                formatDatum(d.datum),
                d.allapot === 'feldolgozas'
                  ? 'AI-feldolgozás…'
                  : d.allapot === 'hiba'
                    ? 'feldolgozási hiba'
                    : d.vanSzoveg
                      ? null
                      : 'nincs szöveg',
              ]
                .filter(Boolean)
                .join(' · ')}
            </span>
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
      <Link to={`/betegek/${patient.id}?nezet=dokumentum&feltoltes=1`} className="navitem navitem--add">
        <span className="row-inline">
          <Icon name="upload" size={14} />
          Dokumentum feltöltése
        </span>
      </Link>
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
              {f.mod === 'surgossegi' ? 'Sürgősségi' : 'Echo diktálás'} · {formatDatum(f.kezdes)} {formatIdo(f.kezdes)}{' '}
              · {allapotFelirat[f.allapot].toLowerCase()}
            </span>
          </Link>
        ))}
        <Link to={`/betegek/${patient.id}?nezet=atirat&uj=felvetel`} className="navitem navitem--add">
          <span className="row-inline">
            <Icon name="mic" size={14} />
            Új felvétel
          </span>
        </Link>
      </div>
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
