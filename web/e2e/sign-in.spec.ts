import { expect, test } from './fixtures/adminApi';

/**
 * Вход в панель (ADR-0043) против настоящего Keycloak: остальные сценарии проходят его молча
 * фикстурой, а здесь он и есть предмет проверки.
 *
 * <p>Проверяется то, ради чего форму перенесли в панель и что ломается незаметно: глубокая ссылка
 * переживает вход (адрес не уходит на издателя и возвращается тем же), неверный пароль — это
 * сообщение, а не пустой экран, а выход возвращает форму.
 */
test('глубокая ссылка переживает вход: оператор попадает на запрошенный экран', async ({
  page,
}) => {
  // Фикстура сама заполняет форму и ждёт экран — проверяется результат: остались на /dlq.
  await page.goto('/dlq');

  await expect(page).toHaveURL(/\/dlq$/);
  await expect(page.getByRole('row', { name: /018f-0000-0000-0000-000000000001/ })).toBeVisible();
  await expect(page.getByText('demo')).toBeVisible();
});

test('неверный пароль остаётся отказом с объяснением, а не пустым экраном', async ({ page }) => {
  await page.goto('/dashboard');
  await page.getByText('demo').click();
  await page.getByRole('menuitem', { name: 'Выйти' }).click();

  await page.getByLabel('Пользователь').fill('demo');
  await page.getByLabel('Пароль').fill('не тот пароль');
  await page.getByRole('button', { name: 'Войти' }).click();

  await expect(page.getByText('Неверное имя пользователя или пароль')).toBeVisible();
  await expect(page.getByLabel('Пользователь')).toBeVisible();
});
