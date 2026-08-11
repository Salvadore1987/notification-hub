# Notification Hub — админ-панель (web/)

SPA админ-панели (§11, UI-01…UI-04): React 18 + TypeScript, сборка Vite, UI-kit — Ant Design
(выбор согласован, UI-01). Работает только через Admin BFF `/api/admin/v1` (§11.2) — прямого
доступа к БД или внутренним портам нет (UI-02).

## Команды

```bash
npm install
npm run dev            # http://localhost:5173, /api проксируется на bootstrap (localhost:8080)
                       # нужен поднятый Keycloak: docker compose up -d keycloak, вход demo/demo
npm run build          # tsc -b + vite build → dist/
npm run lint           # ESLint (flat config)
npm run format         # Prettier
npm run generate:api   # типы из ../adapter/in/admin/src/main/resources/openapi/comm-hub-admin-v1.yaml
npm test               # unit/компонентные тесты (Vitest + Testing Library, jsdom)
npm run test:coverage  # то же с покрытием
npm run test:e2e       # Playwright: сценарии QA-07 и проверка доступности (нужен chromium)
```

Перед первым `npm run test:e2e` — `npx playwright install chromium` (браузер качается один раз).
E2E логинятся в настоящий Keycloak, поэтому ему нужно быть поднятым: `docker compose up -d keycloak`
(без него прогон падает сразу и говорит эту команду).

## Тесты

- **Unit/компонентные** (`src/**/*.test.ts(x)`, Vitest + Testing Library): чистые модули
  (маскирование, время, ошибки, роли, конфигурация, словари) и ключевые компоненты
  (`ServerTable`, `useReasonPrompt`, `RequireSection`, меню, DLQ, тестовая отправка). BFF
  подменяется заглушкой `src/test/api.ts` — маршрут объявляется как «GET /dlq», проверяется
  и что ушло на сервер, и что увидел оператор.
- **E2E** (`e2e/*.spec.ts`, Playwright): сценарии QA-07 — пауза рассылки, повтор из DLQ,
  публикация шаблона, тестовая отправка. Backend не поднимается: Admin BFF подменяется на
  уровне сети (`e2e/fixtures/adminApi.ts`, состояние живое — пауза меняет статус в карточке).
  А вот **вход настоящий**: форма панели заполняется демо-пользователем против настоящего Keycloak
  (`e2e/fixtures/signIn.ts`), и входит каждый тест — переиспользовать нечего, токен живёт в
  `sessionStorage`, которого `storageState` не переносит (SEC-02). Вход и ожидание экрана стоят
  в фикстуре `page`, а не в spec'ах; сам вход проверяет `sign-in.spec.ts`.
  Плюс `accessibility.spec.ts`: axe-core (WCAG 2.1 A/AA, кроме
  контраста — это тема Ant Design) на пяти экранах и форме входа, и язык документа при
  переключении языка.
- Словари RU/UZ/EN сверяются между собой и с ключами, которые просят экраны
  (`src/i18n/locales.test.ts`), а списки перечислений в `shared/labels.ts` — со
  сгенерированной схемой контракта (`src/shared/labels.test.ts`).

`src/api/generated/admin-schema.ts` сгенерирован и закоммичен; после правки контракта
запускать `npm run generate:api` — расхождение с контроллерами ловит AdminOpenApiContractTest
на стороне backend, расхождение типов — регенерация здесь.

## Конфигурация развёртывания

`public/config.json` читается при старте (сборка одна, контуров несколько):

- `apiBaseUrl` — база Admin BFF (по умолчанию относительная `/api/admin/v1`);
- `oidc.authority` / `clientId` / `scope` — OIDC-провайдер корпоративного SSO (UI-02, SEC-02).
  Локально это Keycloak из `docker compose` (`http://localhost:8180/realms/commhub`), вход
  `demo/demo`; другие роли §10.1 — `operator`, `template-manager`, `analyst`, `viewer`, `auditor`
  (пароль совпадает с логином). Пустой `authority` — **не** режим работы, а ошибка настройки
  контура: панель показывает «не настроена» и внутрь не пускает (ADR-0037);
- `rolesClaim` — claim токена с SSO-группами (зеркало `commhub.security.roles-claim`,
  по умолчанию `groups`);
- `groupRoles` — маппинг SSO-группа → роль §10.1; группа, совпадающая с именем роли,
  проходит как есть.

Аутентификация — **форма входа самой панели** (ADR-0043): логин и пароль уходят direct access
grant'ом на token endpoint издателя (`src/auth/tokenClient.ts`), токены живут в `sessionStorage`
(`src/auth/tokenStore.ts`) и продлеваются за 30 с до истечения. Редиректа на издателя и
`/auth/callback` больше нет, поэтому глубокая ссылка переживает вход сама: адрес не меняется.
Роли считаются из payload access-токена и гейтят меню и маршруты (`src/auth/roles.ts` —
клиентское зеркало `AdminAuthority`); решение всегда за `@PreAuthorize` на сервере (FR-7.2).

## Конвенции

- Время: хранение и API — UTC, отображение — `Asia/Tashkent` (`src/shared/time.ts`, UI-04).
- i18n: RU (основной и fallback), UZ, EN (`src/i18n`, UI-01).
- PII: адреса маскирует backend по роли; клиентские `maskMsisdn`/`maskEmail` — для значений,
  введённых оператором (DB-04).
- Обоснование действия (FR-7.3) уходит заголовком `X-Commhub-Reason` percent-encoded
  (`src/shared/reason.ts`): значение HTTP-заголовка байтовое, русский текст без кодирования
  браузер отправить отказывается. Декодирует его `ReasonHeaderFilter` на стороне BFF.
- Списки: `ServerTable` — серверная пагинация/сортировка, виртуализация по высоте (UI-03);
  фильтры принадлежат экрану и замыкаются в `fetchPage`.
- Разделы §11.2 объявляются один раз в `src/layout/navigation.tsx` (путь + перевод + секция ролей).
