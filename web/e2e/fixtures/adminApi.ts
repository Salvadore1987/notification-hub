import { test as base, type Page, type Route } from '@playwright/test';

/**
 * Заглушка Admin BFF на уровне сети. Backend в E2E не поднимается сознательно: сценарии QA-07
 * проверяют панель — что нажимает оператор, что уходит на сервер и что он видит в ответ, — а
 * поведение самого Хаба покрыто его интеграционными тестами (Phase 15).
 *
 * Состояние заглушки живое: пауза рассылки меняет статус в карточке, публикация версии меняет
 * её статус в таблице. Иначе сценарий проверял бы только факт запроса, а не то, что оператор
 * увидел результат.
 */
export interface RecordedRequest {
  readonly key: string;
  readonly body: unknown;
  readonly headers: Record<string, string>;
  readonly query: URLSearchParams;
}

export interface AdminStub {
  /** Все запросы к BFF в порядке отправки: «POST /dlq/retry» и что в нём было. */
  readonly requests: RecordedRequest[];
  readonly lastRequest: (key: string) => RecordedRequest | undefined;
  /** Подменить ответ конкретного маршрута — для сценариев отказа. */
  readonly stub: (key: string, body: unknown, status?: number) => void;
}

const BASE = '/api/admin/v1';

interface State {
  batchStatus: 'PROCESSING' | 'PAUSED' | 'STOPPED' | 'ACCEPTED' | 'COMPLETED';
  dlq: {
    messageId: string;
    reason: string;
    lastError: string;
    movedAt: string;
    retryable: boolean;
    archived: boolean;
  }[];
  /** Словарь доменный (`TemplateStatus`): «отклонена» — это возврат в DRAFT, отдельного статуса нет. */
  versionStatus: 'DRAFT' | 'ON_REVIEW' | 'PUBLISHED' | 'ARCHIVED';
}

function initialState(): State {
  return {
    batchStatus: 'PROCESSING',
    dlq: [
      {
        messageId: '018f-0000-0000-0000-000000000001',
        reason: 'PROVIDER_REJECTED',
        lastError: 'playmobile 103: internal error',
        movedAt: '2026-08-09T05:00:00Z',
        retryable: true,
        archived: false,
      },
      {
        messageId: '018f-0000-0000-0000-000000000002',
        reason: 'ATTEMPTS_EXHAUSTED',
        lastError: 'no answer from provider',
        movedAt: '2026-08-09T06:00:00Z',
        retryable: true,
        archived: false,
      },
    ],
    versionStatus: 'ON_REVIEW',
  };
}

function batchCard(state: State) {
  return {
    batchId: '018f-batch-0001',
    streamId: 'core-banking',
    channel: 'SMS',
    status: state.batchStatus,
    total: 1000,
    costEstimate: '125000.00',
    createdAt: '2026-08-09T04:00:00Z',
    progress: { processed: 400, sent: 380, delivered: 350, failed: 20, completionPercent: 40 },
  };
}

function templateCard(state: State) {
  return {
    code: 'OTP_LOGIN',
    channel: 'SMS',
    direction: 'AUTH',
    owner: 'security',
    catalogStatus: 'ACTIVE',
    versions: [
      {
        versionId: '018f-version-0001',
        locale: 'RU',
        version: 2,
        status: state.versionStatus,
        body: { text: 'Код подтверждения: {{code}}' },
        variables: ['code'],
        review: {
          createdBy: 'author',
          reviewedBy: state.versionStatus === 'PUBLISHED' ? 'tester' : undefined,
        },
      },
    ],
    providerMappings: [],
  };
}

