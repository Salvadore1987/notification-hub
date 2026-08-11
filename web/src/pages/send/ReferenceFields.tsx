import { Input, Select } from 'antd';
import type { CSSProperties } from 'react';
import { useTranslation } from 'react-i18next';

import type { components } from '../../api/generated/admin-schema';
import { useStreams, useTemplates } from '../../shared/useReference';

type Channel = components['schemas']['Channel'];

/**
 * Поля-ссылки формы отправки: поток и шаблон выбираются из списка, а не набираются.
 *
 * Обе вкладки просят одно и то же, поэтому поля живут здесь, а не дублируются. Каждое из них
 * **деградирует в обычный ввод**, если справочник не пришёл: у оператора, который знает код,
 * должна остаться возможность отправить (см. `useReference`).
 *
 * Управляемые компоненты: `value`/`onChange` приходят от `Form.Item`, как этого ждёт antd.
 */

/** Выбор потока. Приостановленные показываются помеченными, а не прячутся. */
export function StreamSelect({
  value,
  onChange,
  ariaLabel,
  id,
  allowClear,
  style,
}: {
  readonly value?: string;
  readonly onChange?: (value: string) => void;
  /** Только для форм без подписи: там, где Form.Item метку рисует, она уже связана с полем. */
  readonly ariaLabel?: string;
  /** Приходит от Form.Item: без него подпись формы указывает в пустоту. */
  readonly id?: string;
  readonly allowClear?: boolean;
  readonly style?: CSSProperties;
}) {
  const { t } = useTranslation();
  const { items, loading, failed } = useStreams();

  if (failed) {
    return (
      <Input
        id={id}
        value={value}
        onChange={(e) => onChange?.(e.target.value)}
        aria-label={ariaLabel}
        style={style}
      />
    );
  }
  return (
    <Select
      showSearch
      allowClear={allowClear}
      style={style}
      loading={loading}
      id={id}
      value={value}
      onChange={onChange}
      aria-label={ariaLabel}
      optionFilterProp="label"
      placeholder={t('send.streamPlaceholder')}
      options={items.map((stream) => ({
        value: stream.streamId ?? '',
        // Приостановленный поток не прячем: иначе «моего потока нет в списке» становится загадкой.
        label: `${stream.streamId}${stream.name ? ` — ${stream.name}` : ''}`,
        disabled: stream.status !== 'ACTIVE',
      }))}
    />
  );
}

/** Выбор шаблона, сужённый до канала отправки. */
export function TemplateSelect({
  value,
  onChange,
  channel,
  ariaLabel,
  id,
}: {
  readonly value?: string;
  readonly onChange?: (value: string) => void;
  readonly channel?: Channel;
  /** Только для форм без подписи: там, где Form.Item метку рисует, она уже связана с полем. */
  readonly ariaLabel?: string;
  /** Приходит от Form.Item: без него подпись формы указывает в пустоту. */
  readonly id?: string;
}) {
  const { t } = useTranslation();
  const { items, loading, failed, truncated } = useTemplates();

  if (failed) {
    return (
      <Input
        id={id}
        value={value}
        onChange={(e) => onChange?.(e.target.value)}
        aria-label={ariaLabel}
        placeholder="OTP_LOGIN"
      />
    );
  }
  const forChannel = items.filter((template) => !channel || template.channel === channel);
  return (
    <Select
      showSearch
      loading={loading}
      id={id}
      value={value}
      onChange={onChange}
      aria-label={ariaLabel}
      optionFilterProp="label"
      placeholder={t('send.templatePlaceholder')}
      notFoundContent={truncated ? t('send.templatesTruncated') : undefined}
      options={forChannel.map((template) => ({
        value: template.code ?? '',
        label: `${template.code}${template.direction ? ` — ${template.direction}` : ''}`,
      }))}
    />
  );
}

/**
 * Языки шаблона: только те, в которых у выбранного шаблона есть публикация (FR-4.2).
 *
 * Пока шаблон не выбран — все три, как было. Это снимает отказ сметы «нет опубликованной версии»
 * до того, как оператор его увидит.
 */
export function TemplateLocaleSelect({
  value,
  onChange,
  templateCode,
  ariaLabel,
  id,
}: {
  readonly value?: string;
  readonly onChange?: (value: string) => void;
  readonly templateCode?: string;
  /** Только для форм без подписи: там, где Form.Item метку рисует, она уже связана с полем. */
  readonly ariaLabel?: string;
  /** Приходит от Form.Item: без него подпись формы указывает в пустоту. */
  readonly id?: string;
}) {
  const { items, failed } = useTemplates();
  const selected = items.find((template) => template.code === templateCode);
  const locales = failed || !selected ? ['RU', 'UZ', 'EN'] : (selected.publishedLocales ?? []);
  return (
    <Select
      id={id}
      value={value}
      onChange={onChange}
      aria-label={ariaLabel}
      options={locales.map((locale) => ({ value: locale, label: locale }))}
    />
  );
}
