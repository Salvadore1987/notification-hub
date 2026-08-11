import { App as AntdApp, Alert, Button, Card, Form, Input, Select, Space, Tag } from 'antd';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import { api } from '../../api/client';
import type { components } from '../../api/generated/admin-schema';
import { useReasonPrompt } from '../../shared/components/ReasonPrompt';
import { describeError } from '../../shared/errors';
import { CHANNELS, TRAFFIC_CLASSES, enumOptions, messageStatusColor } from '../../shared/labels';
import { maskAddress } from '../../shared/masking';
import { reasonHeader } from '../../shared/reason';
import { SendEstimateModal } from './SendEstimateModal';
import { StreamSelect, TemplateLocaleSelect, TemplateSelect } from './ReferenceFields';
import { VariablesField, type VariableRow } from './VariablesField';
import { variablesOf } from './variables';

type SendEstimate = components['schemas']['SendEstimate'];
type MessageAccepted = components['schemas']['MessageAccepted'];
type Channel = components['schemas']['Channel'];

interface SingleSendForm {
  streamId: string;
  templateCode: string;
  locale: string;
  channel: Channel;
  trafficClass?: string;
  msisdn?: string;
  email?: string;
  pushToken?: string;
  clientId?: string;
  variables?: VariableRow[];
}

/**
 * Одно сообщение по опубликованному шаблону (ADR-0038).
 *
 * Поля текста здесь нет и быть не может: контент берёт шаблон, который уже прошёл ревью
 * второго человека (FR-4.2). Кнопка отправки закрыта сметой — пока её не получили для
 * текущего состояния формы, отправлять нечего.
 */
export function SingleSendTab() {
  const { t } = useTranslation();
  const { message } = AntdApp.useApp();
  const [form] = Form.useForm<SingleSendForm>();
  const channel = Form.useWatch('channel', form);
  const templateCode = Form.useWatch('templateCode', form);
  const { reasonModal, askReason } = useReasonPrompt();

  const [estimate, setEstimate] = useState<SendEstimate | null>(null);
  const [estimating, setEstimating] = useState(false);
  const [sending, setSending] = useState(false);
  const [accepted, setAccepted] = useState<MessageAccepted | null>(null);
  const [sentTo, setSentTo] = useState('');

  const bodyOf = (values: SingleSendForm) => ({
    streamId: values.streamId,
    templateCode: values.templateCode,
    locale: values.locale,
    channel: values.channel,
    trafficClass: values.trafficClass || undefined,
    recipient: {
      clientId: values.clientId || undefined,
      msisdn: values.msisdn || undefined,
      email: values.email || undefined,
      pushToken: values.pushToken || undefined,
    },
    variables: variablesOf(values.variables),
  });

  const runEstimate = async () => {
    const values = await form.validateFields().catch(() => null);
    if (!values) {
      return;
    }
    setEstimating(true);
    try {
      const result = await api().POST('/send/estimate', { body: bodyOf(values) });
      setEstimate(result.data ?? null);
    } catch (e) {
      void message.error(describeError(e, t));
    } finally {
      setEstimating(false);
    }
  };

  const confirmAndSend = async () => {
    const values = form.getFieldsValue();
    const reason = await askReason(t('send.confirm'));
    if (reason === null) {
      return;
    }
    setSending(true);
    try {
      const result = await api().POST('/send/message', {
        body: bodyOf(values),
        params: { header: reasonHeader(reason) },
      });
      setAccepted(result.data ?? null);
      // Адрес набрал оператор — в подтверждении он маскируется (DB-04).
      setSentTo(maskAddress(values.msisdn || values.email || values.pushToken));
      setEstimate(null);
    } catch (e) {
      void message.error(describeError(e, t));
    } finally {
      setSending(false);
    }
  };

  return (
    <Space direction="vertical" size="large" style={{ width: '100%', maxWidth: 720 }}>
      <Alert type="info" showIcon message={t('send.singleHint')} />
      <Form
        form={form}
        layout="vertical"
        initialValues={{ channel: 'SMS', locale: 'RU' }}
        onValuesChange={() => setEstimate(null)}
      >
        <Form.Item
          name="streamId"
          label={t('batches.streamId')}
          tooltip={t('batches.streamIdHint')}
          rules={[{ required: true }]}
        >
          <StreamSelect />
        </Form.Item>
        <Form.Item
          name="templateCode"
          label={t('templates.code')}
          tooltip={t('templates.codeHint')}
          rules={[{ required: true }]}
        >
          <TemplateSelect channel={channel} />
        </Form.Item>
        <Form.Item
          name="locale"
          label={t('templates.localeField')}
          tooltip={t('templates.localeFieldHint')}
          rules={[{ required: true }]}
        >
          <TemplateLocaleSelect templateCode={templateCode} />
        </Form.Item>
        <Form.Item
          name="channel"
          label={t('dashboard.channel')}
          tooltip={t('send.channelHint')}
          rules={[{ required: true }]}
        >
          <Select options={enumOptions(CHANNELS)} aria-label={t('dashboard.channel')} />
        </Form.Item>
        <Form.Item
          name="trafficClass"
          label={t('streams.trafficClass')}
          tooltip={t('send.trafficClassHint')}
        >
          <Select
            allowClear
            options={enumOptions(TRAFFIC_CLASSES)}
            aria-label={t('streams.trafficClass')}
          />
        </Form.Item>
        {channel === 'SMS' && (
          <Form.Item
            name="msisdn"
            label="MSISDN"
            tooltip={t('send.msisdnHint')}
            rules={[{ required: true }]}
          >
            <Input placeholder="998901234567" />
          </Form.Item>
        )}
        {channel === 'EMAIL' && (
          <Form.Item
            name="email"
            label="Email"
            tooltip={t('send.emailHint')}
            rules={[{ required: true }]}
          >
            <Input type="email" />
          </Form.Item>
        )}
        {channel === 'PUSH' && (
          <Form.Item
            name="pushToken"
            label={t('providers.pushToken')}
            tooltip={t('send.pushTokenHint')}
            rules={[{ required: true }]}
          >
            <Input />
          </Form.Item>
        )}
        <Form.Item
          name="clientId"
          label={t('suppressions.clientId')}
          tooltip={t('send.clientIdHint')}
        >
          <Input />
        </Form.Item>
        <VariablesField />
        <Button type="primary" loading={estimating} onClick={() => void runEstimate()}>
          {t('send.estimate')}
        </Button>
      </Form>

      {accepted && (
        <Card size="small" title={t('send.result')}>
          <Space direction="vertical">
            <span>
              {t('messages.messageId')}: {accepted.messageId}
            </span>
            <span>
              {t('messages.status')}:{' '}
              <Tag color={messageStatusColor(accepted.status)}>{accepted.status}</Tag>
            </span>
            <span>
              {t('providers.sentTo')}: {sentTo}
            </span>
          </Space>
        </Card>
      )}

      <SendEstimateModal
        estimate={estimate}
        open={estimate !== null}
        sending={sending}
        onConfirm={() => void confirmAndSend()}
        onCancel={() => setEstimate(null)}
      />
      {reasonModal}
    </Space>
  );
}
