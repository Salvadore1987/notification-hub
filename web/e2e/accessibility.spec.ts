import AxeBuilder from '@axe-core/playwright';

import { expect, test } from './fixtures/adminApi';

/**
 * Доступность (UI-01): axe-core на экранах, которыми пользуются во время инцидента. Проверяются
 * правила WCAG 2.1 A/AA, кроме контраста — палитра Ant Design это тема оформления, а не наш код,
 * и её замечания не должны прятать настоящие: пропущенную подпись поля или неразмеченную таблицу.
 *
 * Отдельно проверяется язык документа: он должен меняться вместе с выбранным языком панели, иначе
 * экранный диктор читает русский текст английскими правилами.
 */
/**
 * Замечание antd, которое нечем починить в нашем коде: Form.Item ставит aria-required на обёртку
 * Select'а — узел без роли, которому этот атрибут не положен. Разметку рисует библиотека, а
 * выключать правило целиком нельзя: тогда перестанут ловиться наши собственные ошибки.
 */
const KNOWN_ANTD_ISSUES = ['aria-allowed-attr: .ant-select-in-form-item'];

/** Нарушение показывается вместе с узлом: «label» без селектора нечего чинить. */
function describe(
  violations: { id: string; help: string; nodes: { target: unknown[] }[] }[],
): string[] {
  return violations
    .map(
      (violation) =>
        `${violation.id}: ${violation.help} → ${violation.nodes.map((node) => node.target.join(' ')).join(', ')}`,
    )
    .filter(
      (line) =>
        !KNOWN_ANTD_ISSUES.some(
          (known) =>
            line.startsWith(known.split(': ')[0] + ':') && line.includes(known.split(': ')[1]),
        ),
    );
}

const SCREENS = [
  { path: '/dashboard', name: 'дашборд' },
  { path: '/batches', name: 'рассылки' },
  { path: '/dlq', name: 'DLQ' },
  { path: '/templates/OTP_LOGIN', name: 'карточка шаблона' },
  { path: '/providers', name: 'каналы и провайдеры' },
];

for (const screen of SCREENS) {
  test(`${screen.name} не нарушает правила доступности`, async ({ page }) => {
    await page.goto(screen.path);
    await page.getByRole('main').waitFor();

    const results = await new AxeBuilder({ page })
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
      .disableRules(['color-contrast'])
      .analyze();

    expect(describe(results.violations)).toEqual([]);
  });
}

test('форма тестовой отправки размечена подписями, а не только placeholder', async ({ page }) => {
  await page.goto('/providers');
  await page.getByRole('tab', { name: 'Тестовая отправка' }).click();

  await expect(page.getByLabel('Поток')).toBeVisible();
  await expect(page.getByLabel('MSISDN')).toBeVisible();

  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa'])
    .disableRules(['color-contrast'])
    .analyze();

  expect(describe(results.violations)).toEqual([]);
});

test('язык документа следует за выбранным языком панели (UI-01)', async ({ page }) => {
  await page.goto('/dashboard');
  await expect(page.locator('html')).toHaveAttribute('lang', 'ru');

  // Кликается видимая часть Select'а: сам combobox-input антд перекрывает выбранным значением.
  await page.getByTitle('Рус').click();
  await page.locator('.ant-select-item-option[title="Eng"]').click();

  await expect(page.getByRole('menu').getByText('Dashboard')).toBeVisible();
  await expect(page.locator('html')).toHaveAttribute('lang', 'en');
});
