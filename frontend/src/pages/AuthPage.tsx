import { useId, useState, type FormEvent } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { api } from '../api';
import { setCurrentUser, useCurrentUser, type Szerepkor } from '../api/currentUser';
import { Icon } from '../components/Icon';
import { AppBar } from '../components/shell';

type Mod = 'belepes' | 'regisztracio';

/**
 * Belépés és új profil létrehozása (orvos vagy admin). A backend még nem ad
 * munkamenetet: a belépés ellenőrzi a jelszót, utána a felhasználó
 * azonosítója megy a kérésekben (X-User-Id). A valódi védelem a Security-fázis.
 */
export function AuthPage() {
  const user = useCurrentUser();
  const location = useLocation();
  const navigate = useNavigate();
  const [mod, setMod] = useState<Mod>('belepes');
  // Ahonnan a belépésre irányítottak (pl. egy megosztott betegoldal-link)
  const vissza = (location.state as { from?: string } | null)?.from ?? '/dashboard';

  if (user) return <Navigate to={vissza} replace />;

  const kesz = () => navigate(vissza, { replace: true });

  return (
    <div className="app">
      <AppBar />
      <main className="authpage">
        <div className="stack-4">
          <span className="label">Klinikai döntéstámogatás</span>
          <h1 className="h1">{mod === 'belepes' ? 'Belépés' : 'Új profil'}</h1>
        </div>

        <section className="authcard" aria-label={mod === 'belepes' ? 'Belépés' : 'Új profil létrehozása'}>
          <div role="tablist" aria-label="Belépés vagy új profil" className="ptabs">
            <button
              type="button"
              role="tab"
              aria-selected={mod === 'belepes'}
              className={`ptab${mod === 'belepes' ? ' ptab--on' : ''}`}
              onClick={() => setMod('belepes')}
            >
              <Icon name="user" />
              Belépés
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={mod === 'regisztracio'}
              className={`ptab${mod === 'regisztracio' ? ' ptab--on' : ''}`}
              onClick={() => setMod('regisztracio')}
            >
              <Icon name="plus" />
              Új profil
            </button>
          </div>
          {mod === 'belepes' ? <BelepesForm onDone={kesz} /> : <RegisztracioForm onDone={kesz} />}
        </section>

        <p className="meta">
          Fejlesztői állapot: a belépés ellenőrzi a jelszót, de a backend végpontjait még nem védi munkamenet.
        </p>
      </main>
    </div>
  );
}

function BelepesForm({ onDone }: { onDone: () => void }) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const ok = username.trim() !== '' && password !== '';

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!ok) return;
    setBusy(true);
    setError(null);
    try {
      setCurrentUser(await api.belep({ username: username.trim(), password }));
      onDone();
    } catch (err) {
      setError((err as Error).message);
      setPassword('');
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="authcard__body" onSubmit={submit} noValidate>
      <div className="field">
        <label htmlFor="login-username">Felhasználónév</label>
        <input
          id="login-username"
          className="input"
          autoComplete="username"
          value={username}
          onChange={(e) => setUsername(e.target.value)}
          autoFocus
        />
      </div>
      <div className="field">
        <label htmlFor="login-password">Jelszó</label>
        <input
          id="login-password"
          type="password"
          className="input"
          autoComplete="current-password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
      </div>
      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}
      <button type="submit" className="btn btn--primary btn--lg" disabled={!ok || busy}>
        <Icon name="check" size={18} strokeWidth={2.4} />
        {busy ? 'Belépés…' : 'Belépés'}
      </button>
    </form>
  );
}

// A backend UserCreateRequest szabályai, hogy a hiba itt, érthetően jelenjen meg
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

function RegisztracioForm({ onDone }: { onDone: () => void }) {
  const [username, setUsername] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [password2, setPassword2] = useState('');
  const [role, setRole] = useState<Szerepkor>('DOCTOR');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [tried, setTried] = useState(false);
  const hintId = useId();

  const hibak = {
    username: !username.trim()
      ? 'Add meg a felhasználónevet.'
      : username.trim().length > 64
        ? 'Legfeljebb 64 karakter.'
        : null,
    email: !EMAIL.test(email.trim()) ? 'Érvényes email-címet adj meg.' : null,
    password: password.length < 8 || password.length > 72 ? 'A jelszó 8–72 karakter legyen.' : null,
    password2: password2 !== password ? 'A két jelszó nem egyezik.' : null,
  };
  const ok = Object.values(hibak).every((h) => h === null);
  const hiba = (k: keyof typeof hibak) =>
    tried && hibak[k] ? (
      <span className="form-error" id={`reg-${k}-error`}>
        {hibak[k]}
      </span>
    ) : null;

  async function submit(e: FormEvent) {
    e.preventDefault();
    setTried(true);
    if (!ok) return;
    setBusy(true);
    setError(null);
    try {
      setCurrentUser(await api.regisztral({ username: username.trim(), email: email.trim(), password, role }));
      onDone();
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="authcard__body" onSubmit={submit} noValidate>
      <fieldset className="rolepick">
        <legend className="field__legend">Szerepkör</legend>
        {(
          [
            ['DOCTOR', 'Orvos', 'Állításokat hagyhat jóvá, módosíthat, elutasíthat.'],
            ['ADMIN', 'Admin', 'Rendszerfelügyelet — klinikai állítást nem hagyhat jóvá.'],
          ] as const
        ).map(([value, cim, leiras]) => (
          <label key={value} className={`rolepick__opt${role === value ? ' rolepick__opt--on' : ''}`}>
            <input
              type="radio"
              name="role"
              value={value}
              checked={role === value}
              onChange={() => setRole(value)}
            />
            <span className="rolepick__title">
              <Icon name={value === 'DOCTOR' ? 'stetho' : 'user'} />
              {cim}
            </span>
            <span className="meta">{leiras}</span>
          </label>
        ))}
      </fieldset>

      <div className="field">
        <label htmlFor="reg-username">Felhasználónév</label>
        <input
          id="reg-username"
          className="input"
          autoComplete="username"
          maxLength={64}
          value={username}
          onChange={(e) => setUsername(e.target.value)}
          aria-invalid={tried && !!hibak.username}
          aria-describedby="reg-username-error"
        />
        {hiba('username')}
      </div>
      <div className="field">
        <label htmlFor="reg-email">Email</label>
        <input
          id="reg-email"
          type="email"
          className="input"
          autoComplete="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          aria-invalid={tried && !!hibak.email}
          aria-describedby="reg-email-error"
        />
        {hiba('email')}
      </div>
      <div className="field">
        <label htmlFor="reg-password">Jelszó</label>
        <input
          id="reg-password"
          type="password"
          className="input"
          autoComplete="new-password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          aria-invalid={tried && !!hibak.password}
          aria-describedby={`${hintId} reg-password-error`}
        />
        <span className="meta" id={hintId}>
          8–72 karakter.
        </span>
        {hiba('password')}
      </div>
      <div className="field">
        <label htmlFor="reg-password2">Jelszó újra</label>
        <input
          id="reg-password2"
          type="password"
          className="input"
          autoComplete="new-password"
          value={password2}
          onChange={(e) => setPassword2(e.target.value)}
          aria-invalid={tried && !!hibak.password2}
          aria-describedby="reg-password2-error"
        />
        {hiba('password2')}
      </div>
      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}
      <button type="submit" className="btn btn--primary btn--lg" disabled={busy}>
        <Icon name="plus" size={18} strokeWidth={2.4} />
        {busy ? 'Létrehozás…' : 'Profil létrehozása'}
      </button>
    </form>
  );
}
