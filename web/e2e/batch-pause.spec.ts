import { expect, test } from './fixtures/adminApi';

/**
 * QA-07: оператор ставит рассылку на паузу. Проверяется весь путь — список, карточка, действие
 * FR-3.2 с обоснованием FR-7.3 и то, что статус в карточке изменился.
 */
test.describe('Рассылки', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/batches');
  });

  test('оператор ставит рассылку на паузу и видит новый статус', async ({ page, admin }) => {
    await page.getByRole('cell', { name: '018f-batch-0001' }).click();

    const card = page.getByRole('dialog');
    await expect(card.getByText('PROCESSING')).toBeVisible();

    await card.getByRole('button', { name: 'Приостановить' }).click();
    await page
      .getByPlaceholder('Обоснование попадёт в журнал аудита')
      .fill('всплеск отказов провайдера');
    await page.getByRole('button', { name: 'Подтвердить' }).click();

    await expect(card.getByText('PAUSED')).toBeVisible();

    const action = admin.lastRequest('POST /batches/018f-batch-0001/actions/pause');
    expect(action).toBeDefined();
    expect(decodeURIComponent(action!.headers['x-commhub-reason'])).toBe(
      'всплеск отказов провайдера',
    );
  });

  test('передумав в модалке обоснования, оператор ничего не отправляет', async ({
    page,
    admin,
  }) => {
    await page.getByRole('cell', { name: '018f-batch-0001' }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Приостановить' }).click();
    await page.getByRole('button', { name: 'Отмена' }).click();

    await expect(page.getByRole('dialog').getByText('PROCESSING')).toBeVisible();
    expect(admin.lastRequest('POST /batches/018f-batch-0001/actions/pause')).toBeUndefined();
  });

  test('drill-down открывает сообщения рассылки как фильтр списка, а не отдельный экран', async ({
    page,
    admin,
  }) => {
    await page.getByRole('cell', { name: '018f-batch-0001' }).click();
    await page.getByRole('button', { name: 'Сообщения рассылки' }).click();

    await expect(page).toHaveURL(/\/messages/);
    await expect(page.getByPlaceholder('ID рассылки')).toHaveValue('018f-batch-0001');
    await expect
      .poll(() => admin.lastRequest('GET /messages')?.query.get('batchId'))
      .toBe('018f-batch-0001');
  });
});
