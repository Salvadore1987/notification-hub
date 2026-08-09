import i18next from 'i18next';
import { initReactI18next } from 'react-i18next';
import dayjs from 'dayjs';
import 'dayjs/locale/ru';
import 'dayjs/locale/uz-latn';

import en from './locales/en.json';
import ru from './locales/ru.json';
import uz from './locales/uz.json';

/** UI-01: RU/UZ/EN, интерфейс — минимум RU, поэтому RU и язык по умолчанию, и fallback. */
export const LANGUAGES = ['ru', 'uz', 'en'] as const;
export type Language = (typeof LANGUAGES)[number];

const STORAGE_KEY = 'commhub-admin.language';

function storedLanguage(): Language {
  const value = localStorage.getItem(STORAGE_KEY);
  return (LANGUAGES as readonly string[]).includes(value ?? '') ? (value as Language) : 'ru';
}

const DAYJS_LOCALES: Record<Language, string> = { ru: 'ru', uz: 'uz-latn', en: 'en' };

export function setLanguage(language: Language): void {
  localStorage.setItem(STORAGE_KEY, language);
  dayjs.locale(DAYJS_LOCALES[language]);
  document.documentElement.lang = language;
  void i18next.changeLanguage(language);
}

void i18next.use(initReactI18next).init({
  resources: {
    ru: { translation: ru },
    uz: { translation: uz },
    en: { translation: en },
  },
  lng: storedLanguage(),
  fallbackLng: 'ru',
  interpolation: { escapeValue: false },
});

dayjs.locale(DAYJS_LOCALES[storedLanguage()]);

// Язык документа — не украшение: экранный диктор выбирает по нему правила чтения, поэтому
// он живёт вместе с выбранным языком панели, а не остаётся тем, что записано в index.html.
document.documentElement.lang = storedLanguage();

export default i18next;
