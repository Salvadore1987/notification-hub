import { defineConfig, devices } from '@playwright/test';

import { STORAGE_STATE } from './e2e/authState';

/**
 * E2E критических сценариев QA-07: пауза батча, повтор из DLQ, публикация шаблона, тестовая
 * отправка — плюс проверка доступности (axe) на тех же экранах.
 *
 * Backend по-прежнему не поднимается: Admin BFF подменяется на уровне сети
 * (`e2e/fixtures/adminApi.ts`), так сценарий проверяет саму панель, а не контур. Контракт BFF при
 * этом не выдуман: ответы заглушки — тела схем comm-hub-admin-v1.yaml.
 *
 * А вот **Keycloak настоящий** (ADR-0037: открытого режима нет). Проект `setup` один раз проходит
 * форму логина демо-пользователем и сохраняет SSO-cookie; остальные тесты стартуют с ней и проходят
 * редирект молча. Один хост на всё — `localhost`: для Keycloak `localhost` и `127.0.0.1` разные
 * origin'ы, и redirect_uri, выданный с одного, не подойдёт другому.
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: 0,
  reporter: process.env.CI ? 'line' : [['list']],
  globalSetup: './e2e/global-setup.ts',
  use: {
    baseURL: 'http://localhost:5173',
    trace: 'retain-on-failure',
    locale: 'ru-RU',
    timezoneId: 'Asia/Tashkent',
  },
  projects: [
    { name: 'setup', testMatch: /auth\.setup\.ts/ },
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'], storageState: STORAGE_STATE },
      dependencies: ['setup'],
    },
  ],
  webServer: {
    command: 'npm run dev -- --host localhost --port 5173',
    url: 'http://localhost:5173',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
});
