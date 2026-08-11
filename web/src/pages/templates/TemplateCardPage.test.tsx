import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it } from 'vitest';

import { mockApi, type ApiMock } from '../../test/api';
import { renderWithProviders } from '../../test/render';
import { TemplateCardPage } from './TemplateCardPage';

/**
 * Workflow версии шаблона (FR-4.1, FR-4.2).
 *
 * Проверяется прежде всего словарь статусов: панель однажды уже посылала `IN_REVIEW`, которого нет
 * ни в домене, ни в CHECK-ограничении, и черновик было невозможно провести до публикации — тост
 * «Запрос не выполнен» и статус на месте. Поэтому тест смотрит на путь запроса, а не на то, что
 * кнопка нарисована.
 */
describe('TemplateCardPage', () => {
  let api: ApiMock;

  function card(status: string, createdBy = 'template-manager') {
    return {
      templateId: '018f-t-0001',
      code: 'OTP_LOGIN',
      channel: 'SMS',
      direction: 'сервисные',
      owner: 'retail',
      catalogStatus: 'ACTIVE',
      versions: [
        {
          versionId: '018f-v-0001',
          version: 1,
          locale: 'RU',
          body: { text: 'Ваш код: {CODE}' },
          status,
          variables: ['CODE'],
          review: { createdBy },
        },
      ],
      providerMappings: [],
    };
  }

  function render(status: string, createdBy?: string, userName = 'reviewer') {
    api = mockApi({
      'GET /templates/OTP_LOGIN': { body: card(status, createdBy) },
      'POST /templates/OTP_LOGIN/versions/RU/1/state/ON_REVIEW': { body: {} },
    });
    // Экран берёт код из пути, поэтому монтируется через маршрут, а не напрямую.
    renderWithProviders(
      <Routes>
        <Route path="/templates/:code" element={<TemplateCardPage />} />
      </Routes>,
      { roles: ['TEMPLATE_MANAGER'], route: '/templates/OTP_LOGIN', userName },
    );
  }

  beforeEach(() => {
    api = mockApi({});
  });

  it('FR-4.1: черновик отправляется на ревью статусом, который знает домен', async () => {
    // Arrange
    render('DRAFT');
    await screen.findByRole('button', { name: 'На ревью' });

    // Act
    await userEvent.click(screen.getByRole('button', { name: 'На ревью' }));

    // Assert — именно ON_REVIEW: IN_REVIEW возвращал 400 и версия оставалась черновиком
    await waitFor(() =>
      expect(api.lastCall('POST /templates/OTP_LOGIN/versions/RU/1/state/ON_REVIEW')).toBeDefined(),
    );
  });

  it('версия на ревью предлагает публикацию, отклонение и архив, и не роняет экран', async () => {
    // Arrange + Act
    render('ON_REVIEW');

    // Assert — до починки словаря NEXT_STATES['ON_REVIEW'] был undefined и рендер падал
    expect(await screen.findByRole('button', { name: 'Опубликовать' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Отклонить' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Архивировать' })).toBeInTheDocument();
  });

  it('FR-4.2: автор версии не может её опубликовать — кнопка заблокирована до запроса', async () => {
    // Arrange + Act — вошедший и автор версии совпадают
    render('ON_REVIEW', 'reviewer', 'reviewer');

    // Assert
    await waitFor(() => expect(screen.getByRole('button', { name: 'Опубликовать' })).toBeDisabled());
    expect(api.calls.filter((call) => call.path.includes('/state/'))).toHaveLength(0);
  });
});
