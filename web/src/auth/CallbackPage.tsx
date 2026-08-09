import { Spin } from 'antd';
import { useTranslation } from 'react-i18next';

/**
 * redirect_uri OIDC-потока. Сам обмен кода на токены делает AuthProvider (react-oidc-context
 * замечает code/state в URL на любом маршруте); страница лишь занимает URL и показывает ожидание.
 */
export function CallbackPage() {
  const { t } = useTranslation();
  return (
    <Spin size="large" style={{ display: 'block', marginTop: '20vh' }} tip={t('auth.signingIn')} />
  );
}
