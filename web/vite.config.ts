import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

// Dev-прокси ведёт на локальный bootstrap (./gradlew :bootstrap:bootRun, порт 8080), чтобы
// SPA и BFF в разработке жили на одном origin — так же, как в развёрнутом контуре (§11.2).
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: false,
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: true,
  },
});
