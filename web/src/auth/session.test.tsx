import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import '../i18n';
import { AppConfigProvider, DEFAULT_CONFIG, type AppConfig } from '../config/appConfig';
import { SessionProvider } from './session';
import { useSession } from './sessionContext';

/**
 * Вход в панель целиком (ADR-0043): форма — наша, издатель только выдаёт токен.
 *
 * <p>Стабится ровно граница с издателем (`fetch` на discovery и token endpoint) — всё остальное
 * работает так же, как в браузере: sessionStorage, разбор claim'ов, роли §10.1.
 */
const CONFIG: AppConfig = {
  ...DEFAULT_CONFIG,
  oidc: { ...DEFAULT_CONFIG.oidc, authority: 'https://issuer.test/realms/commhub' },
};

const TOKEN_ENDPOINT = 'https://issuer.test/protocol/openid-connect/token';

/** Токен, каким его выдаёт Keycloak: claim'ы читаются из payload, подпись панель не проверяет. */
function accessToken(username: string, groups: string[]): string {
  const payload = btoa(JSON.stringify({ preferred_username: username, groups }))
    .replace(/\+/g, '-')
    .replace(/\//g, '_');
  return `header.${payload}.signature`;
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

const fetchMock = vi.fn<typeof fetch>();

/** discovery отвечает всегда; ответ token endpoint задаёт тест. */
function issuerAnswers(token: () => Response): void {
  fetchMock.mockImplementation((input) => {
    const url = String(input);
    if (url.includes('/.well-known/openid-configuration')) {
      return Promise.resolve(json({ token_endpoint: TOKEN_ENDPOINT }));
    }
    return Promise.resolve(token());
  });
}

function Screen() {
  const session = useSession();
  return <div>{`панель, ${session.userName}, ${session.roles.join('+')}`}</div>;
}

function renderPanel() {
  return render(
    <AppConfigProvider value={CONFIG}>
      <SessionProvider>
        <Screen />
      </SessionProvider>
    </AppConfigProvider>,
  );
}

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  window.sessionStorage.clear();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('SessionProvider', () => {
  it('shows the panel only after a successful sign-in, with the roles of the token', async () => {
    issuerAnswers(() =>
      json({ access_token: accessToken('demo', ['ADMIN']), refresh_token: 'rt', expires_in: 300 }),
    );
    renderPanel();

    await screen.findByLabelText('Пользователь');
    expect(screen.queryByText(/панель,/)).not.toBeInTheDocument();

    await userEvent.type(screen.getByLabelText('Пользователь'), 'demo');
    await userEvent.type(screen.getByLabelText('Пароль'), 'demo');
    await userEvent.click(screen.getByRole('button', { name: 'Войти' }));

    expect(await screen.findByText('панель, demo, ADMIN')).toBeInTheDocument();
  });

  it('keeps the operator on the form and names the reason when the password is wrong', async () => {
    issuerAnswers(() => json({ error: 'invalid_grant' }, 401));
    renderPanel();

    await userEvent.type(await screen.findByLabelText('Пользователь'), 'demo');
    await userEvent.type(screen.getByLabelText('Пароль'), 'wrong');
    await userEvent.click(screen.getByRole('button', { name: 'Войти' }));

    expect(await screen.findByText('Неверное имя пользователя или пароль')).toBeInTheDocument();
    expect(screen.getByLabelText('Пользователь')).toBeInTheDocument();
    expect(screen.queryByText(/панель,/)).not.toBeInTheDocument();
  });

  it('renews a stored session instead of asking for the password again (F5)', async () => {
    window.sessionStorage.setItem(
      'commhub.session',
      JSON.stringify({ accessToken: 'stale', refreshToken: 'rt', expiresAt: Date.now() - 1_000 }),
    );
    issuerAnswers(() =>
      json({
        access_token: accessToken('operator', ['OPERATOR']),
        refresh_token: 'rt2',
        expires_in: 300,
      }),
    );

    renderPanel();

    expect(await screen.findByText('панель, operator, OPERATOR')).toBeInTheDocument();
    const grants = fetchMock.mock.calls.map((call) =>
      new URLSearchParams(String(call[1]?.body)).get('grant_type'),
    );
    expect(grants).toContain('refresh_token');
    expect(grants).not.toContain('password');
  });

  it('asks for the password when the stored session cannot be renewed', async () => {
    window.sessionStorage.setItem(
      'commhub.session',
      JSON.stringify({ accessToken: 'stale', refreshToken: 'rt', expiresAt: Date.now() - 1_000 }),
    );
    issuerAnswers(() => json({ error: 'invalid_grant' }, 400));

    renderPanel();

    expect(await screen.findByLabelText('Пользователь')).toBeInTheDocument();
    await waitFor(() => expect(window.sessionStorage.getItem('commhub.session')).toBeNull());
  });

  it('refuses to open at all when the contour named no issuer (ADR-0037)', async () => {
    render(
      <AppConfigProvider value={DEFAULT_CONFIG}>
        <SessionProvider>
          <Screen />
        </SessionProvider>
      </AppConfigProvider>,
    );

    expect(await screen.findByText('Панель не настроена')).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
