export const REASON_HEADER = 'X-Commhub-Reason';

/**
 * Заголовок обоснования FR-7.3 для запроса — единственное место, где значение кодируется.
 *
 * Значение заголовка HTTP — байтовая строка: браузер (и Request в тестах) отказывается принять
 * символ с кодом выше 255, а обоснование оператор набирает по-русски, поэтому без кодирования
 * запрос падал бы ещё до отправки. Наружу уходит percent-encoded UTF-8 (RFC 3986), обратно в
 * текст его превращает BFF (ReasonHeaderFilter) — журнал аудита хранит слова оператора.
 *
 * Пустое обоснование — законный ответ модалки («подтвердил без причины»), и тогда заголовка нет.
 */
export function reasonHeader(
  reason: string | null | undefined,
): Record<typeof REASON_HEADER, string> | undefined {
  return reason ? { [REASON_HEADER]: encodeURIComponent(reason) } : undefined;
}
