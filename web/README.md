# Notification Hub — админ-панель (web/)

SPA админ-панели (§11, UI-01…UI-04): React 18 + TypeScript, сборка Vite, UI-kit — Ant Design
(выбор согласован, UI-01). Работает только через Admin BFF `/api/admin/v1` (§11.2) — прямого
доступа к БД или внутренним портам нет (UI-02).

## Команды

```bash
npm install
npm run dev            # http://localhost:5173, /api проксируется на bootstrap (localhost:8080)
npm run build          # tsc -b + vite build → dist/
npm run lint           # ESLint (flat config)
npm run format         # Prettier
npm run generate:api   # типы из ../adapter/src/main/resources/openapi/comm-hub-admin-v1.yaml
```

`src/api/generated/admin-schema.ts` сгенерирован и закоммичен; после правки контракта
запускать `npm run generate:api` — расхождение с контроллерами ловит AdminOpenApiContractTest
на стороне backend, расхождение типов — регенерация здесь.

## Конфигурация развёртывания

`public/config.json` читается при старте (сборка одна, контуров несколько):

- `apiBaseUrl` — база Admin BFF (по умолчанию относительная `/api/admin/v1`);
- `oidc.authority` / `clientId` / `scope` — OIDC-провайдер корпоративного SSO (UI-02, SEC-02).
  Пустой `authority` — open mode: панель работает без аутентификации со всеми ролями, с
  предупреждением в шапке — та же позиция, что `@adminAccess.open()` на backend;
- `rolesClaim` — claim токена с SSO-группами (зеркало `commhub.security.roles-claim`,
  по умолчанию `groups`);
- `groupRoles` — маппинг SSO-группа → роль §10.1; группа, совпадающая с именем роли,
  проходит как есть.

Аутентификация — Authorization Code + PKCE (`oidc-client-ts`); токены в `sessionStorage`,
обновление — silent renew. Роли считаются из claim'а профиля или payload access-токена и
гейтят меню и маршруты (`src/auth/roles.ts` — клиентское зеркало `AdminAuthority`); решение
всегда за `@PreAuthorize` на сервере (FR-7.2).

## Конвенции

- Время: хранение и API — UTC, отображение — `Asia/Tashkent` (`src/shared/time.ts`, UI-04).
- i18n: RU (основной и fallback), UZ, EN (`src/i18n`, UI-01).
- PII: адреса маскирует backend по роли; клиентские `maskMsisdn`/`maskEmail` — для значений,
  введённых оператором (DB-04).
- Списки: `ServerTable` — серверная пагинация/сортировка, виртуализация по высоте (UI-03);
  фильтры принадлежат экрану и замыкаются в `fetchPage`.
- Разделы §11.2 объявляются один раз в `src/layout/navigation.tsx` (путь + перевод + секция ролей).
