// Дымовой прогон: контур отвечает, поток заведён, приём работает (QA-05).
//
// Запускается первым — до NF-01 и TC-01. Половина неудачных нагрузочных прогонов кончается тем,
// что поток не заведён или приостановлен, и полчаса нагрузки уходят в 422; этот сценарий стоит
// пятнадцать секунд и отвечает на тот же вопрос.
//
//   k6 run -e HUB_URL=http://localhost:8080 load/k6/smoke.js

import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, otpMessage, submitMessage } from './lib/hub.js';

export const options = {
  vus: 1,
  duration: '15s',
  thresholds: {
    checks: ['rate==1.0'],
  },
};

export default function () {
  const health = http.get(`${BASE_URL}/actuator/health/readiness`);
  check(health, { 'readiness UP': (r) => r.status === 200 });

  const accepted = submitMessage(otpMessage(__ITER));
  check(accepted, {
    'ответ несёт messageId': (r) => r.status === 202 && !!r.json('messageId'),
  });
  sleep(1);
}
