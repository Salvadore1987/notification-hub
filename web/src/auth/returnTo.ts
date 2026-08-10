import type { User } from 'oidc-client-ts';

/**
 * Куда вернуться после входа.
 *
 * <p>Пока панель работала без аутентификации, глубокой ссылке некуда было ломаться. Теперь `/dlq`
 * в чистом профиле — это уход на issuer и возврат на /auth/callback, и без запомненного адреса
 * оператор каждый раз оказывался бы на дашборде: redirect_uri один на все экраны.
 *
 * <p>Отдельный файл, а не session.tsx: там только компоненты, иначе fast refresh перестаёт работать
 * для всего модуля — та же причина, по которой рядом живёт sessionContext.ts.
 */
export interface SigninState {
  readonly returnTo?: string;
}

/** Адрес, с которого уходили на issuer, — вместе с query и hash: ссылка на экран это ссылка целиком. */
export function currentLocation(): string {
  return `${window.location.pathname}${window.location.search}${window.location.hash}`;
}

/** State для signinRedirect: то, что restoreReturnTo прочитает после обмена кода. */
export function signinState(): SigninState {
  return { returnTo: currentLocation() };
}

/**
 * Возврат на запрошенный маршрут после обмена кода.
 *
 * <p>Переписать адрес нужно в любом случае — code и state не должны остаться в истории; куда
 * именно, знает только state. Сам /auth/callback исключён: вернуться на него значит показать
 * пустой Spin вместо экрана.
 */
export function restoreReturnTo(user?: User | void): void {
  const returnTo = (user?.state as SigninState | undefined)?.returnTo;
  const target = returnTo && !returnTo.startsWith('/auth/callback') ? returnTo : '/';
  window.history.replaceState({}, document.title, target);
}
