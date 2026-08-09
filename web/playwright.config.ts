import { defineConfig, devices } from '@playwright/test';

/**
 * E2E критических сценариев QA-07: пауза батча, повтор из DLQ, публикация шаблона, тестовая
 * отправка — плюс проверка доступности (axe) на тех же экранах.
 *
 * Backend не поднимается: Admin BFF подменяется на уровне сети (`e2e/fixtures/adminApi.ts`), а
 * панель работает в open mode (`public/config.json` без issuer) — так сценарий проверяет саму
 * панель, а не контур. Контракт BFF при этом не выдуман: ответы заглушки — тела схем
 * comm-hub-admin-v1.yaml, а типы этих же схем проверяются unit-тестами.
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: 0,
  reporter: process.env.CI ? 'line' : [['list']],
  use: {
    baseURL: 'http://127.0.0.1:5173',
    trace: 'retain-on-failure',
    locale: 'ru-RU',
    timezoneId: 'Asia/Tashkent',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 5173',
    url: 'http://127.0.0.1:5173',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
});
