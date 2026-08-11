import { App as AntdApp, Alert, Button, Card, Form, Input, Select, Space, Tag } from 'antd';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import { api } from '../../api/client';
import type { components } from '../../api/generated/admin-schema';
import { describeError } from '../../shared/errors';
import { CHANNELS, enumOptions, messageStatusColor, type Channel } from '../../shared/labels';
import { maskAddress } from '../../shared/masking';

type MessageAccepted = components['schemas']['MessageAccepted'];

interface TestSendForm {
  streamId: string;
  channel: Channel;
  provider?: string;
  clientId?: string;
  msisdn?: string;
  email?: string;
  pushToken?: string;
  pushPlatform?: 'APNS' | 'FCM';
  subject?: string;
  text?: string;
}

/**
 * FR-7.4: тестовая отправка идёт по обычному конвейеру — квоты, фильтры, креды провайдера, —
 * потому что проверяют именно его. Метка TEST остаётся измерением в метриках и витрине.
 */
export function TestSendTab() {
  const { t } = useTranslation();
  const { message } = AntdApp.useApp();
  const [form] = Form.useForm<TestSendForm>();
  const channel = Form.useWatch('channel', form);

  const [sending, setSending] = useState(false);
  const [accepted, setAccepted] = useState<MessageAccepted | null>(null);
  const [sentTo, setSentTo] = useState('');

  const send = async () => {
    // Незаполненную форму подсвечивает сама Form; наружу это не ошибка отправки, а её отсутствие.
    const values = await form.validateFields().catch(() => null);
    if (!values) {
      return;
    }
    setSending(true);
    try {
      const result = await api().POST('/providers/test-send', {
        body: {
          streamId: values.streamId,
          channel: values.channel,
          provider: values.provider || undefined,
          recipient: {
            clientId: values.clientId || undefined,
            msisdn: values.msisdn || undefined,
            email: values.email || undefined,
            pushToken: values.pushToken || undefined,
            pushPlatform: values.pushPlatform,
          },
          subject: values.subject || undefined,
          text: values.text || undefined,
        },
      });
      setAccepted(result.data ?? null);
      // В подтверждении адрес маскируется на клиенте — его набрал оператор (DB-04).
      setSentTo(maskAddress(values.msisdn || values.email || values.pushToken));
    } catch (e) {
      setAccepted(null);
      void message.error(describeError(e, t));
    } finally {
      setSending(false);
    }
  };

  return (
    <Space direction="vertical" size="large" style={{ width: '100%', maxWidth: 640 }}>
      <Alert type="info" showIcon message={t('providers.testSendHint')} />
      <Form form={form} layout="vertical" initialValues={{ channel: 'SMS' }}>
        <Form.Item
          name="streamId"
          label={t('batches.streamId')}
          rules={[{ required: true }]}
          tooltip={t('providers.testStreamIdHint')}
        >
          <Input />
        </Form.Item>
        <Form.Item
          name="channel"
          label={t('dashboard.channel')}
          rules={[{ required: true }]}
          tooltip={t('providers.testChannelHint')}
        >
          <Select options={enumOptions(CHANNELS)} />
        </Form.Item>
        <Form.Item
          name="provider"
          label={t('dashboard.provider')}
          tooltip={t('providers.pinProviderHint')}
        >
          <Input />
        </Form.Item>
        {channel === 'SMS' && (
          <Form.Item
            name="msisdn"
            label="MSISDN"
            rules={[{ required: true }]}
            tooltip={t('send.msisdnHint')}
          >
            <Input placeholder="998901234567" />
          </Form.Item>
        )}
        {channel === 'EMAIL' && (
          <>
            <Form.Item
              name="email"
              label="Email"
              rules={[{ required: true }]}
              tooltip={t('send.emailHint')}
            >
              <Input type="email" />
            </Form.Item>
            <Form.Item
              name="subject"
              label={t('providers.subject')}
              tooltip={t('providers.subjectHint')}
            >
              <Input />
            </Form.Item>
          </>
        )}
        {channel === 'PUSH' && (
          <>
            <Form.Item
              name="pushToken"
              label={t('providers.pushToken')}
              rules={[{ required: true }]}

              tooltip={t('send.pushTokenHint')}
            >
              <Input />
            </Form.Item>
            <Form.Item
              name="pushPlatform"
              label={t('providers.pushPlatform')}
              tooltip={t('providers.pushPlatformHint')}
            >
              <Select allowClear options={enumOptions(['APNS', 'FCM'])} />
            </Form.Item>
            <Form.Item
              name="subject"
              label={t('providers.subject')}
              tooltip={t('providers.subjectHint')}
            >
              <Input />
            </Form.Item>
          </>
        )}
        <Form.Item
          name="clientId"
          label={t('suppressions.clientId')}
          tooltip={t('send.clientIdHint')}
        >
          <Input />
        </Form.Item>
        <Form.Item name="text" label={t('providers.text')} tooltip={t('providers.testTextHint')}>
          <Input.TextArea rows={3} />
        </Form.Item>
        <Button type="primary" loading={sending} onClick={() => void send()}>
          {t('providers.send')}
        </Button>
      </Form>
      {accepted && (
        <Card size="small" title={t('providers.testSendResult')}>
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
    </Space>
  );
}
