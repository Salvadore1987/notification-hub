import { expect, test } from './fixtures/adminApi';

/**
 * QA-07: публикация шаблона. Путь тот же, что у ревьюера: каталог → карточка → версия на ревью →
 * «Опубликовать». Переходы workflow FR-4.2 предлагаются по статусу версии, право решает backend.
 */
test.describe('Шаблоны', () => {
  test('ревьюер публикует версию, ждущую ревью', async ({ page, admin }) => {
    await page.goto('/templates');
    await page.getByRole('cell', { name: 'OTP_LOGIN' }).click();

    await expect(page).toHaveURL(/\/templates\/OTP_LOGIN$/);
    const version = page.getByRole('row', { name: /ON_REVIEW/ });
    await expect(version).toBeVisible();

    await version.getByRole('button', { name: 'Опубликовать' }).click();

    await expect(page.getByRole('row', { name: /PUBLISHED/ })).toBeVisible();
    expect(
      admin.lastRequest('POST /templates/OTP_LOGIN/versions/RU/2/state/PUBLISHED'),
    ).toBeDefined();
  });

  test('версии на ревью предлагается и отклонение, но не редактирование', async ({ page }) => {
    await page.goto('/templates/OTP_LOGIN');

    const version = page.getByRole('row', { name: /ON_REVIEW/ });
    await expect(version.getByRole('button', { name: 'Отклонить' })).toBeVisible();
    await expect(version.getByRole('button', { name: 'Изменить' })).toHaveCount(0);
  });

  test('отказ публикации показывается и статус не меняется', async ({ page, admin }) => {
    // Ровно то, чем отвечает настоящий backend: Guard домена — это DomainValidationException,
    // то есть 400 VALIDATION_FAILED с английским detail, а не 409 с русским текстом.
    admin.stub(
      'POST /templates/OTP_LOGIN/versions/RU/2/state/PUBLISHED',
      {
        title: 'Validation failed',
        detail: 'maker/checker: the author of a template version may not publish it (FR-4.2)',
        status: 400,
      },
      400,
    );
    await page.goto('/templates/OTP_LOGIN');

    await page
      .getByRole('row', { name: /ON_REVIEW/ })
      .getByRole('button', { name: 'Опубликовать' })
      .click();

    await expect(page.getByText(/maker\/checker/)).toBeVisible();
    await expect(page.getByRole('row', { name: /ON_REVIEW/ })).toBeVisible();
  });
});
