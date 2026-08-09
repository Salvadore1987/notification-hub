import { describe, expect, it } from 'vitest';

import { maskAddress, maskEmail, maskMsisdn } from './masking';

/** Зеркало Masking.java: что видно в подтверждении, должно совпадать с тем, что видно в логе. */
describe('maskMsisdn', () => {
  it('keeps the operator code and the last four digits', () => {
    expect(maskMsisdn('998901234567')).toBe('99890***4567');
  });

  it('hides a value too short to mask partially', () => {
    expect(maskMsisdn('9989012')).toBe('***');
  });

  it('renders an absent value as a dash', () => {
    expect(maskMsisdn(undefined)).toBe('-');
    expect(maskMsisdn(null)).toBe('-');
    expect(maskMsisdn('   ')).toBe('-');
  });

  it('trims before masking', () => {
    expect(maskMsisdn('  998901234567  ')).toBe('99890***4567');
  });
});

describe('maskEmail', () => {
  it('keeps the first and last character of the local part and the whole domain', () => {
    expect(maskEmail('ivan@example.com')).toBe('i***n@example.com');
  });

  it('keeps only the first character when the local part is one or two characters', () => {
    expect(maskEmail('a@example.com')).toBe('a***@example.com');
    expect(maskEmail('ab@example.com')).toBe('a***@example.com');
  });

  it('masks the whole value when there is no local part', () => {
    expect(maskEmail('@example.com')).toBe('***');
    expect(maskEmail('not-an-email')).toBe('***');
  });

  it('splits on the last @ — the local part may contain one', () => {
    expect(maskEmail('a@b@example.com')).toBe('a***b@example.com');
  });
});

describe('maskAddress', () => {
  it('chooses the masking by what the value is, like AdminMasking.recipient', () => {
    expect(maskAddress('998901234567')).toBe('99890***4567');
    expect(maskAddress('ivan@example.com')).toBe('i***n@example.com');
    expect(maskAddress('')).toBe('-');
  });

  it('treats a push token as an opaque value, not as an address', () => {
    expect(maskAddress('fcm-token-0123456789')).toBe('fcm-t***6789');
  });
});
