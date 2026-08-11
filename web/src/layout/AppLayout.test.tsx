import { screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { renderWithProviders } from '../test/render';
import { AppLayout } from './AppLayout';
import { NAV_ITEMS } from './navigation';

const menu = () => within(screen.getByRole('menu'));

describe('AppLayout menu', () => {
  it('shows an administrator every section of §11.2', () => {
    renderWithProviders(<AppLayout />, { roles: ['ADMIN'] });

    for (const item of NAV_ITEMS) {
      expect(menu().getByText(labelOf(item.key))).toBeInTheDocument();
    }
  });

  it('shows an operator only what an operator may open', () => {
    renderWithProviders(<AppLayout />, { roles: ['OPERATOR'] });

    expect(menu().getByText('DLQ')).toBeInTheDocument();
    expect(menu().getByText('Рассылки')).toBeInTheDocument();
    expect(menu().queryByText('Маршрутизация')).not.toBeInTheDocument();
    expect(menu().queryByText('Аудит')).not.toBeInTheDocument();
  });

  it('leaves an analyst statistics and the dashboard, and nothing to act with', () => {
    renderWithProviders(<AppLayout />, { roles: ['ANALYST'] });

    expect(menu().getByText('Статистика')).toBeInTheDocument();
    expect(menu().getByText('Дашборд')).toBeInTheDocument();
    expect(menu().queryByText('DLQ')).not.toBeInTheDocument();
    expect(menu().queryByText('Сообщения')).not.toBeInTheDocument();
  });

  it('names the signed-in operator — there is no session without one (ADR-0037)', () => {
    const signOut = vi.fn();
    renderWithProviders(<AppLayout />, { roles: ['ADMIN'], userName: 'demo', signOut });

    expect(screen.getByText('demo')).toBeInTheDocument();
  });
});

function labelOf(key: string): string {
  const labels: Record<string, string> = {
    dashboard: 'Дашборд',
    batches: 'Рассылки',
    send: 'Отправка',
    messages: 'Сообщения',
    dlq: 'DLQ',
    streams: 'Входящие потоки',
    providers: 'Каналы и провайдеры',
    routing: 'Маршрутизация',
    templates: 'Шаблоны',
    suppressions: 'Suppression list',
    statistics: 'Статистика',
    audit: 'Аудит',
    administration: 'Администрирование',
  };
  return labels[key];
}
