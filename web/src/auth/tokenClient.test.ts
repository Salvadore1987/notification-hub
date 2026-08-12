import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { OidcSettings } from '../config/appConfig';
import { endSession, passwordGrant, refreshGrant, SignInError } from './tokenClient';

/**
 * Разговор с издателем (ADR-0043). Проверяется не Keycloak, а то, что панель отличает три отказа,
 * которые оператору значат разное: «не тот пароль», «издателя нет» и «издатель отказал по своей
 * причине» — и что пароль уходит ровно туда, куда сказал discovery.
 *
 * <p>authority у каждого теста свой: адреса издателя кэшируются на модуле, и общий authority
 * заставил бы тесты зависеть от порядка.
 */
const TOKEN = 'https://issuer.test/protocol/openid-connect/token';
const LOGOUT = 'https://issuer.test/protocol/openid-connect/logout';

let issuers = 0;

function oidc(): OidcSettings {
  issuers += 1;
  return {
    authority: `https://issuer.test/realms/r${issuers}`,
    clientId: 'commhub-admin',
    scope: 'openid profile',
  };
}

function discovery(): Response {
  return new Response(JSON.stringify({ token_endpoint: TOKEN, end_session_endpoint: LOGOUT }), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function bodyOf(call: number): URLSearchParams {
  return new URLSearchParams(String(fetchMock.mock.calls[call][1]?.body));
}

describe('tokenClient', () => {
  it('exchanges the credentials at the endpoint the issuer named', async () => {
    fetchMock
      .mockResolvedValueOnce(discovery())
      .mockResolvedValueOnce(json({ access_token: 'at', refresh_token: 'rt', expires_in: 300 }));

    const tokens = await passwordGrant(oidc(), 'demo', 'secret');

    expect(fetchMock.mock.calls[0][0]).toContain('/.well-known/openid-configuration');
    expect(fetchMock.mock.calls[1][0]).toBe(TOKEN);
    expect(Object.fromEntries(bodyOf(1))).toEqual({
      grant_type: 'password',
      client_id: 'commhub-admin',
      scope: 'openid profile',
      username: 'demo',
      password: 'secret',
    });
    expect(tokens.accessToken).toBe('at');
    expect(tokens.refreshToken).toBe('rt');
    expect(tokens.expiresAt).toBeGreaterThan(Date.now());
  });

  it('reads invalid_grant as a wrong password, not as an unavailable issuer', async () => {
    fetchMock
      .mockResolvedValueOnce(discovery())
      .mockResolvedValueOnce(
        json({ error: 'invalid_grant', error_description: 'Invalid user credentials' }, 401),
      );

    const error = await passwordGrant(oidc(), 'demo', 'wrong').catch((e: unknown) => e);

    expect(error).toBeInstanceOf(SignInError);
    expect((error as SignInError).failure).toBe('invalidCredentials');
  });

  it("keeps the issuer's own reason for any other refusal", async () => {
    fetchMock
      .mockResolvedValueOnce(discovery())
      .mockResolvedValueOnce(
        json({ error: 'invalid_request', error_description: 'Account is not fully set up' }, 400),
      );

    const error = (await passwordGrant(oidc(), 'demo', 'demo').catch(
      (e: unknown) => e,
    )) as SignInError;

    expect(error.failure).toBe('rejected');
    expect(error.detail).toBe('Account is not fully set up');
  });

  it('calls an unreachable issuer an issuer problem, and never the operator', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch'));

    const error = (await passwordGrant(oidc(), 'demo', 'demo').catch(
      (e: unknown) => e,
    )) as SignInError;

    expect(error.failure).toBe('issuerUnavailable');
  });

  it('renews without the password — the panel does not keep it', async () => {
    fetchMock
      .mockResolvedValueOnce(discovery())
      .mockResolvedValueOnce(json({ access_token: 'at2', refresh_token: 'rt2', expires_in: 300 }));

    await refreshGrant(oidc(), 'rt');

    expect(Object.fromEntries(bodyOf(1))).toEqual({
      grant_type: 'refresh_token',
      client_id: 'commhub-admin',
      refresh_token: 'rt',
    });
  });

  it('ends the session at the issuer on sign-out, and stays quiet when it cannot', async () => {
    fetchMock.mockResolvedValueOnce(discovery()).mockRejectedValueOnce(new TypeError('offline'));

    await expect(endSession(oidc(), 'rt')).resolves.toBeUndefined();

    expect(fetchMock.mock.calls[1][0]).toBe(LOGOUT);
  });
});
