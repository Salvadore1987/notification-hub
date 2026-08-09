import { createContext, useContext } from 'react';

/**
 * Конфигурация развёртывания, а не сборки: SPA собирается один раз, а контуров у Банка несколько,
 * поэтому issuer, client id и маппинг групп читаются из /config.json при старте — та же позиция,
 * что у backend'а, где issuer и group→role mapping приходят из deployment-конфигурации (SEC-02).
 */
export interface OidcSettings {
  /** URL OIDC-провайдера (issuer). Пустая строка — SSO не настроен, панель в open mode. */
  readonly authority: string;
  readonly clientId: string;
  readonly scope: string;
}

export interface AppConfig {
  readonly apiBaseUrl: string;
  readonly oidc: OidcSettings;
  /** Claim токена с SSO-группами; зеркалит commhub.security.roles-claim (по умолчанию "groups"). */
  readonly rolesClaim: string;
  /** SSO-группа → роль §10.1. Группа, совпадающая с именем роли, маппится сама на себя. */
  readonly groupRoles: Readonly<Record<string, string>>;
}

export const DEFAULT_CONFIG: AppConfig = {
  apiBaseUrl: '/api/admin/v1',
  oidc: { authority: '', clientId: 'commhub-admin', scope: 'openid profile offline_access' },
  rolesClaim: 'groups',
  groupRoles: {},
};

/**
 * Недоступный или битый config.json не роняет панель, а оставляет дефолты: относительный
 * apiBaseUrl и open mode — то же поведение, что у контура без настроенного SSO.
 */
export async function loadAppConfig(): Promise<AppConfig> {
  try {
    const response = await fetch('/config.json', { cache: 'no-store' });
    if (!response.ok) {
      return DEFAULT_CONFIG;
    }
    const raw = (await response.json()) as Partial<AppConfig>;
    return {
      apiBaseUrl: raw.apiBaseUrl ?? DEFAULT_CONFIG.apiBaseUrl,
      oidc: { ...DEFAULT_CONFIG.oidc, ...raw.oidc },
      rolesClaim: raw.rolesClaim ?? DEFAULT_CONFIG.rolesClaim,
      groupRoles: raw.groupRoles ?? DEFAULT_CONFIG.groupRoles,
    };
  } catch {
    return DEFAULT_CONFIG;
  }
}

const AppConfigContext = createContext<AppConfig>(DEFAULT_CONFIG);

export const AppConfigProvider = AppConfigContext.Provider;

export function useAppConfig(): AppConfig {
  return useContext(AppConfigContext);
}
