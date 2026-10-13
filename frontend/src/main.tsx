import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';
// Betűtípus helyi csomagból (nem a Google Fonts-ról): internet nélkül is ugyanúgy jelenik meg
import '@fontsource/atkinson-hyperlegible-next/400.css';
import '@fontsource/atkinson-hyperlegible-next/700.css';
import '@fontsource/atkinson-hyperlegible-mono/400.css';
import '@fontsource/atkinson-hyperlegible-mono/700.css';
import './styles/tokens.css';
import './styles/app.css';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
