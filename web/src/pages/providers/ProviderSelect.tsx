import { Input, Select } from 'antd';
import { useTranslation } from 'react-i18next';

import type { components } from '../../api/generated/admin-schema';
import { useProviders } from '../../shared/useReference';

type Channel = components['schemas']['Channel'];

/**
 * Выбор провайдера по коду — вместо ввода кода руками.
 *
 * Живёт здесь, а не в `shared/`, по правилу AR-04, перенесённому на фронт: словарь провайдеров
 * принадлежит разделу провайдеров, а карточка шаблона и форма потока им пользуются.
 *
 * Как и справочники отправки, **деградирует в ввод**, если список не пришёл: экран не должен
 * закрываться из-за того, что не показался список.
 */
export function ProviderSelect({
  value,
  onChange,
  channel,
  ariaLabel,
  id,
  allowClear,
}: {
  readonly value?: string;
  readonly onChange?: (value: string) => void;
  readonly channel?: Channel;
  /** Только для форм без подписи: там, где Form.Item метку рисует, она уже связана с полем. */
  readonly ariaLabel?: string;
  /** Приходит от Form.Item: без него подпись формы указывает в пустоту. */
  readonly id?: string;
  readonly allowClear?: boolean;
}) {
  const { t } = useTranslation();
  const { items, loading, failed } = useProviders();

  if (failed) {
    return (
      <Input
        id={id}
        value={value}
        onChange={(e) => onChange?.(e.target.value)}
        aria-label={ariaLabel}
      />
    );
  }
  return (
    <Select
      showSearch
      allowClear={allowClear}
      loading={loading}
      id={id}
      value={value}
      onChange={onChange}
      aria-label={ariaLabel}
      optionFilterProp="label"
      placeholder={t('providers.selectPlaceholder')}
      options={items
        .filter((provider) => !channel || provider.channel === channel)
        .map((provider) => ({
          value: provider.code ?? '',
          // Невыбираемый провайдер показывается: «почему его нет в списке» — вопрос дороже строки.
          label: `${provider.code}${provider.state?.selectable === false ? ' · ' + provider.state?.health : ''}`,
        }))}
    />
  );
}

/**
 * Порядок провайдеров — тот же справочник, но списком с сохранением порядка выбора.
 *
 * `mode="multiple"`, а не `tags`: набор кодов конечен, и опечатка здесь означает канал, который
 * молча некуда переключить (FR-6.3).
 */
export function ProviderOrderSelect({
  value,
  onChange,
  channel,
  ariaLabel,
  id,
}: {
  readonly value?: string[];
  readonly onChange?: (value: string[]) => void;
  readonly channel?: Channel;
  /** Только для форм без подписи: там, где Form.Item метку рисует, она уже связана с полем. */
  readonly ariaLabel?: string;
  /** Приходит от Form.Item: без него подпись формы указывает в пустоту. */
  readonly id?: string;
}) {
  const { items, loading, failed } = useProviders();

  if (failed) {
    return (
      <Select
        id={id}
        mode="tags"
        open={false}
        suffixIcon={null}
        tokenSeparators={[',', ' ']}
        value={value}
        onChange={onChange}
        aria-label={ariaLabel}
      />
    );
  }
  return (
    <Select
      mode="multiple"
      loading={loading}
      id={id}
      value={value}
      onChange={onChange}
      aria-label={ariaLabel}
      optionFilterProp="label"
      options={items
        .filter((provider) => !channel || provider.channel === channel)
        .map((provider) => ({ value: provider.code ?? '', label: provider.code ?? '' }))}
    />
  );
}
