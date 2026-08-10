import { createContext, useContext } from 'react';

import { SECTION_ROLES, type Role, type Section } from './roles';

/**
 * Сессия, какой её видят экраны: роли и гейтинг, без деталей OIDC. Провайдеры живут в
 * session.tsx; здесь — контракт и хук, отдельно, чтобы файл с компонентами экспортировал
 * только компоненты (fast refresh).
 */
export interface Session {
  readonly userName?: string;
  readonly roles: readonly Role[];
  readonly canSee: (section: Section) => boolean;
  readonly signOut: () => void;
}

export const SessionContext = createContext<Session | null>(null);

export function useSession(): Session {
  const session = useContext(SessionContext);
  if (!session) {
    throw new Error('useSession outside SessionProvider');
  }
  return session;
}

export function makeSession(
  roles: readonly Role[],
  userName: string | undefined,
  signOut: () => void,
): Session {
  return {
    userName,
    roles,
    canSee: (section) => SECTION_ROLES[section].some((role) => roles.includes(role)),
    signOut,
  };
}
