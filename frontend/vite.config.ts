import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Dev módban a /api kéréseket a Spring Boot backendre továbbítjuk (CORS nélkül).
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
});
