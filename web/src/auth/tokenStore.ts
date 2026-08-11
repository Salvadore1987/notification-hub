import type { TokenSet } from './tokenClient';

/**
 * Где живёт сессия оператора: `sessionStorage`, то есть ровно столько, сколько живёт вкладка
 * (SEC-02). Это прежнее решение — при редирект-потоке так же держал токен oidc-client-ts, — и оно
 * не меняется оттого, что токен теперь добывает сама панель.
 *
 * <p>Хранение нужно не для удобства, а чтобы F5 не был повторным вводом пароля: перезагрузка
 * страницы — обычное действие во время инцидента.
 */
const KEY = 'commhub.session';

export function readSession(): TokenSet | null {
  try {
    const raw = window.sessionStorage.getItem(KEY);
    if (!raw) {
      return null;
    }
    const stored = JSON.parse(raw) as Partial<TokenSet>;
    if (typeof stored.accessToken !== 'string' || typeof stored.expiresAt !== 'number') {
      return null;
    }
    return {
      accessToken: stored.accessToken,
      refreshToken: typeof stored.refreshToken === 'string' ? stored.refreshToken : undefined,
      expiresAt: stored.expiresAt,
    };
  } catch {
    return null;
  }
}

export function writeSession(tokens: TokenSet): void {
  try {
    window.sessionStorage.setItem(KEY, JSON.stringify(tokens));
  } catch {
    // Недоступное хранилище стоит одной перезагрузки, а не входа: сессия уже в памяти.
  }
}

export function clearSession(): void {
  try {
    window.sessionStorage.removeItem(KEY);
  } catch {
    // Нечего чинить: выход из панели уже произошёл в памяти.
  }
}
