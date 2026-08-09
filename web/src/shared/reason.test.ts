import { describe, expect, it } from 'vitest';

import { REASON_HEADER, reasonHeader } from './reason';

describe('reasonHeader', () => {
  it('sends a Russian justification as percent-encoded UTF-8 — a header value is bytes', () => {
    const header = reasonHeader('дубль после инцидента');

    expect(header?.[REASON_HEADER]).toBe(
      '%D0%B4%D1%83%D0%B1%D0%BB%D1%8C%20%D0%BF%D0%BE%D1%81%D0%BB%D0%B5%20%D0%B8%D0%BD%D1%86%D0%B8%D0%B4%D0%B5%D0%BD%D1%82%D0%B0',
    );
    expect(decodeURIComponent(header![REASON_HEADER])).toBe('дубль после инцидента');
  });

  it('produces a value a browser can actually put on the wire', () => {
    const header = reasonHeader('причина: сбой у провайдера №2');

    expect(() => new Headers(header)).not.toThrow();
    expect([...header![REASON_HEADER]].every((char) => char.charCodeAt(0) < 128)).toBe(true);
  });

  it('leaves no header at all when the operator confirmed without a reason', () => {
    expect(reasonHeader('')).toBeUndefined();
    expect(reasonHeader(null)).toBeUndefined();
    expect(reasonHeader(undefined)).toBeUndefined();
  });

  it('keeps an ASCII justification readable', () => {
    expect(reasonHeader('manual cleanup')?.[REASON_HEADER]).toBe('manual%20cleanup');
  });
});
