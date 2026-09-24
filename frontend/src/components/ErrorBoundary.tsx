import { Component, type ErrorInfo, type ReactNode } from 'react';
import { Icon } from './Icon';

/**
 * Ha egy komponens renderelés közben hibát dob, React nélküle az EGÉSZ alkalmazást
 * eltávolítja (fehér oldal). Klinikai felületen az orvos nem maradhat üres
 * képernyővel: itt egy érthető hibaállapot jelenik meg, újratöltéssel.
 * A szülő `key`-vel (útvonal) újraindítja, így navigáláskor a hiba nem ragad be.
 */
export class ErrorBoundary extends Component<{ children: ReactNode }, { error: Error | null }> {
  state: { error: Error | null } = { error: null };

  static getDerivedStateFromError(error: Error) {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('Renderelési hiba:', error, info.componentStack);
  }

  render() {
    if (!this.state.error) return this.props.children;
    return (
      <main className="listpage" role="alert">
        <div className="state state--error">
          <Icon name="alert" />
          <span>Váratlan hiba történt a nézet megjelenítésekor: {this.state.error.message}</span>
        </div>
        <div className="row-gap-10">
          <button type="button" className="btn btn--primary" onClick={() => window.location.reload()}>
            Oldal újratöltése
          </button>
          <a className="btn" href="/patients">
            Vissza a betegekhez
          </a>
        </div>
      </main>
    );
  }
}
