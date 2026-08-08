// Общие помощники нагрузочных сценариев Notification Hub (QA-05).
//
// Сценарии говорят с Модулем по §8.2, тем же контрактом, что и системы-источники Банка: тело
// POST /api/v1/messages — это IK-03. Ничего специально нагрузочного в API нет и не должно быть:
// профиль, который нельзя воспроизвести штатным контрактом, ничего не доказывает о продакшене.

import http from 'k6/http';
import { check } from 'k6';

/** Базовый адрес контура; для локального стенда — http://localhost:8080. */
export const BASE_URL = __ENV.HUB_URL || 'http://localhost:8080';

/** Потоки-источники. Должны существовать в реестре (§10.1) и быть ACTIVE, иначе всё уйдёт в отказ. */
export const OTP_STREAM = __ENV.OTP_STREAM || 'ibank-otp';
export const BULK_STREAM = __ENV.BULK_STREAM || 'marketing-bulk';

/**
 * Токен систем-источников (SEC-01). Пусто — контур без OAuth2/mTLS: так поднимается локальный стенд,
 * и на нём же меряется потолок самого конвейера, без стоимости валидации подписи.
 */
const TOKEN = __ENV.HUB_TOKEN || '';

export function headers(correlationId) {
  const base = {
    'Content-Type': 'application/json',
    // FR-8.6: сквозной идентификатор. Заголовок побеждает поле документа — тело парсится на
    // сообщение, а батч несёт их много.
    'X-Correlation-Id': correlationId,
  };
  if (TOKEN) {
    base.Authorization = `Bearer ${TOKEN}`;
  }
  return base;
}

/** MSISDN строго формата 9989xxxxxxxx (§8.2): всё остальное отклонит валидатор, а не провайдер. */
export function msisdn(seed) {
  const tail = String(10000000 + (seed % 90000000));
  return `9989${tail}`;
}

export function correlationId(prefix, seed) {
  return `${prefix}-${__VU}-${seed}`;
}

/** Сообщение OTP: короткий латинский текст в один сегмент GSM-7 (§18.3) — как настоящий код. */
export function otpMessage(seed) {
  return {
    schemaVersion: '1.0',
    streamId: OTP_STREAM,
    externalMessageId: `otp-${__VU}-${seed}-${Date.now()}`,
    trafficClass: 'CRITICAL_OTP',
    recipient: { msisdn: msisdn(seed) },
    content: { sms: { text: `Kod: ${100000 + (seed % 899999)}. Nikomu ne soobshchayte.` } },
    correlationId: correlationId('otp', seed),
  };
}

/** Элемент массовой рассылки: кириллица, то есть UCS-2 и два сегмента — как настоящая рассылка. */
export function bulkItem(seed) {
  return {
    externalMessageId: `bulk-${__VU}-${seed}-${Date.now()}`,
    recipient: { msisdn: msisdn(seed * 7 + 13) },
    content: {
      sms: {
        text: 'Hamkorbank: начислен кешбэк за покупки прошлого месяца. Подробности в мобильном приложении.',
      },
    },
  };
}

export function submitMessage(message) {
  const response = http.post(`${BASE_URL}/api/v1/messages`, JSON.stringify(message), {
    headers: headers(message.correlationId),
    tags: { endpoint: 'submitMessage', trafficClass: message.trafficClass || 'TRANSACTIONAL' },
  });
  // 429 — штатный ответ лимитера IR-02, а не ошибка: он считается отдельно, иначе сценарий,
  // упёршийся в лимит потока, выглядит как сломанный Модуль.
  check(response, {
    'accepted (202)': (r) => r.status === 202,
    'not rate limited': (r) => r.status !== 429,
  });
  return response;
}

export function createBatch(streamId, expectedTotal) {
  const response = http.post(
    `${BASE_URL}/api/v1/batches`,
    JSON.stringify({ streamId, channel: 'SMS', trafficClass: 'NOTIFICATION', expectedTotal }),
    { headers: headers(`batch-${__VU}-${Date.now()}`), tags: { endpoint: 'createBatch' } },
  );
  check(response, { 'batch created (202)': (r) => r.status === 202 });
  return response.status === 202 ? response.json('batchId') : null;
}

export function addBatchItems(batchId, streamId, items) {
  const response = http.post(
    `${BASE_URL}/api/v1/batches/${batchId}/items?streamId=${streamId}`,
    JSON.stringify({ items }),
    { headers: headers(`batch-${batchId}`), tags: { endpoint: 'addBatchItems' } },
  );
  check(response, { 'chunk accepted (202)': (r) => r.status === 202 });
  return response;
}

export function actOnBatch(batchId, action) {
  const actorHeaders = headers(`batch-${batchId}`);
  // FR-7.3: действие над батчем журналируется, и без актора в журнале останется «кто-то».
  actorHeaders['X-Commhub-Actor'] = `k6-${__VU}`;
  return http.post(`${BASE_URL}/api/v1/batches/${batchId}/actions/${action}`, null, {
    headers: actorHeaders,
    tags: { endpoint: 'actOnBatch' },
  });
}
