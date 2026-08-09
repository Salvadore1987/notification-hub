import { afterEach, describe, expect, it, vi } from 'vitest';

import { ApiError, isApiError, parseRetryAfter } from './problem';

describe('ApiError', () => {
  it('takes its message from problem+json, detail before title', () => {
    expect(new ApiError(409, { title: 'Conflict', detail: 'version changed' }).message).toBe(
      'version changed',
    );
    expect(new ApiError(409, { title: 'Conflict' }).message).toBe('Conflict');
    expect(new ApiError(503).message).toBe('HTTP 503');
  });

  it('is recognisable across module boundaries', () => {
    expect(isApiError(new ApiError(404))).toBe(true);
    expect(isApiError(new Error('boom'))).toBe(false);
    expect(isApiError(undefined)).toBe(false);
  });
});

describe('parseRetryAfter', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('reads delta-seconds', () => {
    expect(parseRetryAfter('30')).toBe(30);
  });

  it('rounds a fractional value up — waiting longer is the safe direction (IR-02)', () => {
    expect(parseRetryAfter('1.2')).toBe(2);
  });

  it('never returns a negative wait', () => {
    expect(parseRetryAfter('-5')).toBe(0);
  });

  it('reads an HTTP-date as seconds from now', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-08-09T12:00:00Z'));

    expect(parseRetryAfter('Sun, 09 Aug 2026 12:00:45 GMT')).toBe(45);
    expect(parseRetryAfter('Sun, 09 Aug 2026 11:59:00 GMT')).toBe(0);
  });

  it('gives no answer when the header is absent or unparsable', () => {
    expect(parseRetryAfter(null)).toBeUndefined();
    expect(parseRetryAfter('soon')).toBeUndefined();
  });
});
