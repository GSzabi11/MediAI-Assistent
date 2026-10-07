import { Link } from 'react-router-dom';
import type { Bizonytalansag, ClaimType, Forras, Sulyossag } from '../../api/types';
import { forrasLink, formatDatum } from '../../lib/format';
import { megszolalasKulcs, usePatient } from '../../patient/PatientContext';
import { Icon, type IconName } from '../Icon';

/* ---------- Típus-címke: ikon + felirat + címkestílus (3 független jelző) ---------- */

const typeMeta: Record<ClaimType, { icon: IconName; label: string }> = {
  szabaly: { icon: 'lock', label: 'Szabály' },
  forras: { icon: 'doc', label: 'Forrás' },
  modell: { icon: 'spark', label: 'AI' },
};

export function TypeTag({ type, iconOnly = false }: { type: ClaimType; iconOnly?: boolean }) {
  const m = typeMeta[type];
  return (
    <span className={`tag tag--${type}`} aria-label={iconOnly ? m.label : undefined}>
      <Icon name={m.icon} size={14} strokeWidth={2.4} />
      {!iconOnly && m.label}
    </span>
  );
}

/* ---------- Súlyosság ---------- */

export const sulyossagFelirat: Record<Sulyossag, string> = {
  critical: 'Kritikus',
  warning: 'Figyelmeztetés',
  info: 'Info',
};

/** critical / warning: teljes szélességű sáv; info: kis felirat a fejlécben. */
export function SeverityBand({ sulyossag }: { sulyossag: Sulyossag }) {
  if (sulyossag === 'info') return null;
  return (
    <div className={`sev sev--${sulyossag}`}>
      <Icon name="alert" size={sulyossag === 'critical' ? 18 : 16} strokeWidth={2.4} />
      {sulyossagFelirat[sulyossag]}
    </div>
  );
}

export function SeverityInline({ sulyossag }: { sulyossag: Sulyossag }) {
  return (
    <span className={`sev-inline sev-inline--${sulyossag}`}>
      <Icon name={sulyossag === 'info' ? 'info' : 'alert'} size={14} />
      {sulyossagFelirat[sulyossag]}
    </span>
  );
}

/* ---------- Bizonytalanság: szegmensek + szó (nem csak szín) ---------- */

const uncMeta: Record<Bizonytalansag, { n: number; label: string }> = {
  low: { n: 1, label: 'alacsony' },
  medium: { n: 2, label: 'közepes' },
  high: { n: 3, label: 'magas' },
};

export function UncertaintyMeter({ value, compact = false }: { value: Bizonytalansag; compact?: boolean }) {
  const m = uncMeta[value];
  return (
    <span className="unc" role="img" aria-label={`Bizonytalanság: ${m.label}`}>
      {!compact && <span aria-hidden="true">Bizonytalanság</span>}
      <span className="unc__segs" aria-hidden="true">
        {[1, 2, 3].map((i) => (
          <span key={i} className={`unc__seg${i <= m.n ? ' unc__seg--on' : ''}`} />
        ))}
      </span>
      {!compact && <strong aria-hidden="true">{m.label}</strong>}
    </span>
  );
}

/* ---------- Forrás-hivatkozás ---------- */

export function useForrasCimke() {
  const { dokumentumok, felvetelek, megszolalasByTurn } = usePatient();
  return (f: Forras): { cim: string; hely: string; icon: IconName } => {
    if (f.tipus === 'dokumentum' || f.tipus === 'kep') {
      const d = dokumentumok.find((x) => x.id === f.dokumentumId);
      const cim = d
        ? d.tipus === 'labor'
          ? `${d.cim} ${formatDatum(d.datum).slice(5, 10)}`
          : d.cim
        : `Dokumentum #${f.dokumentumId}`;
      const hely = f.tipus === 'kep' ? `${f.oldal}. oldal, kijelölt terület` : `kar. ${f.kezdet}–${f.veg}`;
      return { cim, hely, icon: d?.tipus === 'labor' ? 'flask' : 'doc' };
    }
    if (f.tipus === 'labor') {
      return { cim: 'Labormérés', hely: `#${f.laborId}`, icon: 'flask' };
    }
    const fv = felvetelek.find((x) => x.id === f.felvetelId);
    // A turnIds a felvétel turn-sorszámai; ha valamelyik nincs meg, látszódjon, ne tűnjön el csendben
    const sorszamok = f.turnIds.map((turn) =>
      megszolalasByTurn.has(megszolalasKulcs(f.felvetelId, turn))
        ? `#${String(turn).padStart(2, '0')}`
        : `#${turn} (nincs meg)`,
    );
    return {
      cim: fv ? `Felvétel #${fv.id}` : `Felvétel #${f.felvetelId}`,
      hely: `megsz. ${sorszamok.join(', ')}`,
      icon: 'mic',
    };
  };
}

export function SourceChip({
  forras,
  active = false,
  small = false,
}: {
  forras: Forras;
  active?: boolean;
  small?: boolean;
}) {
  const { patient } = usePatient();
  const cimke = useForrasCimke()(forras);
  return (
    <Link
      to={forrasLink(patient.id, forras)}
      className={`src${active ? ' src--active' : ''}${small ? ' src--small' : ''}`}
      aria-label={`Forrás megnyitása: ${cimke.cim}, ${cimke.hely}`}
    >
      <Icon name={cimke.icon} size={small ? 12 : 14} />
      {cimke.cim} <span className="mono src__hely">{cimke.hely}</span>
    </Link>
  );
}
