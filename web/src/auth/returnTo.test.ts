import { afterEach, describe, expect, it, vi } from 'vitest';

import { currentLocation, restoreReturnTo } from './returnTo';

/**
 * Возврат на запрошенный маршрут после входа.
 *
 * <p>Пока панель работала без аутентификации, глубокая ссылка никуда не уходила и ломаться было
 * нечему. Теперь `/dlq` в чистом профиле — это редирект на Keycloak и возврат на /auth/callback,
 * и без state оператор каждый раз оказывался бы на дашборде.
 */
describe('restoreReturnTo', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  function replaceState() {
    return vi.spyOn(window.history, 'replaceState').mockImplementation(() => {});
  }

  it('returns to the screen the operator asked for', () => {
    const spy = replaceState();

    restoreReturnTo({ state: { returnTo: '/messages?status=FAILED' } } as never);

    expect(spy).toHaveBeenCalledWith({}, document.title, '/messages?status=FAILED');
  });

  it('falls back to the dashboard when nothing was remembered', () => {
    const spy = replaceState();

    restoreReturnTo(undefined);

    expect(spy).toHaveBeenCalledWith({}, document.title, '/');
  });

  it('never returns to the callback route itself — that is a loop, not a screen', () => {
    const spy = replaceState();

    restoreReturnTo({ state: { returnTo: '/auth/callback' } } as never);

    expect(spy).toHaveBeenCalledWith({}, document.title, '/');
  });
});

describe('currentLocation', () => {
  it('keeps query and hash: a link to a screen is the whole link', () => {
    window.history.replaceState({}, '', '/statistics?dimension=PROVIDER#top');

    expect(currentLocation()).toBe('/statistics?dimension=PROVIDER#top');
  });
});
