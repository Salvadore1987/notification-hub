import { App as AntApp } from 'antd';
import { render, type RenderResult } from '@testing-library/react';
import type { ReactNode } from 'react';
import { MemoryRouter } from 'react-router-dom';

import '../i18n';
import { makeSession, SessionContext } from '../auth/sessionContext';
import { ROLES, type Role } from '../auth/roles';

/**
 * Рендер экрана в том же окружении, что и в приложении: i18n (RU по умолчанию), AntApp — для
 * message/modal, роутер в памяти и сессия с заданными ролями. Роли — параметр теста, потому что
 * гейтинг §11.2 проверяется тем же способом, каким работает: через SessionContext.
 */
export interface RenderOptions {
  readonly roles?: readonly Role[];
  readonly open?: boolean;
  readonly route?: string;
  readonly signOut?: () => void;
}

export function renderWithProviders(ui: ReactNode, options: RenderOptions = {}): RenderResult {
  const roles = options.roles ?? ROLES;
  const session = makeSession(
    roles,
    options.open ?? false,
    options.open ? undefined : 'tester',
    options.signOut ?? (() => {}),
  );
  return render(
    <MemoryRouter initialEntries={[options.route ?? '/']}>
      <SessionContext.Provider value={session}>
        <AntApp>{ui}</AntApp>
      </SessionContext.Provider>
    </MemoryRouter>,
  );
}
