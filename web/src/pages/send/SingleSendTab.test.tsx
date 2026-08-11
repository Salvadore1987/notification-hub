import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { renderWithProviders } from '../../test/render';
import { clearReferenceCache } from '../../shared/useReference';
import { mockApi, type ApiMock } from '../../test/api';
import { SingleSendTab } from './SingleSendTab';

/** Отправка из панели: смета обязательна, обоснование обязательно (ADR-0038, FR-7.3). */
describe('SingleSendTab', () => {
  let api: ApiMock;

  const STREAMS = [{ streamId: 'payroll', name: 'Зарплатный проект', status: 'ACTIVE' }];
  const TEMPLATES = [
    {
      templateId: '018f-t',
      code: 'PAYROLL',
      channel: 'SMS',
      direction: 'сервисные',
      catalogStatus: 'ACTIVE',
      publishedLocales: ['RU'],
    },
  ];

  const estimate = {
    recipients: 1,
    segments: 1,
    estimatedCost: '241.0000',
    provider: 'PLAYMOBILE',
    template: { version: 3, status: 'PUBLISHED' },
    missingVariables: [],
    rejection: null,
    failures: [],
  };

  beforeEach(() => {
    clearReferenceCache();
    api = mockApi({
      'GET /streams': { body: STREAMS },
      'GET /templates': { body: { items: TEMPLATES, total: 1, limit: 500, offset: 0 } },
      'POST /send/estimate': { body: estimate },
      'POST /send/message': { body: { messageId: '018f-0000-0000', status: 'QUEUED' } },
    });
  });

  /** Поток и шаблон теперь выбираются из справочника, а не набираются. */
  async function fillTheForm() {
    await userEvent.click(screen.getByLabelText('Поток'));
    await userEvent.click(await screen.findByTitle('payroll — Зарплатный проект'));
    await userEvent.click(screen.getByLabelText('Код шаблона'));
    await userEvent.click(await screen.findByTitle('PAYROLL — сервисные'));
    await userEvent.type(screen.getByLabelText('MSISDN'), '998901234567');
  }

  it('рассчитывает смету перед отправкой и не шлёт ничего до подтверждения', async () => {
    // Arrange
    renderWithProviders(<SingleSendTab />, { roles: ['OPERATOR'] });
    await fillTheForm();

    // Act
    await userEvent.click(screen.getByRole('button', { name: 'Рассчитать смету' }));

    // Assert — смета показана, но отправки ещё не было
    await waitFor(() => expect(screen.getByText('Подтверждение отправки')).toBeInTheDocument());
    expect(screen.getByText('PLAYMOBILE')).toBeInTheDocument();
    expect(api.calls.filter((call) => call.path === '/send/message')).toHaveLength(0);
  });

  it('FR-7.3: спрашивает обоснование и отправляет его заголовком', async () => {
    // Arrange
    renderWithProviders(<SingleSendTab />, { roles: ['OPERATOR'] });
    await fillTheForm();
    await userEvent.click(screen.getByRole('button', { name: 'Рассчитать смету' }));
    await waitFor(() => expect(screen.getByText('Подтверждение отправки')).toBeInTheDocument());

    // Act
    await userEvent.click(screen.getByRole('button', { name: 'Отправить' }));
    const reason = await screen.findByPlaceholderText('Обоснование попадёт в журнал аудита');
    await userEvent.type(reason, 'клиент просил продублировать');
    await userEvent.click(screen.getByRole('button', { name: 'Подтвердить' }));

    // Assert
    await waitFor(() => expect(api.lastCall('POST /send/message')).toBeDefined());
    const sent = api.lastCall('POST /send/message');
    expect(sent?.headers.get('X-Commhub-Reason')).toBe(
      encodeURIComponent('клиент просил продублировать'),
    );
    expect(sent?.body).toMatchObject({ templateCode: 'PAYROLL', streamId: 'payroll' });
  });

  it('деградирует в ручной ввод, если справочник недоступен', async () => {
    // Arrange — списки отвечают отказом, как это было бы при нехватке прав
    clearReferenceCache();
    api = mockApi({
      'GET /streams': { status: 403, body: { title: 'forbidden', status: 403 } },
      'GET /templates': { status: 403, body: { title: 'forbidden', status: 403 } },
      'POST /send/estimate': { body: estimate },
    });
    renderWithProviders(<SingleSendTab />, { roles: ['OPERATOR'] });

    // Act — поля остались вводом, и оператор, знающий коды, набирает их руками
    // У выпадающего списка antd внутренний input имеет role="combobox"; у обычного поля его нет.
    await waitFor(() =>
      expect(screen.getByLabelText('Поток')).not.toHaveAttribute('role', 'combobox'),
    );
    await userEvent.type(screen.getByLabelText('Поток'), 'payroll');
    await userEvent.type(screen.getByLabelText('Код шаблона'), 'PAYROLL');
    await userEvent.type(screen.getByLabelText('MSISDN'), '998901234567');
    await userEvent.click(screen.getByRole('button', { name: 'Рассчитать смету' }));

    // Assert — недоступный список не должен стоить оператору отправки
    await waitFor(() => expect(api.lastCall('POST /send/estimate')).toBeDefined());
    expect(api.lastCall('POST /send/estimate')?.body).toMatchObject({
      streamId: 'payroll',
      templateCode: 'PAYROLL',
    });
  });

  it('не даёт подтвердить отправку, для которой нет маршрута', async () => {
    // Arrange
    api = mockApi({
      'GET /streams': { body: STREAMS },
      'GET /templates': { body: { items: TEMPLATES, total: 1, limit: 500, offset: 0 } },
      'POST /send/estimate': {
        body: {
          ...estimate,
          rejection: { reason: 'NO_ROUTE_AVAILABLE', detail: 'нет провайдера' },
        },
      },
    });
    clearReferenceCache();
    renderWithProviders(<SingleSendTab />, { roles: ['OPERATOR'] });
    await fillTheForm();

    // Act
    await userEvent.click(screen.getByRole('button', { name: 'Рассчитать смету' }));

    // Assert
    await waitFor(() => expect(screen.getByText('NO_ROUTE_AVAILABLE')).toBeInTheDocument());
    expect(screen.getByRole('button', { name: 'Отправить' })).toBeDisabled();
  });
});
