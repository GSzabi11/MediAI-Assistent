import { useEffect, type ReactNode } from 'react';
import { BrowserRouter, Navigate, Route, Routes, useLocation, useParams } from 'react-router-dom';
import { api } from './api';
import { getCurrentUser, setCurrentUser, useCurrentUser } from './api/currentUser';
import { ApiError } from './api/types';
import { ErrorBoundary } from './components/ErrorBoundary';
import { AdminPage } from './pages/AdminPage';
import { AuthPage } from './pages/AuthPage';
import { PatientListPage } from './pages/PatientListPage';
import { PatientPage } from './pages/PatientPage';
import { ProfilePage } from './pages/ProfilePage';

/** Betegváltáskor a `key` miatt a teljes betegoldal-állapot újraindul. */
function PatientRoute() {
  const { patientId } = useParams();
  return <PatientPage key={patientId} />;
}

/** Belépés nélkül betegadat nem látszik: a belépés után visszavisz ide. */
function RequireUser({ children }: { children: ReactNode }) {
  const user = useCurrentUser();
  const location = useLocation();
  if (!user) return <Navigate to="/" replace state={{ from: location.pathname + location.search }} />;
  return children;
}

/**
 * A böngészőben tárolt belépés ellenőrzése a backenden (GET /users/{id}). Adatbázis-
 * visszaállítás után a tárolt azonosító nem létező — vagy más — felhasználóra mutatna,
 * és az ő nevében menne minden kérés és jóváhagyás. Eltérésnél kiléptetünk.
 * Elérhetetlen backend esetén nem léptetünk ki (az csak átmeneti).
 */
function SessionCheck() {
  const user = useCurrentUser();
  const id = user?.id;
  useEffect(() => {
    if (id === undefined) return;
    api.getFelhasznalo(id).then(
      (u) => {
        const most = getCurrentUser();
        if (!most || most.id !== u.id) return;
        if (u.felhasznalonev !== most.username) setCurrentUser(null);
        else if (u.szerepkor !== most.role) setCurrentUser({ ...most, role: u.szerepkor });
      },
      (e: unknown) => {
        if (e instanceof ApiError && e.status === 404) setCurrentUser(null);
      },
    );
  }, [id]);
  return null;
}

function Oldalak() {
  const location = useLocation();
  return (
    // Útvonalanként új boundary: egy hibás nézet után a navigálás tiszta lappal indul
    <ErrorBoundary key={location.pathname}>
      <Routes>
        {/* A kezdőoldal a belépés; belépett felhasználót az AuthPage a beteglistára visz */}
        <Route path="/" element={<AuthPage />} />
        <Route path="/belepes" element={<Navigate to="/" replace />} />
        <Route path="/dashboard" element={<Navigate to="/patients" replace />} />
        <Route
          path="/patients"
          element={
            <RequireUser>
              <PatientListPage />
            </RequireUser>
          }
        />
        <Route
          path="/betegek/:patientId"
          element={
            <RequireUser>
              <PatientRoute />
            </RequireUser>
          }
        />
        <Route
          path="/profil"
          element={
            <RequireUser>
              <ProfilePage />
            </RequireUser>
          }
        />
        <Route
          path="/admin"
          element={
            <RequireUser>
              <AdminPage />
            </RequireUser>
          }
        />
        <Route path="*" element={<Navigate to="/patients" replace />} />
      </Routes>
    </ErrorBoundary>
  );
}

export default function App() {
  return (
    <BrowserRouter>
      <SessionCheck />
      <Oldalak />
    </BrowserRouter>
  );
}
