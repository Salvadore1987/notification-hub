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

  it('warns in the header when the contour runs without SSO and hides the sign-out', () => {
    renderWithProviders(<AppLayout />, { open: true });

    expect(screen.getByText('SSO не настроен — панель работает без аутентификации')).toBeVisible();
    expect(screen.queryByText('tester')).not.toBeInTheDocument();
  });

  it('names the signed-in operator when SSO is configured', () => {
    const signOut = vi.fn();
    renderWithProviders(<AppLayout />, { roles: ['ADMIN'], signOut });

    expect(screen.getByText('tester')).toBeInTheDocument();
    expect(
      screen.queryByText('SSO не настроен — панель работает без аутентификации'),
    ).not.toBeInTheDocument();
  });
});

function labelOf(key: string): string {
  const labels: Record<string, string> = {
    dashboard: 'Дашборд',
    batches: 'Рассылки',
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
