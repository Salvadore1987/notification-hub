import { Form, Select } from 'antd';
import { useTranslation } from 'react-i18next';

import { enumOptions } from '../labels';

/**
 * Поле «При превышении» квоты (FR-2.6) — одно на все три формы: поток, канал, провайдер.
 *
 * Правило у поля ровно одно и оно несущее: **как только задан хотя бы один потолок, поведение
 * обязательно**. Значения решают, уходит ли сообщение клиенту, поэтому выбрать за оператора нельзя
 * ни одно из двух. Раньше пустое поле молча превращалось в `ALERT_ONLY` на бэкенде (D-11), то есть
 * квота считала и не блокировала, а форма потом показывала подставленное значение как выбранное.
 * Теперь бэкенд отвечает 400 с указателем на `quota.behavior`, а форма не даёт до него дойти.
 *
 * Список потолков передаётся формой: у потока их четыре, у провайдера два, у канала один.
 */
export function QuotaBehaviorItem({ ceilings }: { readonly ceilings: readonly string[] }) {
  const { t } = useTranslation();
  const paths = ceilings.map((field) => ['quota', field]);
  return (
    <Form.Item noStyle dependencies={paths}>
      {({ getFieldValue }) => {
        const required = ceilings.some((field) => {
          const value = getFieldValue(['quota', field]);
          return value !== undefined && value !== null && value !== '';
        });
        return (
          <Form.Item
            name={['quota', 'behavior']}
            label={t('streams.quotaBehavior')}
            tooltip={t('streams.quotaBehaviorHint')}
            rules={required ? [{ required: true, message: t('streams.quotaBehaviorRequired') }] : []}
          >
            <Select allowClear options={enumOptions(['BLOCK_AND_ALERT', 'ALERT_ONLY'])} />
          </Form.Item>
        );
      }}
    </Form.Item>
  );
}
