import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { clearReferenceCache } from '../../shared/useReference';
import { mockApi, type ApiMock } from '../../test/api';
import { renderWithProviders } from '../../test/render';
import { ChannelsTab } from './ChannelsTab';

/**
 * Каналов ровно три и они перечисление (AR-05), а профиль канала заводится первой же правкой.
 *
 * Проверяется именно это: на неразвёрнутом контуре `GET /channels` отдаёт пустой список, и без
 * синтетических строк настроить канал было бы неоткуда — отправка отказывала бы
 * `NO_ROUTE_AVAILABLE / channel SMS is not configured`, а панель не предлагала бы ни одной кнопки.
 */
describe('ChannelsTab', () => {
  let api: ApiMock;

  const PROVIDERS = [{ providerId: '018f-p', code: 'PM', channel: 'SMS' }];

  function rowOf(channel: string): HTMLElement {
    return screen.getByRole('row', { name: new RegExp(`^${channel}\\b`) });
  }

  beforeEach(() => {
    clearReferenceCache();
  });

  it('показывает все три канала, когда не настроен ни один', async () => {
    // Arrange
    api = mockApi({ 'GET /channels': { body: [] }, 'GET /providers': { body: PROVIDERS } });

    // Act
    renderWithProviders(<ChannelsTab />, { roles: ['ADMIN'] });

    // Assert — строка есть у каждого канала, и у неё одно действие: завести профиль
    await waitFor(() => expect(api.lastCall('GET /channels')).toBeDefined());
    for (const channel of ['SMS', 'EMAIL', 'PUSH']) {
      expect(within(rowOf(channel)).getByText('не настроен')).toBeInTheDocument();
      expect(within(rowOf(channel)).getByRole('button', { name: 'Настроить' })).toBeInTheDocument();
    }
    // Смена состояния несуществующего профиля — 409, поэтому кнопок состояния быть не должно
    expect(screen.queryByRole('button', { name: 'DISABLED' })).not.toBeInTheDocument();
  });

  it('заводит профиль канала тем же PUT, каким его потом правят', async () => {
    // Arrange
    api = mockApi({
      'GET /channels': { body: [] },
      'GET /providers': { body: PROVIDERS },
      'PUT /channels/SMS': { body: { channel: 'SMS', status: 'ACTIVE', fallbackOrder: ['PM'] } },
    });
    renderWithProviders(<ChannelsTab />, { roles: ['ADMIN'] });
    await waitFor(() => expect(api.lastCall('GET /channels')).toBeDefined());

    // Act
    await userEvent.click(within(rowOf('SMS')).getByRole('button', { name: 'Настроить' }));
    await userEvent.click(await screen.findByLabelText('Балансировка'));
    await userEvent.click(await screen.findByTitle('PRIMARY_ONLY'));
    await userEvent.click(screen.getByLabelText('Порядок fallback'));
    await userEvent.click(await screen.findByTitle('PM'));
    await userEvent.click(screen.getByRole('button', { name: 'Сохранить' }));

    // Assert — стратегия из словаря домена, а не из выдуманного контрактом PRIORITY
    await waitFor(() => expect(api.lastCall('PUT /channels/SMS')).toBeDefined());
    expect(api.lastCall('PUT /channels/SMS')?.body).toMatchObject({
      balancingStrategy: 'PRIMARY_ONLY',
      fallbackOrder: ['PM'],
    });
  });

  it('не отправляет профиль без стратегии — BFF требует её как обязательную', async () => {
    // Arrange
    api = mockApi({ 'GET /channels': { body: [] }, 'GET /providers': { body: PROVIDERS } });
    renderWithProviders(<ChannelsTab />, { roles: ['ADMIN'] });
    await waitFor(() => expect(api.lastCall('GET /channels')).toBeDefined());

    // Act
    await userEvent.click(within(rowOf('SMS')).getByRole('button', { name: 'Настроить' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Сохранить' }));

    // Assert — 400 с перечислением в тексте оператору не помогает, поэтому отказ рисует форма.
    // Подсветку antd рисует классом, роли у неё нет, поэтому ищется узел, а не текст.
    await waitFor(() =>
      expect(document.querySelector('.ant-form-item-explain-error')).not.toBeNull(),
    );
    expect(api.lastCall('PUT /channels/SMS')).toBeUndefined();
  });

  it('настроенный канал остаётся правкой, с кнопками состояния', async () => {
    // Arrange
    api = mockApi({
      'GET /channels': {
        body: [
          {
            channel: 'SMS',
            status: 'ACTIVE',
            balancingStrategy: 'PRIMARY_ONLY',
            fallbackOrder: ['PM'],
            available: true,
          },
        ],
      },
      'GET /providers': { body: PROVIDERS },
    });

    // Act
    renderWithProviders(<ChannelsTab />, { roles: ['ADMIN'] });

    // Assert
    await waitFor(() => expect(api.lastCall('GET /channels')).toBeDefined());
    expect(within(rowOf('SMS')).getByRole('button', { name: 'Изменить' })).toBeInTheDocument();
    expect(within(rowOf('SMS')).getByRole('button', { name: 'DISABLED' })).toBeInTheDocument();
    expect(within(rowOf('EMAIL')).getByText('не настроен')).toBeInTheDocument();
  });
});
