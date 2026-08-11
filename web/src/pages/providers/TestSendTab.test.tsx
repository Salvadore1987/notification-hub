import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { initTestApi, mockApi } from '../../test/api';
import { renderWithProviders } from '../../test/render';
import { clearReferenceCache } from '../../shared/useReference';
import { TestSendTab } from './TestSendTab';

const ACCEPTED = { messageId: '018f-ccc', status: 'ACCEPTED' };

/** Поток и провайдер теперь выбираются из справочника — его заглушки нужны каждому тесту. */
const REFERENCE = {
  'GET /streams': { body: [{ streamId: 'core-banking', name: 'Ядро', status: 'ACTIVE' }] },
  'GET /providers': { body: [{ providerId: '018f-p', code: 'PLAYMOBILE', channel: 'SMS' }] },
};

/** Выбор потока из списка вместо ввода строки. */
async function chooseStream() {
  await userEvent.click(screen.getByLabelText('Поток'));
  await userEvent.click(await screen.findByTitle('core-banking — Ядро'));
}

describe('TestSendTab', () => {
  beforeEach(() => {
    clearReferenceCache();
    initTestApi();
  });

  it('sends the form through the ordinary pipeline endpoint (FR-7.4)', async () => {
    const calls = mockApi({ ...REFERENCE, 'POST /providers/test-send': { body: ACCEPTED } });
    renderWithProviders(<TestSendTab />, { roles: ['ADMIN'] });

    await chooseStream();
    await userEvent.type(screen.getByLabelText('MSISDN'), '998901234567');
    await userEvent.type(screen.getByLabelText('Текст'), 'проверка канала');
    await userEvent.click(screen.getByRole('button', { name: 'Отправить' }));

    await waitFor(() => expect(calls.lastCall('POST /providers/test-send')).toBeDefined());
    expect(calls.lastCall('POST /providers/test-send')?.body).toMatchObject({
      streamId: 'core-banking',
      channel: 'SMS',
      recipient: { msisdn: '998901234567' },
      text: 'проверка канала',
    });
  });

  it('masks the address the operator typed in the confirmation (DB-04)', async () => {
    mockApi({ ...REFERENCE, 'POST /providers/test-send': { body: ACCEPTED } });
    renderWithProviders(<TestSendTab />, { roles: ['ADMIN'] });

    await chooseStream();
    await userEvent.type(screen.getByLabelText('MSISDN'), '998901234567');
    await userEvent.click(screen.getByRole('button', { name: 'Отправить' }));

    expect(await screen.findByText('Результат приёма')).toBeInTheDocument();
    expect(screen.getByText(/99890\*\*\*4567/)).toBeInTheDocument();
    expect(screen.queryByText(/Получатель: 998901234567/)).not.toBeInTheDocument();
    expect(screen.getByText('018f-ccc', { exact: false })).toBeInTheDocument();
  });

  it('asks for the address the chosen channel needs, and only that one', async () => {
    mockApi({ ...REFERENCE, 'POST /providers/test-send': { body: ACCEPTED } });
    renderWithProviders(<TestSendTab />, { roles: ['ADMIN'] });

    expect(screen.getByLabelText('MSISDN')).toBeInTheDocument();
    expect(screen.queryByLabelText('Email')).not.toBeInTheDocument();

    await userEvent.click(screen.getByLabelText('Канал'));
    await userEvent.click(await screen.findByTitle('EMAIL'));

    expect(await screen.findByLabelText('Email')).toBeInTheDocument();
    expect(screen.queryByLabelText('MSISDN')).not.toBeInTheDocument();
  });

  it('does not send an incomplete form — the required fields are marked instead', async () => {
    const calls = mockApi({ ...REFERENCE, 'POST /providers/test-send': { body: ACCEPTED } });
    renderWithProviders(<TestSendTab />, { roles: ['ADMIN'] });

    await userEvent.click(screen.getByRole('button', { name: 'Отправить' }));

    await waitFor(() => expect(screen.getAllByRole('alert').length).toBeGreaterThan(0));
    expect(calls.lastCall('POST /providers/test-send')).toBeUndefined();
  });

  it('shows the rejection of the pipeline as it came, and no result card', async () => {
    mockApi({
      ...REFERENCE,
      'POST /providers/test-send': {
        status: 422,
        body: { detail: 'PAN_DETECTED', code: 'PAN_DETECTED' },
      },
    });
    renderWithProviders(<TestSendTab />, { roles: ['ADMIN'] });

    await chooseStream();
    await userEvent.type(screen.getByLabelText('MSISDN'), '998901234567');
    await userEvent.click(screen.getByRole('button', { name: 'Отправить' }));

    expect(await screen.findByText(/PAN_DETECTED/)).toBeInTheDocument();
    expect(screen.queryByText('Результат приёма')).not.toBeInTheDocument();
  });
});
