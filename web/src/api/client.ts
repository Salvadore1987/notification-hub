import createClient, { type Client, type Middleware } from 'openapi-fetch';

import type { AppConfig } from '../config/appConfig';
import type { paths } from './generated/admin-schema';
import { ApiError, parseRetryAfter, type ProblemDetail } from './problem';

/**
 * Типизированный клиент к Admin BFF: типы сгенерированы из comm-hub-admin-v1.yaml
 * (npm run generate:api) — контракт ведётся рядом с контроллерами и проверяется
 * AdminOpenApiContractTest, поэтому расхождение типов ловится регенерацией, а не в проде.
 *
 * Токен приходит через провайдер, а не импортом из auth: клиент — модуль, сессия — React-контекст,
 * и указатель — единственная стрелка между ними, направленная сюда.
 */
let accessTokenProvider: () => string | null = () => null;

export function setAccessTokenProvider(provider: () => string | null): void {
  accessTokenProvider = provider;
}

const authMiddleware: Middleware = {
  async onRequest({ request }) {
    const token = accessTokenProvider();
    if (token) {
      request.headers.set('Authorization', `Bearer ${token}`);
    }
    return request;
  },
};

/**
 * Любой не-2xx превращается в ApiError здесь, один раз, поэтому у экранов ветка ошибки одна
 * и data на успехе всегда есть. Тело читается как problem+json, если BFF его прислал (IR-01).
 */
const problemMiddleware: Middleware = {
  async onResponse({ response }) {
    if (response.ok) {
      return response;
    }
    let problem: ProblemDetail | undefined;
    try {
      problem = (await response.clone().json()) as ProblemDetail;
    } catch {
      problem = undefined;
    }
    throw new ApiError(
      response.status,
      problem,
      parseRetryAfter(response.headers.get('Retry-After')),
    );
  },
};

let client: Client<paths> | null = null;

export function initApi(config: AppConfig): void {
  client = createClient<paths>({ baseUrl: config.apiBaseUrl });
  client.use(authMiddleware);
  client.use(problemMiddleware);
}

export function api(): Client<paths> {
  if (!client) {
    throw new Error('initApi(config) must run before the first request');
  }
  return client;
}
