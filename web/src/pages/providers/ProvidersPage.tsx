import { Tabs } from 'antd';
import { useTranslation } from 'react-i18next';

import { ChannelsTab } from './ChannelsTab';
import { ProvidersTab } from './ProvidersTab';
import { TestSendTab } from './TestSendTab';

/** Раздел «Каналы и провайдеры» §11.2: канал — стратегия и fallback, провайдер — профиль, тест — FR-7.4. */
export function ProvidersPage() {
  const { t } = useTranslation();
  return (
    <Tabs
      destroyInactiveTabPane
      items={[
        { key: 'providers', label: t('providers.tabProviders'), children: <ProvidersTab /> },
        { key: 'channels', label: t('providers.tabChannels'), children: <ChannelsTab /> },
        { key: 'testSend', label: t('providers.tabTestSend'), children: <TestSendTab /> },
      ]}
    />
  );
}
