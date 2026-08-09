import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { initTestApi, mockApi, type ApiCall } from '../../test/api';
import { renderWithProviders } from '../../test/render';
import { DlqPage } from './DlqPage';

/** Плейсхолдер модалки обоснования — на экране есть и другие поля ввода (пикер периода). */
const REASON_PLACEHOLDER = 'Обоснование попадёт в журнал аудита';

const ENTRIES = [
  {
    messageId: '018f-aaa',
    reason: 'PROVIDER_REJECTED',
    lastError: 'code 7: temporary failure',
    movedAt: '2026-08-09T05:00:00Z',
    retryable: true,
    archived: false,
  },
  {
    messageId: '018f-bbb',
    reason: 'ATTEMPTS_EXHAUSTED',
    lastError: 'no answer',
    movedAt: '2026-08-09T06:00:00Z',
    retryable: false,
    archived: true,
  },
];

function stubQueue(overrides: Record<string, Parameters<typeof mockApi>[0][string]> = {}) {
  return mockApi({
    'GET /dlq': { body: { items: ENTRIES, total: 2 } },
    'POST /dlq/retry': { body: { applied: ['018f-aaa'], skipped: [] } },
    'POST /dlq/archive': { body: { applied: ['018f-aaa'], skipped: [] } },
    ...overrides,
  });
}

describe('DlqPage', () => {
  beforeEach(() => {
    initTestApi();
  });

  it('lists the queue with the reason and the moment it stopped', async () => {
    stubQueue();

    renderWithProviders(<DlqPage />, { roles: ['OPERATOR'] });

    expect(await screen.findByText('018f-aaa')).toBeInTheDocument();
    expect(screen.getByText('PROVIDER_REJECTED')).toBeInTheDocument();
    expect(screen.getByText('09.08.2026 10:00:00')).toBeInTheDocument();
  });

  it('retries exactly the ids the operator picked — never a filter (contract position)', async () => {
    const calls = stubQueue();
    renderWithProviders(<DlqPage />, { roles: ['OPERATOR'] });
    await screen.findByText('018f-aaa');

    await userEvent.click(rowCheckbox('018f-aaa'));
    await userEvent.click(screen.getByRole('button', { name: /Повторить выбранные/ }));

    await waitFor(() => expect(calls.lastCall('POST /dlq/retry')).toBeDefined());
    expect(calls.lastCall('POST /dlq/retry')?.body).toEqual({ messageIds: ['018f-aaa'] });
  });

  it('re-reads the page after a retry, so the queue is not stale', async () => {
    const calls = stubQueue();
    renderWithProviders(<DlqPage />, { roles: ['OPERATOR'] });
    await screen.findByText('018f-aaa');
    const reads = calls.calls.filter((call: ApiCall) => call.path === '/dlq').length;

    await userEvent.click(within(rowOf('018f-aaa')).getByRole('button', { name: 'Повторить' }));

    await waitFor(() =>
      expect(calls.calls.filter((call: ApiCall) => call.path === '/dlq').length).toBe(reads + 1),
    );
  });

  it('reports a partial result as it came back, applied and skipped', async () => {
    stubQueue({
      'POST /dlq/retry': { body: { applied: ['018f-aaa'], skipped: ['018f-bbb'] } },
    });
    renderWithProviders(<DlqPage />, { roles: ['OPERATOR'] });
    await screen.findByText('018f-aaa');

    await userEvent.click(within(rowOf('018f-aaa')).getByRole('button', { name: 'Повторить' }));

    expect(await screen.findByText('Применено: 1, пропущено: 1')).toBeInTheDocument();
  });

  it('asks for a justification before archiving and sends it as X-Commhub-Reason (FR-7.3)', async () => {
    const calls = stubQueue();
    renderWithProviders(<DlqPage />, { roles: ['OPERATOR'] });
    await screen.findByText('018f-aaa');

    await userEvent.click(within(rowOf('018f-aaa')).getByRole('button', { name: 'Архивировать' }));
    await userEvent.type(
      await screen.findByPlaceholderText(REASON_PLACEHOLDER),
      'разобрано вручную',
    );
    await userEvent.click(screen.getByRole('button', { name: 'Подтвердить' }));

    await waitFor(() => expect(calls.lastCall('POST /dlq/archive')).toBeDefined());
    const call = calls.lastCall('POST /dlq/archive');
    // Заголовок уходит percent-encoded: значение заголовка — байтовая строка, кириллицы в ней нет.
    expect(call?.headers.get('X-Commhub-Reason')).toBe(encodeURIComponent('разобрано вручную'));
    expect(call?.body).toEqual({ messageIds: ['018f-aaa'] });
  });

  it('sends nothing when the operator cancels the justification', async () => {
    const calls = stubQueue();
    renderWithProviders(<DlqPage />, { roles: ['OPERATOR'] });
    await screen.findByText('018f-aaa');

    await userEvent.click(within(rowOf('018f-aaa')).getByRole('button', { name: 'Архивировать' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Отмена' }));

    expect(calls.lastCall('POST /dlq/archive')).toBeUndefined();
  });

  it('offers no retry for an entry the backend called non-retryable', async () => {
    stubQueue();
    renderWithProviders(<DlqPage />, { roles: ['OPERATOR'] });
    await screen.findByText('018f-bbb');

    expect(within(rowOf('018f-bbb')).getByRole('button', { name: 'Повторить' })).toBeDisabled();
    expect(within(rowOf('018f-bbb')).getByRole('button', { name: 'Архивировать' })).toBeDisabled();
  });

  it('applies the filters of the screen to the query', async () => {
    const calls = stubQueue();
    renderWithProviders(<DlqPage />, { roles: ['OPERATOR'] });
    await screen.findByText('018f-aaa');

    await userEvent.click(screen.getByText('Показывать архивные'));

    await waitFor(() =>
      expect(calls.lastCall('GET /dlq')?.query.get('includeArchived')).toBe('true'),
    );
    expect(calls.lastCall('GET /dlq')?.query.get('limit')).toBe('20');
    expect(calls.lastCall('GET /dlq')?.query.get('offset')).toBe('0');
  });

  it('shows the failure of the queue read instead of an empty table', async () => {
    stubQueue({ 'GET /dlq': { status: 503, body: { detail: 'database is down' } } });

    renderWithProviders(<DlqPage />, { roles: ['OPERATOR'] });

    expect(await screen.findByText(/database is down/)).toBeInTheDocument();
  });
});

function rowOf(messageId: string): HTMLElement {
  return screen.getByText(messageId).closest('tr') as HTMLElement;
}

function rowCheckbox(messageId: string): HTMLElement {
  return within(rowOf(messageId)).getByRole('checkbox');
}
