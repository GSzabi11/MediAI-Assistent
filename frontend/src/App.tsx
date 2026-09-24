import type { ReactNode } from 'react';
import { BrowserRouter, Navigate, Route, Routes, useLocation, useParams } from 'react-router-dom';
import { useCurrentUser } from './api/currentUser';
import { AuthPage } from './pages/AuthPage';
import { PatientListPage } from './pages/PatientListPage';
import { PatientPage } from './pages/PatientPage';

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

export default function App() {
  return (
    <BrowserRouter>
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
        <Route path="*" element={<Navigate to="/patients" replace />} />
      </Routes>
    </BrowserRouter>
  );
}
