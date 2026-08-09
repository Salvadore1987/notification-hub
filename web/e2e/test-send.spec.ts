import { expect, test } from './fixtures/adminApi';

/**
 * QA-07: тестовая отправка FR-7.4. Отправка идёт обычным конвейером, а адрес, который набрал
 * оператор, показывается в подтверждении маскированным (DB-04) — панель не печатает MSISDN целиком.
 */
test.describe('Тестовая отправка', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/providers');
    await page.getByRole('tab', { name: 'Тестовая отправка' }).click();
  });

  test('администратор отправляет тестовое SMS и видит маскированный адрес', async ({
    page,
    admin,
  }) => {
    await page.getByLabel('Поток').fill('core-banking');
    await page.getByLabel('MSISDN').fill('998901234567');
    await page.getByLabel('Текст').fill('проверка канала');

    await page.getByRole('button', { name: 'Отправить' }).click();

    await expect(page.getByText('Результат приёма')).toBeVisible();
    await expect(page.getByText('99890***4567')).toBeVisible();
    await expect(page.getByText('998901234567', { exact: true })).toHaveCount(0);
    await expect(page.getByText('018f-message-0001')).toBeVisible();

    const send = admin.lastRequest('POST /providers/test-send');
    expect(send?.body).toMatchObject({
      streamId: 'core-banking',
      channel: 'SMS',
      recipient: { msisdn: '998901234567' },
      text: 'проверка канала',
    });
  });

  test('незаполненная форма не уходит на backend', async ({ page, admin }) => {
    await page.getByRole('button', { name: 'Отправить' }).click();

    await expect(page.locator('.ant-form-item-explain-error').first()).toBeVisible();
    expect(admin.lastRequest('POST /providers/test-send')).toBeUndefined();
  });

  test('отказ конвейера показывается оператору как есть (IR-01)', async ({ page, admin }) => {
    admin.stub(
      'POST /providers/test-send',
      { title: 'Unprocessable', detail: 'PAN_DETECTED', code: 'PAN_DETECTED', status: 422 },
      422,
    );

    await page.getByLabel('Поток').fill('core-banking');
    await page.getByLabel('MSISDN').fill('998901234567');
    await page.getByRole('button', { name: 'Отправить' }).click();

    await expect(page.getByText(/PAN_DETECTED/)).toBeVisible();
    await expect(page.getByText('Результат приёма')).toHaveCount(0);
  });
});
