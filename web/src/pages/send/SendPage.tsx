import { Tabs } from 'antd';
import { useTranslation } from 'react-i18next';

import { BulkSendTab } from './BulkSendTab';
import { SingleSendTab } from './SingleSendTab';

/**
 * Отправка, инициированная оператором (ADR-0038).
 *
 * Свой раздел, а не вкладка в «Рассылках»: у отправки своя роль, своё обоснование и своё
 * состояние экрана, а необратимая кнопка рядом с таблицей только для чтения — плохая идея.
 */
export function SendPage() {
  const { t } = useTranslation();
  return (
    <Tabs
      destroyInactiveTabPane
      items={[
        { key: 'single', label: t('send.tabSingle'), children: <SingleSendTab /> },
        { key: 'bulk', label: t('send.tabBulk'), children: <BulkSendTab /> },
      ]}
    />
  );
}
