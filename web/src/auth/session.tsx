import { Result, Spin } from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState, type PropsWithChildren } from 'react';
import { useTranslation } from 'react-i18next';

import { setAccessTokenProvider } from '../api/client';
import { useAppConfig, type AppConfig } from '../config/appConfig';
import { tokenClaims } from './jwt';
import { LoginPage } from './LoginPage';
import { rolesFromClaim } from './roles';
import { makeSession, SessionContext } from './sessionContext';
import { endSession, passwordGrant, refreshGrant, type TokenSet } from './tokenClient';
import { clearSession, readSession, writeSession } from './tokenStore';

/**
 * Панель за SSO на любом контуре (ADR-0037): открытого режима нет ни здесь, ни на backend'е, где
 * админ-цепочка безусловно требует токен, а инстанс без issuer'а не стартует. Пустой authority —
 * это не «SSO не настроено, работаем так», а ошибка конфигурации развёртывания, и панель говорит
 * об этом вместо того, чтобы пустить внутрь.
 *
 * <p>Что изменилось (ADR-0043): токен добывается формой входа самой панели, а не редиректом на
 * издателя. Требование «без токена внутрь не пускают» осталось прежним — другим стал способ
 * этот токен получить.
 */
export function SessionProvider({ children }: PropsWithChildren) {
  const config = useAppConfig();
  const { t } = useTranslation();
  if (!config.oidc.authority) {
    return (
      <Result
        status="error"
        title={t('auth.notConfigured')}
        subTitle={t('auth.notConfiguredHint')}
      />
    );
  }
  return <PasswordSession config={config}>{children}</PasswordSession>;
}

/** За сколько до истечения продлевать: запас на дорогу до издателя и на расхождение часов. */
const RENEW_SKEW_MS = 30_000;

function PasswordSession({ config, children }: PropsWithChildren<{ config: AppConfig }>) {
  const { t } = useTranslation();
  const [tokens, setTokens] = useState<TokenSet | null>(null);
  const [restoring, setRestoring] = useState(true);
  const [expired, setExpired] = useState(false);
  // Провайдер токена для API-клиента читает ref, а не состояние: он живёт дольше рендера.
  const current = useRef<TokenSet | null>(null);
  current.current = tokens;

  useEffect(() => {
    setAccessTokenProvider(() => current.current?.accessToken ?? null);
    return () => setAccessTokenProvider(() => null);
  }, []);

  // Перезагрузка страницы — обычное действие во время инцидента, и она не должна быть повторным
  // вводом пароля: сессия восстанавливается из хранилища, просроченный access-токен молча
  // обменивается на новый, пока жив refresh.
  useEffect(() => {
    let cancelled = false;
    const stored = readSession();
    if (!stored) {
      setRestoring(false);
      return;
    }
    if (stored.expiresAt - RENEW_SKEW_MS > Date.now()) {
      setTokens(stored);
      setRestoring(false);
      return;
    }
    if (!stored.refreshToken) {
      clearSession();
      setRestoring(false);
      return;
    }
    refreshGrant(config.oidc, stored.refreshToken)
      .then((next) => {
        if (!cancelled) {
          writeSession(next);
          setTokens(next);
        }
      })
      .catch(() => clearSession())
      .finally(() => {
        if (!cancelled) {
          setRestoring(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [config.oidc]);

  // Продление до истечения, а не после: иначе первый же запрос оператора отвечает 401, и он видит
  // отказ там, где не делал ничего запрещённого. Не удалось продлить — честная форма входа
  // с объяснением, а не тихо мёртвая сессия.
  useEffect(() => {
    const refreshToken = tokens?.refreshToken;
    if (!refreshToken) {
      return;
    }
    const timer = window.setTimeout(
      () => {
        refreshGrant(config.oidc, refreshToken)
          .then((next) => {
            writeSession(next);
            setTokens(next);
          })
          .catch(() => {
            clearSession();
            setTokens(null);
            setExpired(true);
          });
      },
      Math.max(tokens.expiresAt - Date.now() - RENEW_SKEW_MS, 1_000),
    );
    return () => window.clearTimeout(timer);
  }, [tokens, config.oidc]);

  const signIn = useCallback(
    async (username: string, password: string) => {
      const next = await passwordGrant(config.oidc, username, password);
      writeSession(next);
      setExpired(false);
      setTokens(next);
    },
    [config.oidc],
  );

  const signOut = useCallback(() => {
    const refreshToken = current.current?.refreshToken;
    clearSession();
    setExpired(false);
    setTokens(null);
    if (refreshToken) {
      void endSession(config.oidc, refreshToken);
    }
  }, [config.oidc]);

  const claims = useMemo(() => (tokens ? tokenClaims(tokens.accessToken) : {}), [tokens]);

  const session = useMemo(
    () =>
      makeSession(
        rolesFromClaim(claims[config.rolesClaim], config.groupRoles),
        typeof claims.preferred_username === 'string'
          ? claims.preferred_username
          : typeof claims.sub === 'string'
            ? claims.sub
            : undefined,
        signOut,
      ),
    [claims, config.rolesClaim, config.groupRoles, signOut],
  );

  if (restoring) {
    return <Spin fullscreen size="large" tip={t('auth.signingIn')} />;
  }
  if (!tokens) {
    return <LoginPage onSignIn={signIn} expired={expired} />;
  }
  return <SessionContext.Provider value={session}>{children}</SessionContext.Provider>;
}
