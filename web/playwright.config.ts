import { defineConfig, devices } from '@playwright/test';

/**
 * E2E критических сценариев QA-07: пауза батча, повтор из DLQ, публикация шаблона, тестовая
 * отправка — плюс проверка доступности (axe) на тех же экранах.
 *
 * Backend по-прежнему не поднимается: Admin BFF подменяется на уровне сети
 * (`e2e/fixtures/adminApi.ts`), так сценарий проверяет саму панель, а не контур. Контракт BFF при
 * этом не выдуман: ответы заглушки — тела схем comm-hub-admin-v1.yaml.
 *
 * А вот **Keycloak настоящий** (ADR-0037: открытого режима нет). Форма входа теперь своя
 * (ADR-0043), поэтому переиспользовать между тестами нечего — токен живёт в `sessionStorage`,
 * который `storageState` не возит, — и каждый тест входит сам через фикстуру `page`. Один хост на
 * всё — `localhost`: у Keycloak `localhost` и `127.0.0.1` разные origin'ы, и CORS, разрешённый
 * одному, другому не подойдёт.
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
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'npm run dev -- --host localhost --port 5173',
    url: 'http://localhost:5173',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
});