function handle(state: State, key: string, body: unknown): { status: number; body: unknown } {
  switch (key) {
    case 'GET /dashboard':
      return {
        status: 200,
        body: {
          totals: { accepted: 12000, delivered: 11400, failed: 300, deliveryRate: 0.95 },
          otpLatencyP99Millis: 1800,
          channels: [{ channel: 'SMS', accepted: 12000, delivered: 11400, failed: 300 }],
          providers: [{ providerCode: 'playmobile', health: 'UP', successRate: 0.97 }],
          backlog: { outboxAgeSeconds: 2, activeBatches: [batchCard(state)] },
          killSwitch: { active: false },
        },
      };
    case 'GET /batches':
      return { status: 200, body: { items: [batchCard(state)], total: 1 } };
    case 'GET /batches/018f-batch-0001':
      return { status: 200, body: batchCard(state) };
    case 'POST /batches/018f-batch-0001/actions/pause':
      state.batchStatus = 'PAUSED';
      return { status: 200, body: batchCard(state) };
    case 'POST /batches/018f-batch-0001/actions/resume':
      state.batchStatus = 'PROCESSING';
      return { status: 200, body: batchCard(state) };
    case 'GET /dlq':
      return { status: 200, body: { items: state.dlq, total: state.dlq.length } };
    case 'POST /dlq/retry': {
      const ids = ((body as { messageIds?: string[] })?.messageIds ?? []).filter((id) =>
        state.dlq.some((entry) => entry.messageId === id),
      );
      state.dlq = state.dlq.filter((entry) => !ids.includes(entry.messageId));
      return { status: 200, body: { applied: ids, skipped: [] } };
    }
    case 'POST /dlq/archive': {
      const ids = (body as { messageIds?: string[] })?.messageIds ?? [];
      state.dlq = state.dlq.map((entry) =>
        ids.includes(entry.messageId) ? { ...entry, archived: true } : entry,
      );
      return { status: 200, body: { applied: ids, skipped: [] } };
    }
    case 'GET /messages':
      return {
        status: 200,
        body: {
          items: [
            {
              messageId: '018f-message-0001',
              batchId: '018f-batch-0001',
              streamId: 'core-banking',
              channel: 'SMS',
              status: 'DELIVERED',
              recipient: '99890***4567',
              acceptedAt: '2026-08-09T04:05:00Z',
            },
          ],
          total: 1,
        },
      };
    case 'GET /templates':
      return {
        status: 200,
        body: {
          items: [
            {
              templateId: '018f-template-0001',
              code: 'OTP_LOGIN',
              channel: 'SMS',
              direction: 'AUTH',
              owner: 'security',
              catalogStatus: 'ACTIVE',
              locales: ['RU'],
            },
          ],
          total: 1,
        },
      };
    case 'GET /templates/OTP_LOGIN':
      return { status: 200, body: templateCard(state) };
    case 'POST /templates/OTP_LOGIN/versions/RU/2/state/PUBLISHED':
      state.versionStatus = 'PUBLISHED';
      return { status: 200, body: templateCard(state) };
    case 'POST /templates/OTP_LOGIN/versions/RU/2/state/DRAFT':
      state.versionStatus = 'DRAFT';
      return { status: 200, body: templateCard(state) };
    case 'GET /providers':
      return {
        status: 200,
        body: [
          {
            providerId: '018f-provider-0001',
            code: 'playmobile',
            channel: 'SMS',
            adapterType: 'playmobile-http',
            state: 'ACTIVE',
            priority: 10,
            weight: 1,
          },
        ],
      };
    // Не справочник, а то, что развёрнуто на контуре: форма регистрации выбирает отсюда (AR-04).
    case 'GET /providers/adapters':
      return {
        status: 200,
        body: [
          { adapterType: 'smtp', channel: 'EMAIL' },
          { adapterType: 'apns-http2', channel: 'PUSH' },
          { adapterType: 'fcm-http', channel: 'PUSH' },
          { adapterType: 'playmobile-http', channel: 'SMS' },
          { adapterType: 'smsgate-http', channel: 'SMS' },
        ],
      };
    case 'GET /channels':
      return {
        status: 200,
        body: [
          {
            channel: 'SMS',
            status: 'ACTIVE',
            balancingStrategy: 'PRIMARY_ONLY',
            fallbackOrder: ['playmobile'],
            available: true,
          },
        ],
      };
    case 'POST /providers/test-send':
      return {
        status: 200,
        body: {
          messageId: '018f-message-0001',
          status: 'ACCEPTED',
          acceptedAt: '2026-08-09T07:00:00Z',
        },
      };
    default:
      return { status: 404, body: { title: 'no stub for ' + key, status: 404, code: 'NOT_FOUND' } };
  }
}

export async function installAdminApi(page: Page): Promise<AdminStub> {
  const state = initialState();
  const requests: RecordedRequest[] = [];
  const overrides = new Map<string, { status: number; body: unknown }>();

  await page.route(`**${BASE}/**`, async (route: Route) => {
    const request = route.request();
    const url = new URL(request.url());
    const key = `${request.method()} ${url.pathname.replace(BASE, '')}`;
    const body = request.postData();
    const parsed = body ? safeJson(body) : undefined;
    requests.push({
      key,
      body: parsed,
      headers: request.headers(),
      query: url.searchParams,
    });
    const answer = overrides.get(key) ?? handle(state, key, parsed);
    await route.fulfill({
      status: answer.status,
      contentType: answer.status >= 400 ? 'application/problem+json' : 'application/json',
      body: JSON.stringify(answer.body),
    });
  });

  return {
    requests,
    lastRequest: (key) => [...requests].reverse().find((entry) => entry.key === key),
    stub: (key, body, status = 200) => overrides.set(key, { status, body }),
  };
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

/**
 * Тест с уже поднятой заглушкой BFF: экраны в E2E всегда говорят с ней, а не с сетью.
 * `auto` — потому что перехват должен стоять раньше любого `beforeEach` с переходом на экран.
 *
 * `page` подменяется здесь же, и это не удобство, а необходимость: панель за SSO (ADR-0037), так
 * что любой переход — это уход на Keycloak и возврат через /auth/callback. `page.goto` возвращает
 * управление, когда загрузился первый документ, то есть до редиректа; тест, который сразу смотрит
 * на страницу (axe, например, а не локатор с автоожиданием), увидел бы Spin или форму логина.
 * Ожидание живёт в фикстуре, а не в каждом spec'е, потому что забыть его — значит получить
 * плавающий тест, а не ошибку.
 */
export const test = base.extend<{ admin: AdminStub }>({
  // Параметр назван runTest, а не use: правило react-hooks принимает вызов use() внутри функции
  // с именем page за хук в не-компоненте. Фикстура Playwright к React отношения не имеет.
  page: async ({ page }, runTest) => {
    const navigate = page.goto.bind(page);
    page.goto = async (url, options) => {
      const response = await navigate(url, options);
      await waitForPanel(page, url);
      return response;
    };
    await runTest(page);
  },
  admin: [
    async ({ page }, use) => {
      await use(await installAdminApi(page));
    },
    { auto: true },
  ],
});

/**
 * Редирект на issuer прошёл, код обменян, панель отрисовалась — и вернулась она **на запрошенный
 * экран**, а не на дашборд. Последнее проверяется здесь, а значит проверяется каждым сценарием:
 * возврат по returnTo — не отдельный тест, а предусловие всех остальных.
 */
async function waitForPanel(page: Page, requested: string): Promise<void> {
  const wanted = new URL(requested, 'http://localhost:5173').pathname;
  await page.waitForURL((url) => url.port === '5173' && url.pathname === wanted, {
    timeout: 30_000,
  });
  await page.locator('.ant-layout-sider').first().waitFor({ state: 'visible', timeout: 30_000 });
}

export { expect } from '@playwright/test';
