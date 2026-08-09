/**
 * Маскирование PII на клиенте (DB-04, SEC-06) — зеркало серверного Masking.java, чтобы адрес
 * выглядел одинаково в панели, в логе и в выгрузке: 99890***4567, i***n@example.com.
 *
 * Это отображение, а не защита: сколько адреса видно роли, решает backend (AdminMasking) и
 * присылает уже маскированное. Эти функции — для значений, которые вводит сам оператор
 * (поиск, тестовая отправка) и которые нельзя показывать целиком в подтверждениях и заголовках.
 */
const REDACTED = '***';

export function maskMsisdn(raw: string | null | undefined): string {
  if (!raw || !raw.trim()) {
    return '-';
  }
  const trimmed = raw.trim();
  if (trimmed.length <= 7) {
    return REDACTED;
  }
  return trimmed.slice(0, 5) + REDACTED + trimmed.slice(-4);
}

/** Домен переживает маскирование сознательно: он не персональные данные (см. Masking.email). */
export function maskEmail(raw: string | null | undefined): string {
  if (!raw || !raw.trim()) {
    return '-';
  }
  const trimmed = raw.trim();
  const at = trimmed.lastIndexOf('@');
  if (at <= 0) {
    return REDACTED;
  }
  const local = trimmed.slice(0, at);
  const masked =
    local.length <= 2 ? local[0] + REDACTED : local[0] + REDACTED + local[local.length - 1];
  return masked + trimmed.slice(at);
}

/** Как AdminMasking.recipient: что лежит в значении, то и решает, как его маскировать. */
export function maskAddress(raw: string | null | undefined): string {
  if (!raw || !raw.trim()) {
    return '-';
  }
  return raw.indexOf('@') > 0 ? maskEmail(raw) : maskMsisdn(raw);
}
