import { expect, test as setup } from '@playwright/test';

import { STORAGE_STATE } from './authState';

/**
 * Один вход демо-администратором на весь прогон (ADR-0037: открытого режима больше нет).
 *
 * Что именно переиспользуется, стоит назвать точно: Playwright сохраняет cookies и localStorage,
 * но **не** sessionStorage — а oidc-client-ts держит пользователя именно там, и это решение SEC-02,
 * а не деталь. Значит сохранённый артефакт — не токен панели, а SSO-cookie самого Keycloak. Каждый
 * тест по-прежнему проходит редирект на issuer, но форму логина видит только этот файл: дальше
 * Keycloak узнаёт свою cookie и возвращает код молча.
 *
 * Заглушки BFF здесь нет намеренно — проверяется вход, а не экран, — поэтому дашборд после логина
 * запрашивает настоящий backend и vite пишет в вывод ECONNREFUSED. Это ожидаемо: значение имеет
 * только то, что панель отрисовалась и назвала пользователя.
 */

setup('демо-администратор входит через Keycloak', async ({ page }) => {
  await page.goto('/dashboard');

  await page.waitForURL(/\/realms\/commhub\/protocol\/openid-connect\/auth/);
  // По роли, а не по getByLabel: в теме Keycloak поле пароля обёрнуто кнопкой «показать пароль»
  // и с надписью связано только доступным именем, без label[for].
  await page.getByRole('textbox', { name: /Username|Пользователь/i }).fill('demo');
  await page.getByRole('textbox', { name: /Password|Пароль/i }).fill('demo');
  await page.getByRole('button', { name: /Sign In|Войти/i }).click();

  // Возврат именно на запрошенный экран — то, ради чего в signinRedirect кладётся returnTo.
  await page.waitForURL(/\/dashboard$/);
  await expect(page.getByText('demo')).toBeVisible();

  await page.context().storageState({ path: STORAGE_STATE });
});
