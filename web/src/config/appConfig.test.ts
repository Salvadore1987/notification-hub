import { describe, expect, it, vi } from 'vitest';

import { DEFAULT_CONFIG, loadAppConfig } from './appConfig';

function respondWith(body: unknown, status = 200): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => new Response(JSON.stringify(body), { status })),
  );
}

describe('loadAppConfig', () => {
  it('reads the deployment configuration and keeps defaults for what it omits', async () => {
    respondWith({ oidc: { authority: 'https://sso.bank.uz/realms/hub' }, rolesClaim: 'roles' });

    const config = await loadAppConfig();

    expect(config.oidc.authority).toBe('https://sso.bank.uz/realms/hub');
    expect(config.oidc.clientId).toBe(DEFAULT_CONFIG.oidc.clientId);
    expect(config.oidc.scope).toBe(DEFAULT_CONFIG.oidc.scope);
    expect(config.rolesClaim).toBe('roles');
    expect(config.apiBaseUrl).toBe(DEFAULT_CONFIG.apiBaseUrl);
  });

  it('keeps the group mapping as given', async () => {
    respondWith({ groupRoles: { 'cn=hub-admins': 'ADMIN' } });

    await expect(loadAppConfig()).resolves.toMatchObject({
      groupRoles: { 'cn=hub-admins': 'ADMIN' },
    });
  });

  it('reports a missing config.json as an unconfigured panel, never as an open one', async () => {
    respondWith({}, 404);

    const config = await loadAppConfig();

    expect(config).toEqual(DEFAULT_CONFIG);
    // Пустой issuer — это экран «панель не настроена» (ADR-0037), а не доступ без аутентификации.
    expect(config.oidc.authority).toBe('');
  });

  it('survives an unreadable config.json — a blank page would say even less', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => {
        throw new TypeError('offline');
      }),
    );

    const config = await loadAppConfig();

    expect(config).toEqual(DEFAULT_CONFIG);
    expect(config.oidc.authority).toBe('');
  });

  it('reads the file past the cache — a redeployed contour must not get yesterday copy', async () => {
    const fetchMock = vi.fn(async () => new Response('{}', { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);

    await loadAppConfig();

    expect(fetchMock).toHaveBeenCalledWith('/config.json', { cache: 'no-store' });
  });
});
