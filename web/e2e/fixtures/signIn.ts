import { expect, type Page } from '@playwright/test';

/**
 * Вход демо-администратором через форму самой панели (ADR-0043).
 *
 * <p>Раньше вход проходили один раз на прогон и переиспользовали SSO-cookie Keycloak. С direct
 * grant'ом переиспользовать нечего: браузер к издателю не ходит вовсе, а токен живёт в
 * `sessionStorage`, который Playwright в `storageState` не сохраняет (и это решение SEC-02, а не
 * деталь). Поэтому входит каждый тест — это один POST на token endpoint, дешевле прежнего редиректа.
 *
 * <p>Keycloak при этом настоящий: подменять издателя незачем, вход — единственное, что в E2E
 * ходит по-настоящему.
 */
export async function signIn(page: Page, username = 'demo', password = 'demo'): Promise<void> {
  const login = page.getByLabel('Пользователь');
  const panel = page.locator('.ant-layout-sider').first();
  // Ждём того, что отрисовалось первым: форму (сессии нет) или саму панель (вход уже был).
  await expect(login.or(panel).first()).toBeVisible({ timeout: 30_000 });
  if (!(await login.isVisible())) {
    return;
  }
  await login.fill(username);
  await page.getByLabel('Пароль').fill(password);
  await page.getByRole('button', { name: 'Войти' }).click();
  await expect(login).toBeHidden({ timeout: 30_000 });
}
