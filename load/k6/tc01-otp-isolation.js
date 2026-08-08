// TC-01: изоляция классов трафика (QA-05).
//
// Что доказывает: массовая рассылка не съедает полосу OTP. Два сценария идут одновременно —
// батч на 500 000 получателей грузится чанками по 10 000, и параллельно с ним ровным темпом идут
// одиночные OTP. Порог стоит **только** на OTP: рассылка вправе замедляться, OTP — нет.
//
// Это единственный сценарий, который проверяет утверждение, ради которого построены раздельные
// топики, пулы потоков и лимиты (§8.1, AD-05). Все остальные измеряют Модуль под одним видом
// нагрузки, а TC-01 — под двумя, и весь смысл в том, что они мешают друг другу.
//
// Запуск:
//   k6 run -e HUB_URL=https://comm-hub.test.hamkorbank.uz load/k6/tc01-otp-isolation.js
//   k6 run -e BULK_TOTAL=500000 -e OTP_RATE=50 load/k6/tc01-otp-isolation.js

import { fail } from 'k6';
import { Counter } from 'k6/metrics';
import {
  BULK_STREAM,
  addBatchItems,
  actOnBatch,
  bulkItem,
  createBatch,
  otpMessage,
  submitMessage,
} from './lib/hub.js';

const BULK_TOTAL = Number(__ENV.BULK_TOTAL || 500000);
const CHUNK_SIZE = Number(__ENV.CHUNK_SIZE || 10000); // потолок §8.2
const OTP_RATE = Number(__ENV.OTP_RATE || 50);
const DURATION = __ENV.DURATION || '15m';

const bulkItemsLoaded = new Counter('commhub_bulk_items_loaded');

export const options = {
  scenarios: {
    // Массовая рассылка: чанки идут так быстро, как их принимают.
    bulk_batch: {
      executor: 'shared-iterations',
      exec: 'loadBulkChunk',
      vus: Number(__ENV.BULK_VUS || 4),
      iterations: Math.ceil(BULK_TOTAL / CHUNK_SIZE),
      maxDuration: DURATION,
    },
    // OTP: ровный темп на всё время рассылки — именно ради момента, когда она в разгаре.
    otp_stream: {
      executor: 'constant-arrival-rate',
      exec: 'sendOtp',
      rate: OTP_RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: OTP_RATE,
      maxVUs: OTP_RATE * 4,
    },
  },
  thresholds: {
    // Порог TC-01 стоит на классе трафика, а не на эндпоинте: тот же POST /messages несёт оба класса.
    'http_req_duration{trafficClass:CRITICAL_OTP}': ['p(99)<200'],
    'checks{}': ['rate>0.99'],
    // Отдельно: OTP не должен получать 429 от лимитера, пока рассылка занимает Модуль (IR-02).
    'http_req_failed{trafficClass:CRITICAL_OTP}': ['rate<0.001'],
  },
};

/**
 * Батч создаётся один раз на прогон и стартует сразу: setup выполняется до сценариев, поэтому
 * к моменту первого OTP рассылка уже в работе, а не только заведена.
 */
export function setup() {
  const batchId = createBatch(BULK_STREAM, BULK_TOTAL);
  if (!batchId) {
    fail('не удалось создать батч — проверьте streamId и статус потока (FR-3.2)');
  }
  actOnBatch(batchId, 'start');
  return { batchId };
}

export function loadBulkChunk(data) {
  const items = [];
  for (let i = 0; i < CHUNK_SIZE; i++) {
    items.push(bulkItem(__ITER * CHUNK_SIZE + i));
  }
  const response = addBatchItems(data.batchId, BULK_STREAM, items);
  if (response.status === 202) {
    bulkItemsLoaded.add(response.json('accepted'));
  }
}

export function sendOtp() {
  submitMessage(otpMessage(__ITER));
}

/** Рассылка останавливается вместе с прогоном: оставленный батч продолжит слать после теста. */
export function teardown(data) {
  actOnBatch(data.batchId, 'stop');
}
