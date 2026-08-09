import '@testing-library/jest-dom/vitest';

import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

/**
 * Заглушки браузерного окружения ставятся в теле модуля, а не в beforeAll: setup-файл выполняется
 * до импорта тестов, а хуки — уже после, когда модули (i18n, antd) успели прочитать глобальные
 * объекты.
 *
 * Node объявляет свой экспериментальный localStorage (без --localstorage-file он нерабочий), и
 * jsdom поверх него уже ничего не кладёт; панель хранит там выбранный язык (UI-01).
 */
if (typeof globalThis.localStorage?.getItem !== 'function') {
  Object.defineProperty(globalThis, 'localStorage', {
    value: memoryStorage(),
    configurable: true,
    writable: true,
  });
}

/** Ant Design считает, что живёт в браузере: адаптивные сетки спрашивают matchMedia, таблицы и
 *  выпадающие списки — ResizeObserver. jsdom не реализует ни того, ни другого. */
if (!window.matchMedia) {
  window.matchMedia = (query: string) =>
    ({
      matches: false,
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    }) as MediaQueryList;
}

if (!window.ResizeObserver) {
  window.ResizeObserver = class {
    observe(): void {}
    unobserve(): void {}
    disconnect(): void {}
  };
}

if (!window.scrollTo) {
  window.scrollTo = () => {};
}

afterEach(() => {
  cleanup();
});

function memoryStorage(): Storage {
  const entries = new Map<string, string>();
  return {
    get length() {
      return entries.size;
    },
    clear: () => entries.clear(),
    getItem: (key: string) => entries.get(key) ?? null,
    key: (index: number) => [...entries.keys()][index] ?? null,
    removeItem: (key: string) => void entries.delete(key),
    setItem: (key: string, value: string) => void entries.set(key, value),
  };
}
