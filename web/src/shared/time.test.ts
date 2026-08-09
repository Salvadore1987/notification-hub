import dayjs from 'dayjs';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { formatDate, formatDateTime, lastDay, toApiInstant } from './time';

/**
 * UI-04: хранение в UTC, отображение в Asia/Tashkent (+05:00 круглый год). Тест держит именно
 * это: пояс приложения фиксированный, а не пояс машины, на которой запущен браузер или тест.
 */
describe('formatDateTime', () => {
  it('renders a UTC instant in the display time zone', () => {
    expect(formatDateTime('2026-08-09T00:30:00Z')).toBe('09.08.2026 05:30:00');
  });

  it('shifts the date when the instant falls before midnight in Tashkent', () => {
    expect(formatDateTime('2026-08-08T20:00:00Z')).toBe('09.08.2026 01:00:00');
    expect(formatDate('2026-08-08T20:00:00Z')).toBe('09.08.2026');
  });

  it('renders an absent instant as an em dash', () => {
    expect(formatDateTime(null)).toBe('—');
    expect(formatDateTime(undefined)).toBe('—');
    expect(formatDate('')).toBe('—');
  });
});

describe('toApiInstant', () => {
  it('reads the picker value as Tashkent local time and sends UTC', () => {
    expect(toApiInstant(dayjs('2026-08-09T05:30:00'))).toBe('2026-08-09T00:30:00.000Z');
  });
});

describe('lastDay', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('is the last 24 hours in UTC — the same default AdminPeriod applies server-side', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-08-09T12:00:00Z'));

    expect(lastDay()).toEqual({
      from: '2026-08-08T12:00:00.000Z',
      to: '2026-08-09T12:00:00.000Z',
    });
  });
});
