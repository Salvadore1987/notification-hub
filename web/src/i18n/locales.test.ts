import { readFileSync, readdirSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

import i18n, { LANGUAGES, setLanguage } from './index';
import en from './locales/en.json';
import ru from './locales/ru.json';
import uz from './locales/uz.json';

/**
 * UI-01: интерфейс на RU/UZ/EN. Проверяется не качество перевода, а то, что ни один экран не
 * останется без слов: набор ключей во всех языках один, значения непустые, и каждый ключ,
 * который экраны просят у t(), в словаре есть — иначе i18next покажет пользователю сам ключ.
 */
const SRC_DIR = resolve(process.cwd(), 'src');

function flatten(node: unknown, prefix = ''): Record<string, string> {
  if (typeof node === 'string') {
    return { [prefix]: node };
  }
  return Object.entries(node as Record<string, unknown>).reduce<Record<string, string>>(
    (acc, [key, value]) => Object.assign(acc, flatten(value, prefix ? `${prefix}.${key}` : key)),
    {},
  );
}

const DICTIONARIES: Record<string, Record<string, string>> = {
  ru: flatten(ru),
  uz: flatten(uz),
  en: flatten(en),
};

function sourceFiles(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) {
      return entry.name === 'generated' || entry.name === 'test' ? [] : sourceFiles(path);
    }
    return /\.tsx?$/.test(entry.name) && !entry.name.endsWith('.test.ts') ? [path] : [];
  });
}

/** Ключи, которые собираются из шаблонной строки — их считать регуляркой нельзя, они выписаны. */
const DYNAMIC_KEYS = [
  ...['start', 'pause', 'resume', 'stop'].map((action) => `batches.action.${action}`),
  ...['DRAFT', 'ON_REVIEW', 'PUBLISHED', 'ARCHIVED'].map(
    (status) => `templates.transition.${status}`,
  ),
];

describe('translations', () => {
  it('declares the same languages the panel offers', () => {
    expect([...LANGUAGES]).toEqual(Object.keys(DICTIONARIES));
  });

  it.each(['uz', 'en'])('%s carries exactly the keys of the RU dictionary', (language) => {
    const ruKeys = Object.keys(DICTIONARIES.ru).sort();
    expect(Object.keys(DICTIONARIES[language]).sort()).toEqual(ruKeys);
  });

  it.each(Object.keys(DICTIONARIES))('%s has no empty translation', (language) => {
    const empty = Object.entries(DICTIONARIES[language])
      .filter(([, value]) => value.trim() === '')
      .map(([key]) => key);
    expect(empty).toEqual([]);
  });

  it.each(Object.keys(DICTIONARIES))('%s keeps every interpolation of the RU wording', (lang) => {
    const placeholders = (value: string) =>
      [...value.matchAll(/{{(\w+)}}/g)].map((m) => m[1]).sort();
    for (const [key, value] of Object.entries(DICTIONARIES.ru)) {
      expect(placeholders(DICTIONARIES[lang][key]), key).toEqual(placeholders(value));
    }
  });

  it('has a translation for every key the screens ask for', () => {
    const used = new Set(DYNAMIC_KEYS);
    for (const file of sourceFiles(SRC_DIR)) {
      const source = readFileSync(file, 'utf8');
      for (const match of source.matchAll(/\bt\(\s*'([a-z][\w.]*)'/g)) {
        used.add(match[1]);
      }
      for (const match of source.matchAll(/labelKey:\s*'([\w.]+)'/g)) {
        used.add(match[1]);
      }
    }
    const missing = [...used].filter((key) => !(key in DICTIONARIES.ru));
    expect(missing).toEqual([]);
    expect(used.size).toBeGreaterThan(50);
  });

  it('falls back to RU for a language that misses a key — RU is the minimum of UI-01', () => {
    expect([i18n.options.fallbackLng].flat()).toContain('ru');
  });

  it('remembers the chosen language for the next session', async () => {
    setLanguage('en');
    expect(localStorage.getItem('commhub-admin.language')).toBe('en');
    expect(i18n.t('nav.dashboard')).toBe(DICTIONARIES.en['nav.dashboard']);
    setLanguage('ru');
    expect(i18n.t('nav.dashboard')).toBe(DICTIONARIES.ru['nav.dashboard']);
  });
});
