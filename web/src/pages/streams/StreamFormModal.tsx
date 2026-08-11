import { Alert, Checkbox, Divider, Form, Input, InputNumber, Modal, Select } from 'antd';
import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';

import type { components } from '../../api/generated/admin-schema';
import { identifierRule } from '../../shared/identifiers';
import { ProviderSelect } from '../providers/ProviderSelect';
import {
  BALANCING_STRATEGIES,
  CHANNELS,
  PRIORITIES,
  TRAFFIC_CLASSES,
  enumOptions,
} from '../../shared/labels';

type Stream = components['schemas']['Stream'];
export type StreamRequest = components['schemas']['StreamRequest'];

interface FormValues extends StreamRequest {
  streamId?: string;
}

/**
 * Одна форма на регистрацию и правку (контракт у них один — StreamRequest); отличает их
 * наличие исходного потока: у правки streamId выключен, PUT против POST решает экран.
 */
export function StreamFormModal({
  open,
  initial,
  onSubmit,
  onCancel,
}: {
  readonly open: boolean;
  readonly initial: Stream | null;
  readonly onSubmit: (streamId: string, request: StreamRequest) => Promise<boolean>;
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
        streamId: initial.streamId,
        name: initial.name,
        integrationType: initial.integrationType,
        defaults: initial.defaults,
        quota: initial.limits?.quota,
        quietHours: initial.limits?.quietHours,
        rateLimit: initial.limits?.rateLimit,
      });
    }
  }, [open, initial, form]);

  const submit = async () => {
    const values = await form.validateFields();
    const { streamId, ...request } = values;
    const ok = await onSubmit(streamId ?? initial?.streamId ?? '', request);
    if (ok) {
      form.resetFields();
    }
  };

  return (
    <Modal
      title={initial ? t('streams.edit') : t('streams.create')}
      open={open}
      onOk={() => void submit()}
      onCancel={onCancel}
      okText={t('common.save')}
      cancelText={t('common.cancel')}
      width={680}
    >
      <Form form={form} layout="vertical">
        <Form.Item
          name="streamId"
          label={t('streams.streamId')}
          rules={
            initial
              ? []
              : [{ required: true }, identifierRule('streamId', t('streams.streamIdFormat'))]
          }
          tooltip={t('streams.streamIdHint')}
        >
          <Input disabled={initial !== null} placeholder="mobile-app" />
        </Form.Item>
        <Form.Item
          name="name"
          label={t('streams.name')}
          tooltip={t('streams.nameHint')}
          rules={[{ required: true }]}
        >
          <Input />
        </Form.Item>
        <Form.Item
          name="integrationType"
          label={t('streams.integrationType')}
          tooltip={t('streams.integrationTypeHint')}
          rules={[{ required: true }]}
        >
          <Select options={enumOptions(['REST', 'KAFKA'])} />
        </Form.Item>
        <Form.Item
          name="credentialsRef"
          label={t('streams.credentialsRef')}
          tooltip={t('streams.credentialsRefHint')}
        >
          <Input placeholder="env:… / file:… / prop:…" />
        </Form.Item>

        <Divider plain>{t('streams.defaults')}</Divider>
        {/* Приоритет уровней виден только при наведении на «?», а его пропускает как раз тот,
            кто уверен, что уже всё это настроил на канале, — поэтому баннером, а не подсказкой. */}
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message={t('streams.defaultsOverrideHint')}
        />
        <Form.Item
          name={['defaults', 'channel']}
          label={t('dashboard.channel')}
          tooltip={t('streams.defaultChannelHint')}
        >
          <Select allowClear options={enumOptions(CHANNELS)} />
        </Form.Item>
        <Form.Item
          name={['defaults', 'provider']}
          label={t('dashboard.provider')}
          tooltip={t('streams.defaultProviderHint')}
        >
          <ProviderSelect allowClear />
        </Form.Item>
        <Form.Item
          name={['defaults', 'trafficClass']}
          label={t('streams.trafficClass')}
          tooltip={t('streams.trafficClassHint')}
        >
          <Select allowClear options={enumOptions(TRAFFIC_CLASSES)} />
        </Form.Item>
        <Form.Item
          name={['defaults', 'priority']}
          label={t('streams.priority')}
          tooltip={t('streams.priorityHint')}
        >
          <Select allowClear options={enumOptions(PRIORITIES)} />
        </Form.Item>
        <Form.Item
          name={['defaults', 'balancingStrategy']}
          label={t('providers.balancingStrategy')}
          tooltip={t('streams.defaultBalancingStrategyHint')}
        >
          <Select allowClear options={enumOptions(BALANCING_STRATEGIES)} />
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
          name={['quota', 'dailyCost']}
          label={t('streams.dailyCost')}
          tooltip={t('streams.dailyCostHint')}
        >
          <Input placeholder="1000000.0000 UZS" />
        </Form.Item>
        <Form.Item
          name={['quota', 'monthlyCost']}
          label={t('streams.monthlyCost')}
          tooltip={t('streams.monthlyCostHint')}
        >
          <Input placeholder="30000000.0000 UZS" />
        </Form.Item>
        <Form.Item
          name={['quota', 'behavior']}
          label={t('streams.quotaBehavior')}
          tooltip={t('streams.quotaBehaviorHint')}
        >
          <Select allowClear options={enumOptions(['BLOCK_AND_ALERT', 'ALERT_ONLY'])} />
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

        <Divider plain>{t('streams.quietHours')}</Divider>
        {/* Свой баннер, а не один общий сверху: блок в самом низу длинной модалки — верхний
            к этому месту уже уехал бы за экран. */}
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message={t('streams.quietHoursOverrideHint')}
        />
        <Form.Item
          name={['quietHours', 'start']}
          label={t('streams.quietStart')}
          tooltip={t('streams.quietStartHint')}
        >
          <Input placeholder="21:00" />
        </Form.Item>
        <Form.Item
          name={['quietHours', 'end']}
          label={t('streams.quietEnd')}
          tooltip={t('streams.quietEndHint')}
        >
          <Input placeholder="09:00" />
        </Form.Item>
        <Form.Item
          name={['quietHours', 'zone']}
          label={t('streams.quietZone')}
          tooltip={t('streams.quietZoneHint')}
        >
          <Input placeholder="Asia/Tashkent" />
        </Form.Item>
        <Form.Item
          name={['quietHours', 'behavior']}
          label={t('streams.quietBehavior')}
          tooltip={t('streams.quietBehaviorHint')}
        >
          <Select allowClear options={enumOptions(['DEFER', 'REJECT'])} />
        </Form.Item>
        {initial && (
          <Form.Item
            name="clearQuietHours"
            valuePropName="checked"
            tooltip={t('streams.clearQuietHoursHint')}
          >
            <Checkbox>{t('streams.clearQuietHours')}</Checkbox>
          </Form.Item>
        )}
      </Form>
    </Modal>
  );
}
