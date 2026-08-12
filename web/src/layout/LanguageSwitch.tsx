import { Select } from 'antd';
import { useTranslation } from 'react-i18next';

import { LANGUAGES, setLanguage, type Language } from '../i18n';

const LANGUAGE_LABELS: Record<Language, string> = { ru: 'Рус', uz: 'O‘z', en: 'Eng' };

/**
 * Переключатель языка (UI-01). Отдельным компонентом, потому что мест теперь два: шапка панели и
 * форма входа — экран до всякой шапки, на котором язык может понадобиться раньше всего.
 * Для antd placeholder меткой не считается, поэтому aria-label обязателен и живёт здесь один раз.
 */
export function LanguageSwitch() {
  const { t, i18n } = useTranslation();
  return (
    <Select<Language>
      aria-label={t('app.language')}
      size="small"
      value={(i18n.language as Language) ?? 'ru'}
      onChange={setLanguage}
      options={LANGUAGES.map((lng) => ({ value: lng, label: LANGUAGE_LABELS[lng] }))}
    />
  );
}
