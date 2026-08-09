import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../test/render';
import { RequireSection } from './RequireSection';

/**
 * FR-7.2: гейт дублирует @PreAuthorize, а не заменяет его — прямой переход по URL без роли
 * должен упереться в 403 здесь, чтобы оператор увидел причину, а не пустой экран.
 */
describe('RequireSection', () => {
  it('shows the section to a role that owns it', () => {
    renderWithProviders(
      <RequireSection section="operator">
        <div>очередь DLQ</div>
      </RequireSection>,
      { roles: ['OPERATOR'] },
    );

    expect(screen.getByText('очередь DLQ')).toBeInTheDocument();
  });

  it('answers 403 to a role that does not, and renders nothing of the screen', () => {
    renderWithProviders(
      <RequireSection section="admin">
        <div>настройки провайдеров</div>
      </RequireSection>,
      { roles: ['VIEWER'] },
    );

    expect(screen.getByText('403')).toBeInTheDocument();
    expect(screen.getByText('У вас нет роли для этого раздела')).toBeInTheDocument();
    expect(screen.queryByText('настройки провайдеров')).not.toBeInTheDocument();
  });

  it('lets a contour without SSO through — the panel warns instead of refusing (open mode)', () => {
    renderWithProviders(
      <RequireSection section="admin">
        <div>настройки провайдеров</div>
      </RequireSection>,
      { open: true },
    );

    expect(screen.getByText('настройки провайдеров')).toBeInTheDocument();
  });
});
