import type { OidcSettings } from '../config/appConfig';

/**
 * Единственное место панели, знающее протокол OIDC по проводам (ADR-0043).
 *
 * <p>Форма входа живёт в самой панели, поэтому токен добывается direct access grant'ом
 * (`grant_type=password`, RFC 6749 §4.3): логин и пароль уходят одним form-post'ом на token
 * endpoint издателя. Библиотеки здесь нет намеренно — это два запроса и discovery, а не поток
 * с редиректами, состоянием и обменом кода.
 *
 * <p>Пароль не покидает вызов: он не попадает ни в текст ошибки, ни в консоль, ни в хранилище.
 */
export interface TokenSet {
  readonly accessToken: string;
  readonly refreshToken?: string;
  /** Абсолютный момент истечения access-токена (мс эпохи), посчитанный в момент выдачи. */
  readonly expiresAt: number;
}

/**
 * Почему вход не состоялся — то, что экрану нужно перевести, а не показать как есть.
 *
 * <p>`invalidCredentials` — ответ издателя `invalid_grant`: Keycloak одинаково отвечает и на
 * неизвестного пользователя, и на неверный пароль, и это правильно — панель не должна подсказывать,
 * какое из двух. `issuerUnavailable` — ответа не было вовсе (сеть, CORS, лежащий Keycloak):
 * это ошибка контура, а не оператора. `rejected` — издатель отказал по своей причине
 * («Account is not fully set up»), и её текст оператору полезнее любой нашей формулировки.
 */
export type SignInFailure = 'invalidCredentials' | 'issuerUnavailable' | 'rejected';

export class SignInError extends Error {
  constructor(
    readonly failure: SignInFailure,
    readonly detail?: string,
  ) {
    super(detail ?? failure);
    this.name = 'SignInError';
  }
}

interface Endpoints {
  readonly token: string;
  readonly endSession?: string;
}

interface TokenResponse {
  readonly access_token?: string;
  readonly refresh_token?: string;
  readonly expires_in?: number;
  readonly error?: string;
  readonly error_description?: string;
}

/** Время жизни токена, если издатель его не назвал: 60 секунд — заведомо мало, значит безопасно. */
const FALLBACK_LIFETIME_SECONDS = 60;

const discovered = new Map<string, Promise<Endpoints>>();

/**
 * Адреса издателя из его же документа: token endpoint у Keycloak предсказуем, но собирать его
 * строкой значит зашить Keycloak в панель, а `authority` — конфигурация развёртывания.
 * Кэш на модуле, потому что документ не меняется в пределах вкладки; неудача из кэша выбрасывается,
 * чтобы повтор входа был повтором, а не тем же отказом.
 */
export function discoverEndpoints(authority: string): Promise<Endpoints> {
  const cached = discovered.get(authority);
  if (cached) {
    return cached;
  }
  const pending = fetchEndpoints(authority).catch((error: unknown) => {
    discovered.delete(authority);
    throw error;
  });
  discovered.set(authority, pending);
  return pending;
}

async function fetchEndpoints(authority: string): Promise<Endpoints> {
  const url = `${authority.replace(/\/$/, '')}/.well-known/openid-configuration`;
  let response: Response;
  try {
    response = await fetch(url, { cache: 'no-store' });
  } catch {
    throw new SignInError('issuerUnavailable', url);
  }
  if (!response.ok) {
    throw new SignInError('issuerUnavailable', `${url} → HTTP ${response.status}`);
  }
  const document = (await response.json()) as {
    token_endpoint?: string;
    end_session_endpoint?: string;
  };
  if (!document.token_endpoint) {
    throw new SignInError('issuerUnavailable', `${url} carries no token_endpoint`);
  }
  return { token: document.token_endpoint, endSession: document.end_session_endpoint };
}

/** Логин и пароль → токены. Единственный вызов, видящий пароль. */
export async function passwordGrant(
  oidc: OidcSettings,
  username: string,
  password: string,
): Promise<TokenSet> {
  const endpoints = await discoverEndpoints(oidc.authority);
  return requestToken(
    endpoints.token,
    new URLSearchParams({
      grant_type: 'password',
      client_id: oidc.clientId,
      scope: oidc.scope,
      username,
      password,
    }),
  );
}

/** Продление сессии: тот же запрос, но без пароля — его панель не хранит. */
export async function refreshGrant(oidc: OidcSettings, refreshToken: string): Promise<TokenSet> {
  const endpoints = await discoverEndpoints(oidc.authority);
  return requestToken(
    endpoints.token,
    new URLSearchParams({
      grant_type: 'refresh_token',
      client_id: oidc.clientId,
      refresh_token: refreshToken,
    }),
  );
}

/**
 * Выход гасит и сессию на стороне издателя: direct grant её создаёт, и оставить её жить значит
 * оставить открытой дверь, о которой оператор думает, что закрыл. Best-effort: локальный выход
 * уже случился, и отказ издателя не должен его отменять.
 */
export async function endSession(oidc: OidcSettings, refreshToken: string): Promise<void> {
  try {
    const endpoints = await discoverEndpoints(oidc.authority);
    if (!endpoints.endSession) {
      return;
    }
    await fetch(endpoints.endSession, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ client_id: oidc.clientId, refresh_token: refreshToken }),
    });
  } catch {
    // Сессия панели закрыта в любом случае; отказ издателя здесь ничего не меняет.
  }
}

async function requestToken(endpoint: string, form: URLSearchParams): Promise<TokenSet> {
  let response: Response;
  try {
    response = await fetch(endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: form,
    });
  } catch {
    throw new SignInError('issuerUnavailable', endpoint);
  }
  const payload = (await response.json().catch(() => ({}))) as TokenResponse;
  if (!response.ok) {
    throw new SignInError(
      payload.error === 'invalid_grant' ? 'invalidCredentials' : 'rejected',
      payload.error_description ?? payload.error ?? `HTTP ${response.status}`,
    );
  }
  if (!payload.access_token) {
    throw new SignInError('rejected', 'the issuer answered without an access token');
  }
  return {
    accessToken: payload.access_token,
    refreshToken: payload.refresh_token,
    expiresAt: Date.now() + (payload.expires_in ?? FALLBACK_LIFETIME_SECONDS) * 1000,
  };
}
