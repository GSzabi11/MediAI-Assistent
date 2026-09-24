import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api';
import { NotAvailableError, type LaborMeres, type LaborParameter } from '../api/types';
import { formatDatum, formatHonap, formatSzam } from '../lib/format';
import { useAsync } from '../lib/useAsync';
import { usePatient } from '../patient/PatientContext';
import { Icon } from './Icon';
import { ErrorState, Loading } from './shell';

type Idoszak = '6ho' | '1ev' | 'mind';
const napok: Record<Idoszak, number> = { '6ho': 183, '1ev': 366, mind: Infinity };

const tizedes = (p: LaborParameter) => (p.meresek.some((m) => !Number.isInteger(m.ertek)) ? 1 : 0);
const kivul = (p: LaborParameter, v: number) =>
  (p.refAlso !== null && v < p.refAlso) || (p.refFelso !== null && v > p.refFelso);

function statusz(p: LaborParameter, v: number) {
  if (p.refAlso !== null && v < p.refAlso) return p.celtartomany ? '↓ cél alatt' : '↓ ref. alatt';
  if (p.refFelso !== null && v > p.refFelso) return p.celtartomany ? '↑ cél felett' : '↑ ref. felett';
  return null;
}

function refSzoveg(p: LaborParameter) {
  const d = tizedes(p);
  const pre = p.celtartomany ? 'cél' : 'ref.';
  if (p.refAlso !== null && p.refFelso !== null)
    return `${pre} ${formatSzam(p.refAlso, d)}–${formatSzam(p.refFelso, d)}`;
  if (p.refAlso !== null) return `${pre} > ${formatSzam(p.refAlso, d)}`;
  if (p.refFelso !== null) return `${pre} < ${formatSzam(p.refFelso, d)}`;
  return '';
}

/**
 * Labor-idősor: kis diagramok paraméterenként (egy tengely, egy sorozat),
 * referenciasávval. A tartományon kívüli pont alakban is más (teli + gyűrű),
 * nem csak színben. Pontra kattintva a lelet adott sora nyílik meg.
 */
export function LabTrend() {
  const { patient } = usePatient();
  const { state, reload } = useAsync(() => api.getLaborIdosor(patient.id), [patient.id]);
  const [idoszak, setIdoszak] = useState<Idoszak>('mind');
  const [nezet, setNezet] = useState<'diagram' | 'tablazat'>('diagram');

  if (state.status === 'loading') return <Loading what="Labor-idősor" />;
  if (state.status === 'error') {
    if (state.error instanceof NotAvailableError) {
      return (
        <div className="state">
          <Icon name="info" />A labor-idősorhoz még nincs backend-végpont.
        </div>
      );
    }
    return <ErrorState error={state.error} onRetry={reload} />;
  }
  if (state.data.patientId !== patient.id) {
    return <ErrorState error={new Error('A labor-adat nem az aktív beteghez tartozik — rejtve.')} />;
  }

  const minden = state.data.parameterek.flatMap((p) => p.meresek.map((m) => m.datum)).sort();
  const utolso = minden.at(-1);
  const hatar = utolso && idoszak !== 'mind' ? new Date(new Date(utolso).getTime() - napok[idoszak] * 864e5) : null;
  const params = state.data.parameterek.map((p) => ({
    ...p,
    meresek: p.meresek
      .filter((m) => !hatar || new Date(m.datum) >= hatar)
      .sort((a, b) => a.datum.localeCompare(b.datum)),
  }));
  const leletSzam = new Set(
    params.flatMap((p) => p.meresek.map((m) => m.dokumentumId)).filter((id): id is number => id !== null),
  ).size;
  const meresSzam = params.reduce((n, p) => n + p.meresek.length, 0);

  return (
    <div className="labtrend">
      <div className="panel__head panel__head--row">
        <div className="stack-4">
          <h2 className="h2 h2--sm">Laboreredmények időben</h2>
          <span className="meta row-inline">
            <Icon name="doc" />
            {meresSzam} mért érték{leletSzam > 0 && `, ${leletSzam} laborleletből`} — nem AI-következtetés
          </span>
        </div>
        <div className="row-gap-10 push-right">
          <Segmented
            label="Időszak"
            value={idoszak}
            onChange={setIdoszak}
            options={[
              ['6ho', '6 hó'],
              ['1ev', '1 év'],
              ['mind', 'Mind'],
            ]}
          />
          <Segmented
            label="Nézet"
            value={nezet}
            onChange={setNezet}
            options={[
              ['diagram', 'Diagram'],
              ['tablazat', 'Táblázat'],
            ]}
          />
        </div>
      </div>

      {params.length === 0 && <p className="empty">Nincs laboradat.</p>}

      {nezet === 'diagram' ? (
        <>
          <div className="labgrid">
            {params.map((p) => (
              <LabChart key={p.kod} p={p} />
            ))}
          </div>
          <div className="lablegend">
            <span className="row-inline">
              <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden="true">
                <circle cx="8" cy="8" r="5" fill="var(--c-surface)" stroke="var(--c-ink)" strokeWidth="2" />
              </svg>
              tartományon belül
            </span>
            <span className="row-inline">
              <svg width="18" height="18" viewBox="0 0 18 18" aria-hidden="true">
                <circle cx="9" cy="9" r="5" fill="var(--c-ink)" />
                <circle cx="9" cy="9" r="7.5" fill="none" stroke="var(--c-ink)" strokeWidth="1.5" />
              </svg>
              tartományon kívül
            </span>
            <span className="row-inline">
              <span className="lablegend__band" />
              referencia- / céltartomány
            </span>
            <span className="push-right">Pontra kattintva a lelet adott sora nyílik meg.</span>
          </div>
        </>
      ) : (
        <LabTable params={params} />
      )}
    </div>
  );
}

