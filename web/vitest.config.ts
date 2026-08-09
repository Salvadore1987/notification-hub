import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

/**
 * Конфигурация unit/компонентных тестов (Phase 18). Отдельный файл, а не поле в vite.config.ts:
 * сборке не нужно знать про тесты, а тестам не нужен dev-прокси на bootstrap.
 *
 * Окружение — jsdom: проверяются компоненты и чистые модули, а сценарии целиком (QA-07) живут
 * в Playwright (`e2e/`), где браузер настоящий и проверяется ещё и доступность.
 */
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    restoreMocks: true,
    unstubGlobals: true,
    coverage: {
      provider: 'v8',
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/api/generated/**', 'src/test/**', 'src/main.tsx', 'src/**/*.test.{ts,tsx}'],
      reporter: ['text', 'html'],
    },
  },
});
