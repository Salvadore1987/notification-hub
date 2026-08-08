# WireMock: стабы внешних провайдеров

Каталог монтируется в контейнер `commhub-wiremock` (`http://localhost:8089`).

Стабы добавляются по мере реализации адаптеров (QA-04, PR-04):

| Провайдер | Файл | Реализуется в |
|---|---|---|
| Playmobile SMS-Broker (`/broker-api/send`, статусы 100–411) | `playmobile-*.json` | Phase 7 |
| SMS Gate v4.3 (`/api/v2/send`, `/send_msgs`, `/api/v2/search`) | `smsgate-*.json` | Phase 7 |
| FCM HTTP v1 | `fcm-*.json` | Phase 12 |
| APNs HTTP/2 | `apns-*.json` | Phase 12 |

Формат — стандартные JSON-маппинги WireMock; ответы-фикстуры кладутся в `../__files`.
После правки файлов: `curl -X POST http://localhost:8089/__admin/mappings/reset`.
