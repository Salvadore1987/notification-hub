import { expect, test } from './fixtures/adminApi';

/**
 * QA-07: разбор DLQ. Повтор уходит явным списком id (позиция контракта — очередь никогда не
 * повторяется «по фильтру»), а архивирование требует обоснования FR-7.3.
 */
test.describe('DLQ', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/dlq');
  });

  test('оператор повторяет выбранные сообщения и они уходят из очереди', async ({
    page,
    admin,
  }) => {
    const row = page.getByRole('row', { name: /018f-0000-0000-0000-000000000001/ });
    await row.getByRole('checkbox').check();

    await page.getByRole('button', { name: /Повторить выбранные \(1\)/ }).click();

    await expect(page.getByText('Применено: 1')).toBeVisible();
    await expect(row).toHaveCount(0);

    const retry = admin.lastRequest('POST /dlq/retry');
    expect(retry?.body).toEqual({ messageIds: ['018f-0000-0000-0000-000000000001'] });
  });

  test('повтор одной строки не трогает остальные', async ({ page, admin }) => {
    await page
      .getByRole('row', { name: /018f-0000-0000-0000-000000000002/ })
      .getByRole('button', { name: 'Повторить' })
      .click();

    await expect(page.getByText('Применено: 1')).toBeVisible();
    expect(admin.lastRequest('POST /dlq/retry')?.body).toEqual({
      messageIds: ['018f-0000-0000-0000-000000000002'],
    });
    await expect(page.getByRole('row', { name: /018f-0000-0000-0000-000000000001/ })).toBeVisible();
  });

  test('архивирование записывает обоснование в заголовок (FR-7.3)', async ({ page, admin }) => {
    await page
      .getByRole('row', { name: /018f-0000-0000-0000-000000000001/ })
      .getByRole('button', { name: 'Архивировать' })
      .click();
    await page.getByPlaceholder('Обоснование попадёт в журнал аудита').fill('разобрано вручную');
    await page.getByRole('button', { name: 'Подтвердить' }).click();

    await expect(page.getByText('Применено: 1')).toBeVisible();
    const archive = admin.lastRequest('POST /dlq/archive');
    expect(decodeURIComponent(archive!.headers['x-commhub-reason'])).toBe('разобрано вручную');
  });

  test('отказ BFF показывается оператору, а не молчит', async ({ page, admin }) => {
    admin.stub(
      'POST /dlq/retry',
      { title: 'Conflict', detail: 'сообщение уже повторено', status: 409 },
      409,
    );

    await page
      .getByRole('row', { name: /018f-0000-0000-0000-000000000001/ })
      .getByRole('button', { name: 'Повторить' })
      .click();

    await expect(page.getByText(/уже повторено/)).toBeVisible();
  });
});
