import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { clearReferenceCache } from '../../shared/useReference';
import { mockApi, type ApiMock } from '../../test/api';
import { renderWithProviders } from '../../test/render';
import { ProviderFormModal } from './ProviderFormModal';

/**
 * Регистрация провайдера: тип адаптера выбирается из того, что развёрнуто на контуре (AR-04).
 *
 * Проверяется не список сам по себе, а три решения вокруг него: фильтр по каналу, сброс типа при
 * смене канала и деградация в ручной ввод, если список не пришёл.
 */
describe('ProviderFormModal', () => {
  let api: ApiMock;

  const ADAPTERS = [
    { adapterType: 'smtp', channel: 'EMAIL' },
    { adapterType: 'playmobile-http', channel: 'SMS' },
    { adapterType: 'smsgate-http', channel: 'SMS' },
  ];

  beforeEach(() => {
    clearReferenceCache();
    api = mockApi({ 'GET /providers/adapters': { body: ADAPTERS } });
  });

  function renderForm(onSubmit = vi.fn().mockResolvedValue(true)) {
    renderWithProviders(
      <ProviderFormModal open initial={null} onSubmit={onSubmit} onCancel={() => {}} />,
      { roles: ['ADMIN'] },
    );
    return onSubmit;
  }

  /** Внутри Form.Item метка связана с полем, поэтому обёртку ищем от него. */
  function selection(label: string): Element | null {
    const select = screen.getByLabelText(label).closest('.ant-select');
    return select?.querySelector('.ant-select-selection-item') ?? null;
  }

  async function chooseChannel(channel: string) {
    await userEvent.click(screen.getByLabelText('Канал'));
    await userEvent.click(await screen.findByTitle(channel));
  }

  it('AR-04: предлагает адаптеры выбранного канала и не предлагает чужие', async () => {
    // Arrange
    renderForm();
    await chooseChannel('SMS');

    // Act
    await userEvent.click(screen.getByLabelText('Тип адаптера'));

    // Assert — оба SMS-адаптера есть, почтовый не предлагается: канал и тип сверяются вместе
    expect(await screen.findByTitle('playmobile-http')).toBeInTheDocument();
    expect(screen.getByTitle('smsgate-http')).toBeInTheDocument();
    expect(screen.queryByTitle('smtp')).not.toBeInTheDocument();
  });

  it('смена канала сбрасывает выбранный тип адаптера', async () => {
    // Arrange
    renderForm();
    await chooseChannel('SMS');
    await userEvent.click(screen.getByLabelText('Тип адаптера'));
    await userEvent.click(await screen.findByTitle('playmobile-http'));
    await waitFor(() => expect(selection('Тип адаптера')).toHaveTextContent('playmobile-http'));

    // Act
    await chooseChannel('EMAIL');

    // Assert — иначе EMAIL-профиль уехал бы с SMS-адаптером и не отправил бы ничего
    await waitFor(() => expect(selection('Тип адаптера')).toBeNull());
  });

  it('деградирует в ручной ввод, если список адаптеров недоступен', async () => {
    // Arrange — справочник отвечает отказом; регистрация из-за этого встать не должна
    clearReferenceCache();
    api = mockApi({
      'GET /providers/adapters': { status: 500, body: { title: 'error', status: 500 } },
    });
    const onSubmit = renderForm();

    // Act — у выпадающего списка antd внутренний input имеет role="combobox"; у обычного поля нет
    await waitFor(() =>
      expect(screen.getByLabelText('Тип адаптера')).not.toHaveAttribute('role', 'combobox'),
    );
    await userEvent.type(screen.getByLabelText('Код'), 'PLAYMOBILE');
    await chooseChannel('SMS');
    await userEvent.type(screen.getByLabelText('Тип адаптера'), 'playmobile-http');
    await userEvent.click(screen.getByRole('button', { name: 'Сохранить' }));

    // Assert — оператор, знающий код, набирает его руками и регистрирует профиль
    await waitFor(() => expect(onSubmit).toHaveBeenCalled());
    expect(onSubmit.mock.calls[0][0]).toBe('PLAYMOBILE');
    expect(onSubmit.mock.calls[0][1]).toMatchObject({
      channel: 'SMS',
      adapterType: 'playmobile-http',
    });
    expect(api.lastCall('GET /providers/adapters')).toBeDefined();
  });
});
