import {
  App as AntdApp,
  Alert,
  Button,
  Card,
  Descriptions,
  Form,
  Select,
  Space,
  Tooltip,
  Typography,
  Upload,
} from 'antd';
import { QuestionCircleOutlined } from '@ant-design/icons';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';

import { api } from '../../api/client';
import type { components } from '../../api/generated/admin-schema';
import { useReasonPrompt } from '../../shared/components/ReasonPrompt';
import { readFileText } from '../../shared/download';
import { describeError } from '../../shared/errors';
import { CHANNELS, TRAFFIC_CLASSES, enumOptions } from '../../shared/labels';
import { reasonHeader } from '../../shared/reason';
import { SendEstimateModal } from './SendEstimateModal';
import { StreamSelect, TemplateLocaleSelect, TemplateSelect } from './ReferenceFields';

type SendEstimate = components['schemas']['SendEstimate'];
type SendBatchResult = components['schemas']['SendBatchResult'];
type Channel = components['schemas']['Channel'];

interface BulkSendForm {
  streamId: string;
  templateCode: string;
  locale: string;
  channel: Channel;
  trafficClass?: string;
}

/**
 * Рассылка по загруженному списку (ADR-0038, FR-1.6).
 *
 * Файл держит браузер и отправляет его дважды — на смету и на отправку; серверного хранилища
 * черновиков нет намеренно. Созданная рассылка — обычный батч: дальше за ней смотрят в разделе
 * «Рассылки», где её можно приостановить и остановить.
 */
export function BulkSendTab() {
  const { t } = useTranslation();
  const { message } = AntdApp.useApp();
  const navigate = useNavigate();
  const [form] = Form.useForm<BulkSendForm>();
  const channel = Form.useWatch('channel', form);
  const templateCode = Form.useWatch('templateCode', form);
  const { reasonModal, askReason } = useReasonPrompt();

  const [csv, setCsv] = useState<string | null>(null);
  const [fileName, setFileName] = useState('');
  const [estimate, setEstimate] = useState<SendEstimate | null>(null);
  const [estimating, setEstimating] = useState(false);
  const [sending, setSending] = useState(false);
  const [result, setResult] = useState<SendBatchResult | null>(null);

  const queryOf = (values: BulkSendForm) => ({
    streamId: values.streamId,
    templateCode: values.templateCode,
    locale: values.locale,
    channel: values.channel,
    trafficClass: values.trafficClass || undefined,
  });

  const runEstimate = async () => {
    const values = await form.validateFields().catch(() => null);
    if (!values || !csv) {
      if (!csv) {
        void message.warning(t('send.fileRequired'));
      }
      return;
    }
    setEstimating(true);
    try {
      const response = await api().POST('/send/estimate', {
        params: { query: queryOf(values) },
        body: csv as never,
        bodySerializer: (body: unknown) => body as string,
        headers: { 'Content-Type': 'text/csv' },
      });
      setEstimate(response.data ?? null);
    } catch (e) {
      void message.error(describeError(e, t));
    } finally {
      setEstimating(false);
    }
  };

  const confirmAndSend = async () => {
    const values = form.getFieldsValue();
    const reason = await askReason(t('send.confirm'));
    if (reason === null || !csv) {
      return;
    }
    setSending(true);
    try {
      const response = await api().POST('/send/batch', {
        params: { query: queryOf(values), header: reasonHeader(reason) },
        body: csv as never,
        bodySerializer: (body: unknown) => body as string,
        headers: { 'Content-Type': 'text/csv' },
      });
      setResult(response.data ?? null);
      setEstimate(null);
    } catch (e) {
      void message.error(describeError(e, t));
    } finally {
      setSending(false);
    }
  };

  return (
    <Space direction="vertical" size="large" style={{ width: '100%', maxWidth: 720 }}>
      <Alert type="info" showIcon message={t('send.bulkHint')} />
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
      </Form>

      {/* Зона загрузки живёт вне формы, поэтому подпись и подсказка ставятся руками. */}
      <Space direction="vertical" size={4} style={{ width: '100%' }}>
        <Space size={6}>
          <Typography.Text strong>{t('send.fileLabel')}</Typography.Text>
          <Tooltip title={t('send.fileHint')}>
            <QuestionCircleOutlined style={{ color: 'rgba(0,0,0,0.45)' }} />
          </Tooltip>
        </Space>
        <Upload.Dragger
          accept=".csv,text/csv"
          maxCount={1}
          showUploadList={false}
          beforeUpload={(file) => {
            void readFileText(file).then((text) => {
              setCsv(text);
              setFileName(file.name);
              setEstimate(null);
            });
            return false;
          }}
        >
          <p>{fileName || t('templates.dropCsv')}</p>
        </Upload.Dragger>
      </Space>

      <Button type="primary" loading={estimating} onClick={() => void runEstimate()}>
        {t('send.estimate')}
      </Button>

      {result && (
        <Card size="small" title={t('send.result')}>
          <Descriptions column={1} size="small" bordered>
            <Descriptions.Item label={t('batches.batchId')}>{result.batchId}</Descriptions.Item>
            <Descriptions.Item label={t('send.accepted')}>{result.accepted ?? 0}</Descriptions.Item>
            <Descriptions.Item label={t('send.duplicates')}>
              {result.duplicates ?? 0}
            </Descriptions.Item>
            <Descriptions.Item label={t('send.rejected')}>{result.rejected ?? 0}</Descriptions.Item>
          </Descriptions>
          <Button
            style={{ marginTop: 16 }}
            onClick={() => navigate(`/batches?batchId=${result.batchId}`)}
          >
            {t('send.openBatch')}
          </Button>
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
