import { Result } from 'antd';
import { useTranslation } from 'react-i18next';

/** Заглушка раздела до фазы 17: маршрут, гейтинг и меню уже настоящие, экран — ещё нет. */
export function PlaceholderPage({ titleKey }: { titleKey: string }) {
  const { t } = useTranslation();
  return <Result status="info" title={t(titleKey)} subTitle={t('placeholder.text')} />;
}
