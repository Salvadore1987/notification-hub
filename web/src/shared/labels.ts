import type { components } from '../api/generated/admin-schema';

/**
 * Цвета Tag'ов для перечислений контракта. Сами значения показываются как есть — словарь
 * статусов у панели и у API систем-источников один (§6.3), и оператор ищет в логах и в
 * поддержке ровно эти слова, а не их переводы.
 */
type Schemas = components['schemas'];

export type MessageStatus = Schemas['MessageStatus'];
export type BatchStatus = Schemas['BatchStatus'];
export type Channel = Schemas['Channel'];

const MESSAGE_STATUS_COLORS: Record<MessageStatus, string> = {
  ACCEPTED: 'blue',
  VALIDATED: 'blue',
  ROUTED: 'blue',
  QUEUED: 'geekblue',
  SENDING: 'processing',
  SENT_TO_PROVIDER: 'cyan',
  RETRYING: 'orange',
  DELIVERED: 'green',
  UNDELIVERED: 'volcano',
  EXPIRED: 'volcano',
  REJECTED: 'red',
  DUPLICATE: 'default',
  CANCELLED: 'default',
  FAILED: 'red',
};

export function messageStatusColor(status: MessageStatus | undefined): string {
  return status ? MESSAGE_STATUS_COLORS[status] : 'default';
}

const BATCH_STATUS_COLORS: Record<BatchStatus, string> = {
  ACCEPTED: 'blue',
  PROCESSING: 'processing',
  PAUSED: 'orange',
  STOPPED: 'red',
  COMPLETED: 'green',
};

export function batchStatusColor(status: BatchStatus | undefined): string {
  return status ? BATCH_STATUS_COLORS[status] : 'default';
}

export type ProviderHealth = 'UP' | 'DEGRADED' | 'DOWN' | 'UNKNOWN';

const HEALTH_COLORS: Record<ProviderHealth, string> = {
  UP: 'green',
  DEGRADED: 'orange',
  DOWN: 'red',
  UNKNOWN: 'default',
};

export function healthColor(health: ProviderHealth | undefined): string {
  return health ? HEALTH_COLORS[health] : 'default';
}

/**
 * Статус версии шаблона — из контракта, а не своим списком: панель однажды уже разошлась с доменом
 * («IN_REVIEW» и «REJECTED», которых нет ни в `TemplateStatus`, ни в CHECK-ограничении), и кнопка
 * «На ревью» молча отвечала 400. Теперь расхождение — ошибка компиляции.
 */
export type VersionStatus = Schemas['TemplateVersionStatus'];

const VERSION_STATUS_COLORS: Record<VersionStatus, string> = {
  DRAFT: 'default',
  ON_REVIEW: 'orange',
  PUBLISHED: 'green',
  ARCHIVED: 'default',
};

export function versionStatusColor(status: VersionStatus | undefined): string {
  return status ? VERSION_STATUS_COLORS[status] : 'default';
}

export const CHANNELS: readonly Channel[] = ['SMS', 'EMAIL', 'PUSH'];

export const MESSAGE_STATUSES = Object.keys(MESSAGE_STATUS_COLORS) as readonly MessageStatus[];

export const BATCH_STATUSES = Object.keys(BATCH_STATUS_COLORS) as readonly BatchStatus[];

export const REJECTION_REASONS: readonly Schemas['RejectionReason'][] = [
  'VALIDATION_FAILED',
  'DUPLICATE_SUBMISSION',
  'SUPPRESSED',
  'OPT_OUT',
  'QUIET_HOURS',
  'FREQUENCY_CAPPED',
  'QUOTA_EXCEEDED',
  'STREAM_SUSPENDED',
  'TEMPLATE_NOT_PUBLISHED',
  'TEMPLATE_VARIABLE_MISSING',
  'NO_ROUTE_AVAILABLE',
  'PAN_DETECTED',
  'TTL_EXPIRED',
  'SEND_STOPPED',
  'KILL_SWITCH',
  'PROVIDER_REJECTED',
  'ATTEMPTS_EXHAUSTED',
];

export const SUPPRESSION_REASONS: readonly Schemas['SuppressionReason'][] = [
  'OPT_OUT',
  'COMPLAINT',
  'HARD_BOUNCE',
  'DELIVERY_FAILURES',
  'PROVIDER_BLACKLIST',
  'PUSH_TOKEN_INVALID',
  'MANUAL',
];

export const TRAFFIC_CLASSES = ['CRITICAL_OTP', 'TRANSACTIONAL', 'NOTIFICATION'] as const;

export const PRIORITIES = ['HIGH', 'NORMAL', 'LOW'] as const;

export const BALANCING_STRATEGIES = ['PRIORITY', 'ROUND_ROBIN', 'WEIGHTED', 'LEAST_COST'] as const;

export const CONTENT_LOCALES: readonly Schemas['ContentLocale'][] = ['RU', 'UZ', 'EN'];

/** Опции Select'а из значений перечисления — значение и подпись совпадают сознательно. */
export function enumOptions(values: readonly string[]): { value: string; label: string }[] {
  return values.map((value) => ({ value, label: value }));
}
