import { Result } from 'antd';
import type { PropsWithChildren } from 'react';
import { useTranslation } from 'react-i18next';

import { useSession } from './sessionContext';
import type { Section } from './roles';

/**
 * Гейт маршрута по секции §11.2. Дублирует backend, а не заменяет его (UI-02): прямой переход по
 * URL без роли получает 403-экран здесь, и тот же 403 — от @PreAuthorize, если экран обойдут.
 */
export function RequireSection({ section, children }: PropsWithChildren<{ section: Section }>) {
  const session = useSession();
  const { t } = useTranslation();
  if (!session.canSee(section)) {
    return <Result status="403" title="403" subTitle={t('auth.forbidden')} />;
  }
  return <>{children}</>;
}