function Segmented<T extends string>({
  label,
  value,
  onChange,
  options,
}: {
  label: string;
  value: T;
  onChange: (v: T) => void;
  options: [T, string][];
}) {
  return (
    <div className="seg2" role="group" aria-label={label}>
      {options.map(([v, l]) => (
        <button
          key={v}
          type="button"
          className={value === v ? 'on' : ''}
          aria-pressed={value === v}
          onClick={() => onChange(v)}
        >
          {l}
        </button>
      ))}
    </div>
  );
}

const H = 170,
  Y0 = 16,
  Y1 = 136;

/** A diagram a valós pixelszélességével rajzol, így nem skálázódik a betű. */
function useWidth<T extends HTMLElement>() {
  const ref = useRef<T>(null);
  const [w, setW] = useState(440);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const ro = new ResizeObserver(([e]) => setW(Math.max(240, Math.round(e.contentRect.width))));
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  return [ref, w] as const;
}

function LabChart({ p }: { p: LaborParameter }) {
  const { patient } = usePatient();
  const navigate = useNavigate();
  const [hover, setHover] = useState<number | null>(null);
  const [wrapRef, W] = useWidth<HTMLDivElement>();
  const X0 = 60,
    X1 = W - 28,
    L = 44,
    R = W - 8;
  const ms = p.meresek;
  const d = tizedes(p);
  if (ms.length === 0) return null;
  const Hh = H + 20;

  const last = ms[ms.length - 1];
  const first = ms[0];
  const st = statusz(p, last.ertek);

  // y-tartomány: mérések + referenciahatárok, kis ráhagyással
  const vals = [
    ...ms.map((m) => m.ertek),
    ...(p.refAlso !== null ? [p.refAlso] : []),
    ...(p.refFelso !== null ? [p.refFelso] : []),
  ];
  const span = Math.max(...vals) - Math.min(...vals) || 1;
  const lo = Math.min(...vals) - span * 0.25;
  const hi = Math.max(...vals) + span * 0.25;
  const y = (v: number) => Y1 - ((v - lo) / (hi - lo)) * (Y1 - Y0);

  const t0 = new Date(first.datum).getTime();
  const t1 = new Date(last.datum).getTime();
  const x = (m: LaborMeres) =>
    t1 === t0 ? (X0 + X1) / 2 : X0 + ((new Date(m.datum).getTime() - t0) / (t1 - t0)) * (X1 - X0);

  const bandTop = y(Math.min(p.refFelso ?? hi, hi));
  const bandBot = y(Math.max(p.refAlso ?? lo, lo));
  const ticks = [p.refAlso, p.refFelso].filter((v): v is number => v !== null);

  // A laborsornak nincs karaktertartománya a leleten belül: a lelet egészét nyitjuk meg
  const open = (m: LaborMeres) => {
    if (m.dokumentumId !== null) navigate(`/betegek/${patient.id}?nezet=dokumentum&dok=${m.dokumentumId}`);
  };

  const h = hover !== null ? ms[hover] : null;
  const tipX = h ? Math.min(Math.max(x(h) - 172, 48), W - 170) : 0;

  return (
    <article className="chart" aria-label={`${p.nev} idősor`}>
      <div className="row-baseline">
        <h3 className="h3">{p.nev}</h3>
        {p.mertekegyseg && <span className="meta">{p.mertekegyseg}</span>}
        <span className="meta push-right">{refSzoveg(p)}</span>
      </div>
      <div className="row-inline row-gap-10">
        <span className="big">{formatSzam(last.ertek, d)}</span>
        {st && <span className="flag">{st}</span>}
        <span className="meta push-right">
          {formatSzam(first.ertek, d)} → {formatSzam(last.ertek, d)} · {ms.length} lelet
        </span>
      </div>
      <div ref={wrapRef}>
        <svg
          viewBox={`0 0 ${W} ${Hh}`}
          width={W}
          height={Hh}
          role="img"
          aria-label={`${p.nev}: ${ms.map((m) => `${formatDatum(m.datum)} ${formatSzam(m.ertek, d)}`).join('; ')}`}
          onMouseLeave={() => setHover(null)}
        >
          <rect x={L} y={bandTop} width={R - L} height={Math.max(0, bandBot - bandTop)} fill="var(--c-ref-band)" />
          {ticks.map((t) => (
            <g key={t}>
              <line x1={L} x2={R} y1={y(t)} y2={y(t)} stroke="var(--c-line)" />
              <text
                x={38}
                y={y(t) + 4}
                fontSize={11}
                textAnchor="end"
                fill="var(--c-ink-muted)"
                fontFamily="var(--font-mono)"
              >
                {formatSzam(t, d)}
              </text>
            </g>
          ))}
          <line x1={L} x2={R} y1={Y1} y2={Y1} stroke="var(--c-line-input)" />
          {ms.map((m) => (
            <text
              key={m.datum}
              x={x(m)}
              y={Y1 + 20}
              fontSize={11}
              textAnchor="middle"
              fill="var(--c-ink-muted)"
              fontFamily="var(--font-mono)"
            >
              {formatHonap(m.datum)}
            </text>
          ))}
          {h && <line x1={x(h)} x2={x(h)} y1={18} y2={Y1} stroke="var(--c-ink)" strokeDasharray="3 3" />}
          <polyline
            points={ms.map((m) => `${x(m)},${y(m.ertek)}`).join(' ')}
            fill="none"
            stroke="var(--c-ink)"
            strokeWidth={2}
            strokeLinejoin="round"
          />
          {ms.map((m, i) => {
            const out = kivul(p, m.ertek);
            return (
              <g key={m.datum}>
                <circle
                  cx={x(m)}
                  cy={y(m.ertek)}
                  r={5.5}
                  fill={out ? 'var(--c-ink)' : 'var(--c-surface)'}
                  stroke={out ? 'var(--c-surface)' : 'var(--c-ink)'}
                  strokeWidth={2}
                />
                {out && <circle cx={x(m)} cy={y(m.ertek)} r={7} fill="none" stroke="var(--c-ink)" strokeWidth={1.5} />}
                {/* nagyobb, láthatatlan találati felület + billentyűzettel is elérhető */}
                <circle
                  cx={x(m)}
                  cy={y(m.ertek)}
                  r={16}
                  fill="transparent"
                  className="hit"
                  tabIndex={0}
                  role="link"
                  aria-label={`${formatDatum(m.datum)}: ${p.nev} ${formatSzam(m.ertek, d)}${statusz(p, m.ertek) ? ', ' + statusz(p, m.ertek) : ''}. Lelet megnyitása.`}
                  onMouseEnter={() => setHover(i)}
                  onFocus={() => setHover(i)}
                  onBlur={() => setHover(null)}
                  onClick={() => open(m)}
                  onKeyDown={(e) => (e.key === 'Enter' || e.key === ' ') && (e.preventDefault(), open(m))}
                />
              </g>
            );
          })}
          {!h && (
            <text
              x={x(last) - 12}
              y={y(last.ertek) - 10}
              fontSize={13}
              fontWeight={700}
              textAnchor="end"
              fill="var(--c-ink)"
            >
              {formatSzam(last.ertek, d)}
            </text>
          )}
          {h && (
            <g pointerEvents="none">
              <rect
                x={tipX}
                y={10}
                width={164}
                height={56}
                rx={4}
                fill="var(--c-surface)"
                stroke="var(--c-ink)"
                strokeWidth={1.5}
              />
              <text x={tipX + 10} y={28} fontSize={11} fill="var(--c-ink-muted)">
                {formatDatum(h.datum)} · Laborlelet
              </text>
              <text x={tipX + 10} y={46} fontSize={14} fontWeight={700} fill="var(--c-ink)">
                {p.nev} {formatSzam(h.ertek, d)} {statusz(p, h.ertek) ?? ''}
              </text>
              <text x={tipX + 10} y={60} fontSize={11} fontWeight={700} fill="var(--c-accent)">
                {h.dokumentumId !== null ? 'Lelet megnyitása →' : 'nincs csatolt lelet'}
              </text>
            </g>
          )}
        </svg>
      </div>
    </article>
  );
}

function LabTable({ params }: { params: LaborParameter[] }) {
  const datumok = [...new Set(params.flatMap((p) => p.meresek.map((m) => m.datum)))].sort();
  return (
    <div className="labtable-wrap">
      <table className="labtable">
        <caption className="sr-only">Laboreredmények dátum szerint</caption>
        <thead>
          <tr>
            <th scope="col">Paraméter</th>
            <th scope="col">Tartomány</th>
            {datumok.map((d) => (
              <th key={d} scope="col" className="mono">
                {formatDatum(d)}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {params.map((p) => (
            <tr key={p.kod}>
              <th scope="row">
                {p.nev} <span className="meta">{p.mertekegyseg}</span>
              </th>
              <td className="meta">{refSzoveg(p)}</td>
              {datumok.map((d) => {
                const m = p.meresek.find((x) => x.datum === d);
                const st = m ? statusz(p, m.ertek) : null;
                return (
                  <td key={d} className={`mono${st ? ' labtable__out' : ''}`}>
                    {m ? formatSzam(m.ertek, tizedes(p)) : '—'} {st && <span aria-label={st}>{st.slice(0, 1)}</span>}
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
