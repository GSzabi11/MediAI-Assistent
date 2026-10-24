import type { ReactNode } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { setCurrentUser, szerepkorFelirat, useCurrentUser } from '../api/currentUser';
import type { PatientResponse } from '../api/types';
import { eletkor, formatDatum, formatTaj } from '../lib/format';
import { setTemaMod, useTemaMod, type TemaMod } from '../lib/tema';
import { Icon } from './Icon';

export function AppBar() {
  const user = useCurrentUser();
  return (
    <header className="appbar">
      <Link to="/patients" className="appbar__brand">
        <Icon name="plusBox" size={22} />
        MediAI
      </Link>
      {user && (
        <nav className="appbar__nav" aria-label="Fő navigáció">
          <Link to="/patients">Betegek</Link>
          <Link to="/profil">Profilom</Link>
          {user.role === 'ADMIN' && <Link to="/admin">Felhasználók</Link>}
        </nav>
      )}
      <div className="appbar__right">
        <TemaValaszto />
        <span className="lang" aria-label="Nyelv: magyar (angol még nem elérhető)">
          <strong>HU</strong> / EN
        </span>
        {user && <UserMenu />}
      </div>
    </header>
  );
}

const temaOpciok: readonly (readonly [TemaMod, string, 'monitor' | 'sun' | 'moon'])[] = [
  ['system', 'Rendszer szerint', 'monitor'],
  ['light', 'Világos', 'sun'],
  ['dark', 'Sötét', 'moon'],
];

/** Megjelenés választó: rendszer szerint (alapértelmezett), világos vagy sötét. */
function TemaValaszto() {
  const mod = useTemaMod();
  return (
    <div className="seg2 seg2--icon" role="group" aria-label="Megjelenés">
      {temaOpciok.map(([v, felirat, ikon]) => (
        <button
          key={v}
          type="button"
          className={mod === v ? 'on' : ''}
          aria-pressed={mod === v}
          aria-label={felirat}
          title={felirat}
          onClick={() => setTemaMod(v)}
        >
          <Icon name={ikon} />
        </button>
      ))}
    </div>
  );
}

/** A bejelentkezett felhasználó neve, szerepköre és a kijelentkezés. */
function UserMenu() {
  const user = useCurrentUser();
  const navigate = useNavigate();
  if (!user) return null;
  return (
    <>
      <span className="appbar__user">
        <Icon name={user.role === 'DOCTOR' ? 'stetho' : 'user'} />
        <strong>{user.username}</strong> · {szerepkorFelirat[user.role].toLowerCase()}
      </span>
      <button
        type="button"
        className="btn btn--sm"
        onClick={() => {
          setCurrentUser(null);
          navigate('/', { replace: true });
        }}
      >
        Kijelentkezés
      </button>
    </>
  );
}

/**
 * Rögzített beteg-azonosító sáv. Minden nézeten látszik, nem görgethető.
 * patient = null → "Nincs kiválasztott beteg" állapot (csíkozott, hogy ne
 * lehessen összetéveszteni egy beteg fejlécével).
 */
export function PatientBand({ patient, extra }: { patient: PatientResponse | null; extra?: ReactNode }) {
  if (!patient) {
    return (
      <section className="pband pband--empty" aria-label="Aktív beteg">
        <Icon name="userOff" size={24} strokeWidth={2} />
        <div className="pband__field">
          <span className="pband__label">AKTÍV BETEG</span>
          <span className="pband__none">Nincs kiválasztott beteg</span>
        </div>
      </section>
    );
  }
  return (
    <section className="pband" aria-label="Aktív beteg" aria-live="polite">
      <div className="pband__field">
        <span className="pband__label">AKTÍV BETEG</span>
        <span className="pband__name">{patient.nev}</span>
      </div>
      <div className="pband__field">
        <span className="pband__label">TAJ</span>
        <span className="pband__taj mono">{formatTaj(patient.taj)}</span>
      </div>
      <div className="pband__field">
        <span className="pband__label">SZÜLETETT</span>
        <span>
          {formatDatum(patient.szuletesiDatum)} · {eletkor(patient.szuletesiDatum)} év
        </span>
      </div>
      <div className="pband__field">
        <span className="pband__label">ANYJA NEVE</span>
        <span>{patient.anyjaNeve ?? '—'}</span>
      </div>
      {patient.lakcim && (
        <div className="pband__field">
          <span className="pband__label">LAKCÍM</span>
          <span>{patient.lakcim}</span>
        </div>
      )}
      {/* pl. az ellátás-kontextus chipje (EllatasValaszto.KontextusChip) */}
      {extra}
      <Link to="/patients" className="btn btn--on-dark pband__switch">
        Beteg váltása
      </Link>
    </section>
  );
}

/**
 * Beteg-egyezés jelző a tartalompanelek fejlécében. Eltérésnél a hívó
 * elrejti a tartalmat — ez a beteg-keveredés elleni kemény invariáns.
 */
export function PatientMatch({
  active,
  contentPatientId,
  what,
}: {
  active: PatientResponse;
  contentPatientId: number;
  what: string;
}) {
  if (contentPatientId === active.id) {
    return (
      <div className="pmatch">
        <Icon name="checkCircle" />
        <span>
          {what} betege: <strong>{active.nev}</strong> · <span className="mono">TAJ {formatTaj(active.taj)}</span> —
          egyezik a fejléccel
        </span>
      </div>
    );
  }
  return (
    <div className="pmismatch" role="alert">
      <div className="pmismatch__band">
        <Icon name="alert" size={22} strokeWidth={2.4} />
        ELTÉRŐ BETEG — a tartalom nem az aktív beteghez tartozik
      </div>
      <p className="pmismatch__body">A tartalom rejtve marad. Válts beteget, vagy jelezd a hibát.</p>
    </div>
  );
}

export function Loading({ what }: { what: string }) {
  return (
    <p className="state" role="status">
      {what} betöltése…
    </p>
  );
}

export function ErrorState({ error, onRetry }: { error: Error; onRetry?: () => void }) {
  return (
    <div className="state state--error" role="alert">
      <Icon name="alert" />
      <span>{error.message}</span>
      {onRetry && (
        <button className="btn" onClick={onRetry}>
          Újra
        </button>
      )}
    </div>
  );
}
