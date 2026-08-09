import { vi } from 'vitest';

import { initApi } from '../api/client';
import { DEFAULT_CONFIG } from '../config/appConfig';

/**
 * Заглушка Admin BFF для компонентных тестов: маршруты объявляются как «GET /dlq», ответ —
 * тело или {status, body}. Базовый URL абсолютный, потому что Request в Node не принимает
 * относительный; на экранах он приходит из config.json и остаётся относительным.
 */
export const TEST_API_BASE_URL = 'http://localhost/api/admin/v1';

export interface ApiCall {
  readonly method: string;
  readonly path: string;
  readonly query: URLSearchParams;
  readonly headers: Headers;
  readonly body: unknown;
}

export interface StubResponse {
  readonly status?: number;
  readonly body?: unknown;
  readonly headers?: Record<string, string>;
}

export type Handler = StubResponse | ((call: ApiCall) => StubResponse);

export interface ApiMock {
  /** Все запросы в порядке отправки — по ним проверяется, что именно ушло на backend. */
  readonly calls: ApiCall[];
  readonly lastCall: (key: string) => ApiCall | undefined;
}

function isStubResponse(handler: Handler): handler is StubResponse {
  return typeof handler !== 'function';
}

/**
 * openapi-fetch запоминает globalThis.fetch в момент создания клиента, поэтому заглушка ставится
 * до initApi — и клиент пересоздаётся здесь же, чтобы порядок нельзя было перепутать в тесте.
 */
export function mockApi(handlers: Record<string, Handler>): ApiMock {
  const calls: ApiCall[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: Request | string) => {
      const request = input instanceof Request ? input : new Request(input);
      const url = new URL(request.url);
      const path = url.pathname.replace('/api/admin/v1', '');
      const text = await request.clone().text();
      const call: ApiCall = {
        method: request.method,
        path,
        query: url.searchParams,
        headers: request.headers,
        body: text ? safeJson(text) : undefined,
      };
      calls.push(call);
      const handler = handlers[`${request.method} ${path}`];
      if (!handler) {
        return jsonResponse({ status: 404, body: { title: 'no stub', status: 404 } });
      }
      return jsonResponse(isStubResponse(handler) ? handler : handler(call));
    }),
  );
  initTestApi();
  return {
    calls,
    lastCall: (key) => {
      const [method, path] = key.split(' ');
      return [...calls].reverse().find((call) => call.method === method && call.path === path);
    },
  };
}

export function initTestApi(): void {
  initApi({ ...DEFAULT_CONFIG, apiBaseUrl: TEST_API_BASE_URL });
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

function jsonResponse(stub: StubResponse): Response {
  const status = stub.status ?? 200;
  const body = stub.body === undefined ? null : JSON.stringify(stub.body);
  return new Response(body, {
    status,
    headers: {
      'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json',
      ...stub.headers,
    },
  });
}
