// NF-01: устойчивая пропускная способность приёма (QA-05).
//
// Что доказывает: Модуль принимает ≥ 100 сообщений/с непрерывно и держит латентность приёма
// в границах §8.2 — p99 ≤ 200 мс на POST /messages (FR-1.7). Это про **приём**, а не про доставку:
// доставка асинхронна по построению (AD-03), и мерить её на приёме — значит мерить провайдера.
//
// Запуск:
//   k6 run -e HUB_URL=https://comm-hub.test.hamkorbank.uz load/k6/nf01-throughput.js
//   k6 run -e RATE=200 -e DURATION=30m load/k6/nf01-throughput.js
//
// Отчёт приёмки: k6 run --summary-export=load/reports/nf01-<дата>.json ...

import { Trend } from 'k6/metrics';
import { otpMessage, submitMessage } from './lib/hub.js';

const RATE = Number(__ENV.RATE || 150);
const DURATION = __ENV.DURATION || '10m';

const acceptLatency = new Trend('commhub_accept_latency', true);

export const options = {
  scenarios: {
    // constant-arrival-rate, а не constant-vus: NF-01 сформулирован в сообщениях в секунду, и
    // сценарий на фиксированных VU молча снижает нагрузку ровно тогда, когда Модуль замедляется —
    // то есть перестаёт мерить именно в тот момент, ради которого его запускали.
    steady_ingest: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.max(50, RATE),
      maxVUs: Math.max(200, RATE * 4),
    },
  },
  thresholds: {
    // FR-1.7: целевая латентность приёма. Порог — на успешных ответах: 429 от лимитера IR-02
    // возвращается мгновенно и занижал бы перцентиль.
    'http_req_duration{endpoint:submitMessage}': ['p(99)<200', 'p(95)<100'],
    // NF-01 выполнен, только если приняли почти всё: доля не-202 — это и есть недобор пропускной.
    'checks{}': ['rate>0.99'],
    http_req_failed: ['rate<0.01'],
  },
};

export default function () {
  const response = submitMessage(otpMessage(__ITER));
  acceptLatency.add(response.timings.duration);
}
