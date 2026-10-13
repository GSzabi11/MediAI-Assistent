import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

// A közös beállítások a repó gyökerében lévő .env-ből jönnek (sablon: .env.example);
// a böngészőbe csak a VITE_ előtagúak kerülnek.
const ENV_DIR = '..';

// Dev módban a /api kéréseket a Spring Boot backendre továbbítjuk (CORS nélkül).
// Másik backendhez (pl. teszt-példány): MEDIAI_PROXY_TARGET=http://localhost:8081 npm run dev
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, ENV_DIR, '');
  return {
    envDir: ENV_DIR,
    plugins: [react()],
    server: {
      proxy: {
        '/api': process.env.MEDIAI_PROXY_TARGET || env.MEDIAI_PROXY_TARGET || 'http://localhost:8080',
      },
    },
  };
});
