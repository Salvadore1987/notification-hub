import { Input, Select } from 'antd';
import { useTranslation } from 'react-i18next';

import type { components } from '../../api/generated/admin-schema';
import { useAdapters } from '../../shared/useReference';

type Channel = components['schemas']['Channel'];

/**
 * Выбор типа адаптера — вместо ввода непрозрачной строки руками.
 *
 * `AdapterType` в домене намеренно строка, а не перечисление: добавление провайдера — это новый бин
 * и ничего больше (AR-04). Но набрать её руками оператору негде: опечатку не ловит ни контракт, ни
 * база, и профиль ломается только при первой отправке. Поэтому список приходит с контура — ровно те
 * бины, среди которых отправка и будет искать адаптер.
 *
 * **Фильтруется каналом.** Адаптер сверяется и по каналу, и по типу, так что `smtp` на SMS-профиле —
 * гарантированно мёртвый провайдер, который никогда ничего не отправит.
 *
 * Как и остальные справочники, **деградирует в ввод** при отказе: запрещать регистрацию из-за
 * незагрузившегося списка было бы хуже самой проблемы.
 */
export function AdapterTypeSelect({
  value,
  onChange,
  channel,
  ariaLabel,
  id,
  disabled,
}: {
  readonly value?: string;
  readonly onChange?: (value: string) => void;
  readonly channel?: Channel;
  /** Только для форм без подписи: там, где Form.Item метку рисует, она уже связана с полем. */
  readonly ariaLabel?: string;
  /** Приходит от Form.Item: без него подпись формы указывает в пустоту. */
  readonly id?: string;
  readonly disabled?: boolean;
}) {
  const { t } = useTranslation();
  const { items, loading, failed } = useAdapters();

  if (failed) {
    return (
      <Input
        id={id}
        value={value}
        disabled={disabled}
        onChange={(e) => onChange?.(e.target.value)}
        aria-label={ariaLabel}
        placeholder="playmobile-http"
      />
    );
  }

  const options = items
    .filter((adapter) => !channel || adapter.channel === channel)
    .map((adapter) => ({ value: adapter.adapterType ?? '', label: adapter.adapterType ?? '' }));
  // Профиль мог быть заведён под адаптер, которого на этом контуре нет: поле показывает, что в базе,
  // а не пустоту.
  if (value && !options.some((option) => option.value === value)) {
    options.push({ value, label: value });
  }

  return (
    <Select
      showSearch
      loading={loading}
      id={id}
      value={value}
      disabled={disabled}
      onChange={onChange}
      aria-label={ariaLabel}
      optionFilterProp="label"
      options={options}
      notFoundContent={t('providers.adapterTypeEmpty')}
    />
  );
}
