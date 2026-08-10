import { Button, Result, Spin } from 'antd';
import { WebStorageStateStore } from 'oidc-client-ts';
import { useEffect, useMemo, useRef, type PropsWithChildren } from 'react';
import { AuthProvider, hasAuthParams, useAuth } from 'react-oidc-context';
import { useTranslation } from 'react-i18next';

import { setAccessTokenProvider } from '../api/client';
import { useAppConfig, type AppConfig } from '../config/appConfig';
import { restoreReturnTo, signinState } from './returnTo';
import { rolesFromClaim } from './roles';
import { makeSession, SessionContext } from './sessionContext';

/**
 * Панель за SSO на любом контуре (ADR-0037): открытого режима нет ни здесь, ни на backend'е, где
 * админ-цепочка безусловно требует токен, а инстанс без issuer'а не стартует. Пустой authority —
 * это не «SSO не настроено, работаем так», а ошибка конфигурации развёртывания, и панель говорит
 * об этом вместо того, чтобы пустить внутрь.
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
  return <OidcSession config={config}>{children}</OidcSession>;
}

function OidcSession({ config, children }: PropsWithChildren<{ config: AppConfig }>) {
  const oidcConfig = useMemo(
    () => ({
      authority: config.oidc.authority,
      client_id: config.oidc.clientId,
      redirect_uri: `${window.location.origin}/auth/callback`,
      post_logout_redirect_uri: window.location.origin,
      scope: config.oidc.scope,
      // sessionStorage, не localStorage: токен живёт со вкладкой, а не переживает её (SEC-02).
      userStore: new WebStorageStateStore({ store: window.sessionStorage }),
      automaticSilentRenew: true,
      onSigninCallback: restoreReturnTo,
    }),
    [config],
  );
  return (
    <AuthProvider {...oidcConfig}>
      <OidcSessionInner config={config}>{children}</OidcSessionInner>
    </AuthProvider>
  );
}

function OidcSessionInner({ config, children }: PropsWithChildren<{ config: AppConfig }>) {
  const auth = useAuth();
  const { t } = useTranslation();
  const signinAttempted = useRef(false);

  useEffect(() => {
    setAccessTokenProvider(() => auth.user?.access_token ?? null);
    return () => setAccessTokenProvider(() => null);
  }, [auth.user]);

  // Панель целиком за SSO: неаутентифицированная загрузка сразу уходит на issuer. Один раз —
  // чтобы отказ issuer стал экраном с кнопкой, а не циклом редиректов.
  useEffect(() => {
    if (
      !auth.isAuthenticated &&
      !auth.isLoading &&
      !auth.activeNavigator &&
      !hasAuthParams() &&
      !signinAttempted.current
    ) {
      signinAttempted.current = true;
      void auth.signinRedirect({ state: signinState() });
    }
  }, [auth]);

  const roles = useMemo(() => {
    if (!auth.user) {
      return [];
    }
    const fromProfile = auth.user.profile[config.rolesClaim];
    const claim = fromProfile ?? accessTokenClaim(auth.user.access_token, config.rolesClaim);
    return rolesFromClaim(claim, config.groupRoles);
  }, [auth.user, config]);

  const session = useMemo(
    () =>
      makeSession(
        roles,
        auth.user?.profile.preferred_username ?? auth.user?.profile.sub,
        () => void auth.signoutRedirect(),
      ),
    [roles, auth],
  );

  if (auth.error) {
    return (
      <Result
        status="warning"
        title={t('auth.error')}
        subTitle={auth.error.message}
        extra={
          <Button type="primary" onClick={() => void auth.signinRedirect({ state: signinState() })}>
            {t('auth.signIn')}
          </Button>
        }
      />
    );
  }
  if (!auth.isAuthenticated) {
    return (
      <Spin
        size="large"
        style={{ display: 'block', marginTop: '20vh' }}
        tip={t('auth.redirecting')}
      />
    );
  }
  return <SessionContext.Provider value={session}>{children}</SessionContext.Provider>;
}

/**
 * Профиль — это claims id-токена; Keycloak кладёт группы туда только при соответствующем mapper'е,
 * поэтому вторым источником читается payload access-токена — тот же документ, по которому роли
 * решает backend.
 */
function accessTokenClaim(accessToken: string, claim: string): unknown {
  try {
    const payload = accessToken.split('.')[1];
    const decoded = JSON.parse(
      new TextDecoder().decode(
        Uint8Array.from(atob(payload.replace(/-/g, '+').replace(/_/g, '/')), (c) =>
          c.charCodeAt(0),
        ),
      ),
    ) as Record<string, unknown>;
    return decoded[claim];
  } catch {
    return undefined;
  }
}
