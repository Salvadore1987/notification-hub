import { Button, Divider, Form, Input, InputNumber, Modal, Select, Space } from 'antd';
import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';

import type { components } from '../../api/generated/admin-schema';
import { CHANNELS, enumOptions } from '../../shared/labels';

type Provider = components['schemas']['Provider'];
export type ProviderRequest = components['schemas']['ProviderRequest'];

interface FormValues extends Omit<ProviderRequest, 'endpointConfig'> {
  code?: string;
  endpointEntries?: { key?: string; value?: string }[];
}

/**
 * endpointConfig — произвольные ключ-значение (originator, priorities, ttl…); форма ведёт их
 * списком пар, потому что набор ключей знает адаптер провайдера, а не панель.
 */
export function ProviderFormModal({
  open,
  initial,
  onSubmit,
  onCancel,
}: {
  readonly open: boolean;
  readonly initial: Provider | null;
  readonly onSubmit: (code: string, request: ProviderRequest) => Promise<boolean>;
  readonly onCancel: () => void;
}) {
  const { t } = useTranslation();
  const [form] = Form.useForm<FormValues>();

  useEffect(() => {
    if (!open) {
      return;
    }
    form.resetFields();
    if (initial) {
      form.setFieldsValue({
        code: initial.code,
        channel: initial.channel,
        adapterType: initial.adapterType,
        weight: initial.weight,
        tariff: initial.tariff,
        rateLimit: initial.rateLimit,
        quota: initial.state?.quota,
        credentialsRef: initial.state?.credentialsRef,
        endpointEntries: Object.entries(initial.state?.endpointConfig ?? {}).map(
          ([key, value]) => ({ key, value }),
        ),
      });
    }
  }, [open, initial, form]);

  const submit = async () => {
    const values = await form.validateFields();
    const { code, endpointEntries, ...request } = values;
    const endpointConfig = Object.fromEntries(
      (endpointEntries ?? [])
        .filter((entry) => entry.key?.trim())
        .map((entry) => [entry.key?.trim() ?? '', entry.value ?? '']),
    );
    const ok = await onSubmit(code ?? initial?.code ?? '', {
      ...request,
      endpointConfig: Object.keys(endpointConfig).length > 0 ? endpointConfig : undefined,
    });
    if (ok) {
      form.resetFields();
    }
  };

  return (
    <Modal
      title={initial ? t('providers.edit') : t('providers.create')}
      open={open}
      onOk={() => void submit()}
      onCancel={onCancel}
      okText={t('common.save')}
      cancelText={t('common.cancel')}
      width={680}
    >
      <Form form={form} layout="vertical">
        <Form.Item
          name="code"
          label={t('providers.code')}
          rules={initial ? [] : [{ required: true }]}

          tooltip={t('providers.codeHint')}
        >
          <Input disabled={initial !== null} />
        </Form.Item>
        <Form.Item
          name="channel"
          label={t('dashboard.channel')}
          rules={[{ required: true }]}
          tooltip={t('providers.channelHint')}
        >
          <Select options={enumOptions(CHANNELS)} />
        </Form.Item>
        <Form.Item
          name="adapterType"
          label={t('providers.adapterType')}
          rules={[{ required: true }]}
          tooltip={t('providers.adapterTypeHint')}
        >
          <Input placeholder="PLAYMOBILE / SMS_GATE / SMTP / FCM / APNS" />
        </Form.Item>
        <Form.Item name="weight" label={t('providers.weight')} tooltip={t('providers.weightHint')}>
          <InputNumber min={0} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item
          name="credentialsRef"
          label={t('streams.credentialsRef')}
          tooltip={t('streams.credentialsRefHint')}
        >
          <Input placeholder="env:… / file:… / prop:…" />
        </Form.Item>

        <Divider plain>{t('providers.tariff')}</Divider>
        <Form.Item
          name={['tariff', 'perMessage']}
          label={t('providers.perMessage')}
          tooltip={t('providers.perMessageHint')}
        >
          <Input placeholder="12.5000 UZS" />
        </Form.Item>
        <Form.Item
          name={['tariff', 'perSegment']}
          label={t('providers.perSegment')}
          tooltip={t('providers.perSegmentHint')}
        >
          <Input placeholder="12.5000 UZS" />
        </Form.Item>

        <Divider plain>{t('streams.rateLimit')}</Divider>
        <Form.Item name={['rateLimit', 'tps']} label="TPS" tooltip={t('streams.tpsHint')}>
          <InputNumber min={0} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item
          name={['rateLimit', 'perMinute']}
          label={t('streams.perMinute')}
          tooltip={t('streams.perMinuteHint')}
        >
          <InputNumber min={0} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item
          name={['rateLimit', 'perRecipientPerHour']}
          label={t('streams.perRecipientPerHour')}

          tooltip={t('streams.perRecipientPerHourHint')}
        >
          <InputNumber min={0} style={{ width: '100%' }} />
        </Form.Item>

        <Divider plain>{t('streams.quota')}</Divider>
        <Form.Item
          name={['quota', 'dailyCount']}
          label={t('streams.dailyCount')}
          tooltip={t('streams.dailyCountHint')}
        >
          <InputNumber min={0} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item
          name={['quota', 'monthlyCount']}
          label={t('streams.monthlyCount')}
          tooltip={t('streams.monthlyCountHint')}
        >
          <InputNumber min={0} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item
          name={['quota', 'behavior']}
          label={t('streams.quotaBehavior')}
          tooltip={t('streams.quotaBehaviorHint')}
        >
          <Select allowClear options={enumOptions(['BLOCK', 'ALERT_ONLY'])} />
        </Form.Item>

        <Divider plain>{t('providers.endpointConfig')}</Divider>
        <Form.List name="endpointEntries">
          {(fields, { add, remove }) => (
            <Space direction="vertical" style={{ width: '100%' }}>
              {fields.map((field) => (
                <Space key={field.key} align="baseline" style={{ display: 'flex' }}>
                  <Form.Item name={[field.name, 'key']} noStyle>
                    <Input placeholder={t('providers.configKey')} style={{ width: 220 }} />
                  </Form.Item>
                  <Form.Item name={[field.name, 'value']} noStyle>
                    <Input placeholder={t('providers.configValue')} style={{ width: 280 }} />
                  </Form.Item>
                  <Button type="link" danger onClick={() => remove(field.name)}>
                    {t('common.remove')}
                  </Button>
                </Space>
              ))}
              <Button onClick={() => add({})}>{t('providers.addConfigEntry')}</Button>
            </Space>
          )}
        </Form.List>
      </Form>
    </Modal>
  );
}
