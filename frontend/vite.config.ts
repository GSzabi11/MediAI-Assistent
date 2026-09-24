import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Dev módban a /api kéréseket a Spring Boot backendre továbbítjuk (CORS nélkül).
// Másik backendhez (pl. teszt-példány): MEDIAI_PROXY_TARGET=http://localhost:8081 npm run dev
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': process.env.MEDIAI_PROXY_TARGET ?? 'http://localhost:8080',
    },
  },
});
