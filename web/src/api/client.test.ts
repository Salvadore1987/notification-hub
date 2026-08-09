import { beforeEach, describe, expect, it, vi } from 'vitest';

import { initTestApi, mockApi, TEST_API_BASE_URL } from '../test/api';
import { api, setAccessTokenProvider } from './client';
import { ApiError } from './problem';

describe('admin api client', () => {
  beforeEach(() => {
    initTestApi();
    setAccessTokenProvider(() => null);
  });

  it('sends the access token of the session, and nothing when there is none', async () => {
    const calls = mockApi({ 'GET /dashboard': { body: { totals: {} } } });

    await api().GET('/dashboard', {});
    setAccessTokenProvider(() => 'token-123');
    await api().GET('/dashboard', {});

    expect(calls.calls[0].headers.get('Authorization')).toBeNull();
    expect(calls.calls[1].headers.get('Authorization')).toBe('Bearer token-123');
  });

  it('addresses the BFF at the configured base url', async () => {
    const calls = mockApi({ 'GET /dashboard': { body: {} } });

    await api().GET('/dashboard', { params: { query: { includeTest: true } } });

    expect(calls.calls[0].path).toBe('/dashboard');
    expect(calls.calls[0].query.get('includeTest')).toBe('true');
    expect(TEST_API_BASE_URL).toContain('/api/admin/v1');
  });

  it('turns any non-2xx into one ApiError carrying problem+json (IR-01)', async () => {
    mockApi({
      'GET /dashboard': {
        status: 409,
        body: { title: 'Conflict', detail: 'configuration changed', code: 'CONFLICT' },
      },
    });

    const error = await api()
      .GET('/dashboard', {})
      .catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(409);
    expect((error as ApiError).problem?.code).toBe('CONFLICT');
    expect((error as ApiError).message).toBe('configuration changed');
  });

  it('carries the parsed Retry-After of the limiter', async () => {
    mockApi({
      'GET /dashboard': {
        status: 429,
        body: { title: 'Too Many' },
        headers: { 'Retry-After': '7' },
      },
    });

    const error = (await api()
      .GET('/dashboard', {})
      .catch((e: unknown) => e)) as ApiError;

    expect(error.retryAfterSeconds).toBe(7);
  });

  it('survives an error body that is not problem+json', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response('<html>502</html>', { status: 502 })),
    );
    initTestApi();

    const error = (await api()
      .GET('/dashboard', {})
      .catch((e: unknown) => e)) as ApiError;

    expect(error.status).toBe(502);
    expect(error.problem).toBeUndefined();
    expect(error.message).toBe('HTTP 502');
  });

  it('refuses to be used before the deployment configuration was read', async () => {
    vi.resetModules();
    const fresh = await import('./client');

    expect(() => fresh.api()).toThrow(/initApi/);
  });
});
