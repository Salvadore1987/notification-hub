import type { TFunction } from 'i18next';
import { describe, expect, it } from 'vitest';

import { ApiError } from '../api/problem';
import { describeError } from './errors';

/** t подменён отражением ключа: проверяется состав строки, а не перевод (переводы — i18n-тест). */
const t = ((key: string, params?: Record<string, unknown>) =>
  params ? `${key}(${Object.values(params).join(',')})` : key) as unknown as TFunction;

describe('describeError', () => {
  it('names the status in words the operator can act on', () => {
    expect(describeError(new ApiError(404), t)).toBe('errors.notFound');
    expect(describeError(new ApiError(409), t)).toBe('errors.conflict');
    expect(describeError(new ApiError(429), t)).toBe('errors.rateLimited');
    expect(describeError(new ApiError(500), t)).toBe('errors.request');
  });

  it('shows the detail of problem+json as it came — it is what backend says (IR-01)', () => {
    expect(describeError(new ApiError(409, { detail: 'stream is suspended' }), t)).toBe(
      'errors.conflict. stream is suspended',
    );
  });

  it('falls back to the title when there is no detail', () => {
    expect(describeError(new ApiError(400, { title: 'Bad Request' }), t)).toBe(
      'errors.request. Bad Request',
    );
  });

  it('appends when to come back for a rate-limited call (IR-02)', () => {
    expect(describeError(new ApiError(429, { detail: 'too many' }, 30), t)).toBe(
      'errors.rateLimited. too many. errors.retryIn(30)',
    );
  });

  it('says nothing but "unknown" about a failure that is not an API answer', () => {
    expect(describeError(new TypeError('network down'), t)).toBe('errors.unknown');
    expect(describeError(undefined, t)).toBe('errors.unknown');
  });
});
