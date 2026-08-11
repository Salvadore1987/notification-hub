import { useCallback, useEffect, useState } from 'react';

import { api } from '../api/client';
import type { components } from '../api/generated/admin-schema';

type Stream = components['schemas']['Stream'];
type Provider = components['schemas']['Provider'];
type DeployedAdapter = components['schemas']['DeployedAdapter'];
type TemplateSummary = components['schemas']['TemplateSummary'];

/**
 * Справочники, из которых выбирают: потоки, провайдеры и каталог шаблонов.
 *
 * Первый в панели способ наполнить `Select` из API — до сих пор все списки были перечислениями.
 * Поэтому он один на всех: пять экранов, каждый со своим `useEffect`, разошлись бы в кэшировании и
 * в поведении при отказе быстрее, чем кто-нибудь это заметил.
 *
 * Кэш на уровне модуля с коротким TTL нужен не ради экономии запроса: `SendPage` размонтирует
 * вкладку при переключении (`destroyInactiveTabPane`), и без него каждый переход между «Одним
 * сообщением» и «Рассылкой из файла» перезапрашивал бы оба списка.
 *
 * **Отказ не блокирует форму.** Если справочник не пришёл — прав не хватило, backend недоступен —
 * поле деградирует в обычный ввод: оператор, знающий код, должен иметь возможность его набрать.
 * Запрещать отправку из-за выпадающего списка было бы хуже самой проблемы.
 */

/** Сколько живёт закэшированный ответ. Конфигурация меняется редко, а форма открывается часто. */
const TTL_MILLIS = 60_000;

interface CacheEntry {
  readonly loadedAt: number;
  readonly value: unknown;
}

const cache = new Map<string, CacheEntry>();
const inFlight = new Map<string, Promise<unknown>>();

/**
 * Забыть загруженное.
 *
 * Кэш живёт в модуле, а не в компоненте, поэтому переживает и размонтирование формы, и — в тестах —
 * переход к следующему сценарию. Сбрасывается здесь: смена пользователя или проверка поведения при
 * отказе не должны получать ответ, загруженный для кого-то другого.
 */
export function clearReferenceCache(): void {
  cache.clear();
  inFlight.clear();
}

/** Что знает поле о своём справочнике. */
export interface Reference<T> {
  readonly items: readonly T[];
  readonly loading: boolean;
  /** Справочник недоступен — поле обязано деградировать в ручной ввод. */
  readonly failed: boolean;
}

function useReference<T>(key: string, loader: () => Promise<T[]>): Reference<T> {
  const [items, setItems] = useState<readonly T[]>([]);
  const [loading, setLoading] = useState(false);
  const [failed, setFailed] = useState(false);

  const load = useCallback(async () => {
    const cached = cache.get(key);
    if (cached && Date.now() - cached.loadedAt < TTL_MILLIS) {
      setItems(cached.value as T[]);
      return;
    }
    setLoading(true);
    try {
      // Два поля одного экрана просят один справочник — запрос должен быть один.
      const pending = inFlight.get(key) ?? loader();
      inFlight.set(key, pending);
      const value = (await pending) as T[];
      cache.set(key, { loadedAt: Date.now(), value });
      setItems(value);
      setFailed(false);
    } catch {
      // Причина не показывается: поле молча становится вводом, а сообщение об ошибке рядом с
      // работающим полем читается как «всё сломано», хотя отправка возможна.
      setFailed(true);
    } finally {
      inFlight.delete(key);
      setLoading(false);
    }
  }, [key, loader]);

  useEffect(() => {
    void load();
  }, [load]);

  return { items, loading, failed };
}

/** Все зарегистрированные потоки; список не пагинирован (§11.2). */
export function useStreams(): Reference<Stream> {
  const loader = useCallback(async () => {
    const result = await api().GET('/streams');
    return result.data ?? [];
  }, []);
  return useReference('streams', loader);
}

/** Все профили провайдеров с их живым состоянием. */
export function useProviders(): Reference<Provider> {
  const loader = useCallback(async () => {
    const result = await api().GET('/providers');
    return result.data ?? [];
  }, []);
  return useReference('providers', loader);
}

/**
 * Типы адаптеров, развёрнутые на этом контуре (AR-04).
 *
 * Единственный справочник здесь, который не хранится в базе: это поднятые бины канальных портов.
 * Меняется деплоем, а не панелью, поэтому TTL ему велик с запасом.
 */
export function useAdapters(): Reference<DeployedAdapter> {
  const loader = useCallback(async () => {
    const result = await api().GET('/providers/adapters');
    return result.data ?? [];
  }, []);
  return useReference('adapters', loader);
}

/**
 * Каталог шаблонов без текстов.
 *
 * Берётся одной страницей по потолку контракта: `total` у каталога намеренно приблизителен (у него
 * нет дешёвого счётчика), поэтому листать «до конца» по нему нельзя. Полная страница означает, что
 * шаблонов больше потолка, — экран говорит об этом вместо молчаливого обрезания.
 */
export const TEMPLATE_PAGE_LIMIT = 500;

export function useTemplates(): Reference<TemplateSummary> & { readonly truncated: boolean } {
  const loader = useCallback(async () => {
    const result = await api().GET('/templates', {
      params: { query: { catalogStatus: 'ACTIVE', limit: TEMPLATE_PAGE_LIMIT, offset: 0 } },
    });
    return result.data?.items ?? [];
  }, []);
  const reference = useReference('templates', loader);
  return { ...reference, truncated: reference.items.length >= TEMPLATE_PAGE_LIMIT };
}
