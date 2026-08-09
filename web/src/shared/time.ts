import dayjs, { type Dayjs } from 'dayjs';
import timezone from 'dayjs/plugin/timezone';
import utc from 'dayjs/plugin/utc';

dayjs.extend(utc);
dayjs.extend(timezone);

/**
 * UI-04: хранение и API — UTC (ISO-8601), отображение — Asia/Tashkent. Часовой пояс приложения
 * один и фиксированный, а не пояс браузера: панель читают из разных мест, а инцидент один.
 */
export const DISPLAY_TIME_ZONE = 'Asia/Tashkent';

export function formatDateTime(value: string | null | undefined): string {
  return value ? dayjs.utc(value).tz(DISPLAY_TIME_ZONE).format('DD.MM.YYYY HH:mm:ss') : '—';
}

export function formatDate(value: string | null | undefined): string {
  return value ? dayjs.utc(value).tz(DISPLAY_TIME_ZONE).format('DD.MM.YYYY') : '—';
}

/** Значение из пикера (в поясе отображения) → инстант для query-параметров BFF. */
export function toApiInstant(value: Dayjs): string {
  return value.tz(DISPLAY_TIME_ZONE, true).utc().toISOString();
}

/** Дефолт периода экранов — последние сутки, та же логика, что у AdminPeriod на backend (DB-02). */
export function lastDay(): { from: string; to: string } {
  const now = dayjs.utc();
  return { from: now.subtract(24, 'hour').toISOString(), to: now.toISOString() };
}
