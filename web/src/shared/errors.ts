import type { TFunction } from 'i18next';

import { isApiError } from '../api/problem';

/**
 * Одна ошибка — одна строка для человека. problem+json несёт detail на языке backend'а —
 * он и показывается, когда есть; локализованные тексты — рамка вокруг (статус, Retry-After).
 */
export function describeError(error: unknown, t: TFunction): string {
  if (!isApiError(error)) {
    return t('errors.unknown');
  }
  const head =
    error.status === 404
      ? t('errors.notFound')
      : error.status === 409
        ? t('errors.conflict')
        : error.status === 429
          ? t('errors.rateLimited')
          : t('errors.request');
  const detail = error.problem?.detail ?? error.problem?.title;
  const retry =
    error.retryAfterSeconds !== undefined
      ? t('errors.retryIn', { seconds: error.retryAfterSeconds })
      : undefined;
  return [head, detail, retry].filter(Boolean).join('. ');
}
