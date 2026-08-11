import {
  App as AntdApp,
  Alert,
  Button,
  Card,
  DatePicker,
  Descriptions,
  Form,
  Input,
  Modal,
  Popconfirm,
  Select,
  Space,
  Tag,
  Tooltip,
  Upload,
} from 'antd';
import type { Dayjs } from 'dayjs';
import { useCallback, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { api } from '../../api/client';
import type { components } from '../../api/generated/admin-schema';
import { useReasonPrompt } from '../../shared/components/ReasonPrompt';
import { ServerTable, type PageQuery } from '../../shared/components/ServerTable';
import { readFileText } from '../../shared/download';
import { describeError } from '../../shared/errors';
import { CHANNELS, SUPPRESSION_REASONS, enumOptions, type Channel } from '../../shared/labels';
import { maskAddress } from '../../shared/masking';
import { reasonHeader } from '../../shared/reason';
import { formatDateTime, toApiInstant } from '../../shared/time';

type Suppression = components['schemas']['Suppression'];
type SuppressionReason = components['schemas']['SuppressionReason'];
type SuppressionCheck = components['schemas']['SuppressionCheck'];
type ImportResult = components['schemas']['ImportResult'];

interface AddForm {
  channel?: Channel;
  address?: string;
  clientId?: string;
  reason: SuppressionReason;
  validUntil?: Dayjs;
}

interface CheckForm {
  channel: Channel;
  address?: string;
  clientId?: string;
}

export function SuppressionsPage() {
  const { t } = useTranslation();
  const { message } = AntdApp.useApp();
  const { reasonModal, askReason } = useReasonPrompt();
  const [addForm] = Form.useForm<AddForm>();
  const [checkForm] = Form.useForm<CheckForm>();

  const [channel, setChannel] = useState<Channel | undefined>();
  const [reason, setReason] = useState<SuppressionReason | undefined>();
  const [clientId, setClientId] = useState('');
  const [refreshToken, setRefreshToken] = useState(0);
  const [addOpen, setAddOpen] = useState(false);
  const [importOpen, setImportOpen] = useState(false);
  const [importing, setImporting] = useState(false);
  const [importResult, setImportResult] = useState<ImportResult | null>(null);
  const [check, setCheck] = useState<SuppressionCheck | null>(null);
  const [checkedFor, setCheckedFor] = useState('');

  const fetchPage = useCallback(
    async (query: PageQuery) => {
      const result = await api().GET('/suppressions', {
        params: {
          query: {
            channel,
            reason,
            clientId: clientId || undefined,
            limit: query.limit,
            offset: query.offset,
          },
        },
      });
      return { rows: result.data?.items ?? [], total: Number(result.data?.total ?? 0) };
    },
    [channel, reason, clientId],
  );

  const add = async () => {
    try {
      const values = await addForm.validateFields();
      await api().POST('/suppressions', {
        body: {
          channel: values.channel,
          address: values.address || undefined,
          clientId: values.clientId || undefined,
          reason: values.reason,
          validUntil: values.validUntil ? toApiInstant(values.validUntil) : undefined,
        },
      });
      void message.success(t('common.saved'));
      setAddOpen(false);
      addForm.resetFields();
      setRefreshToken((n) => n + 1);
    } catch (e) {
      void message.error(describeError(e, t));
    }
  };

  const remove = async (entry: Suppression) => {
    const reasonText = await askReason(t('suppressions.remove'));
    if (reasonText === null) {
      return;
    }
    try {
      await api().DELETE('/suppressions/{entryId}', {
        params: {
          path: { entryId: entry.entryId ?? '' },
          header: reasonHeader(reasonText),
        },
      });
      void message.success(t('common.done'));
      setRefreshToken((n) => n + 1);
    } catch (e) {
      void message.error(describeError(e, t));
    }
  };

  const runCheck = async () => {
    const values = await checkForm.validateFields();
    try {
      const result = await api().GET('/suppressions/check', {
        params: {
          query: {
            channel: values.channel,
            address: values.address || undefined,
            clientId: values.clientId || undefined,
          },
        },
      });
      setCheck(result.data ?? null);
      // Адрес набрал оператор — в результате он показывается маскированным (DB-04).
      setCheckedFor(values.address ? maskAddress(values.address) : (values.clientId ?? ''));
    } catch (e) {
      void message.error(describeError(e, t));
    }
  };

  const importCsv = async (file: Blob) => {
    setImporting(true);
    try {
      const csv = await readFileText(file);
      const result = await api().POST('/suppressions/import', {
        body: csv as never,
        bodySerializer: (body: unknown) => body as string,
        headers: { 'Content-Type': 'text/csv' },
      });
      setImportResult(result.data ?? null);
      setRefreshToken((n) => n + 1);
    } catch (e) {
      void message.error(describeError(e, t));
    } finally {
      setImporting(false);
    }
  };

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Card size="small" title={t('suppressions.check')}>
        {/* Подписей у полей нет (inline-форма), поэтому иконка у метки не рисуется —
            подсказка висит на самом контроле и всплывает по наведению на него. */}
        <Form form={checkForm} layout="inline" initialValues={{ channel: 'SMS' }}>
          <Form.Item name="channel" rules={[{ required: true }]}>
            <Tooltip title={t('suppressions.checkChannelHint')}>
              <Select
                options={enumOptions(CHANNELS)}
                aria-label={t('dashboard.channel')}
                style={{ width: 110 }}
              />
            </Tooltip>
          </Form.Item>
          <Form.Item name="address">
            <Tooltip title={t('suppressions.checkAddressHint')}>
              <Input placeholder={t('suppressions.address')} style={{ width: 220 }} />
            </Tooltip>
          </Form.Item>
          <Form.Item name="clientId">
            <Tooltip title={t('suppressions.checkClientIdHint')}>
              <Input placeholder={t('suppressions.clientId')} style={{ width: 160 }} />
            </Tooltip>
          </Form.Item>
          <Button type="primary" onClick={() => void runCheck()}>
            {t('suppressions.runCheck')}
          </Button>
        </Form>
        {check && (
          <Alert
            style={{ marginTop: 16 }}
            type={check.suppressed ? 'warning' : 'success'}
            showIcon
            message={t(check.suppressed ? 'suppressions.blocked' : 'suppressions.allowed', {
              subject: checkedFor,
            })}
            description={
              check.suppressed && check.entry && 'reason' in check.entry ? (
                <Space>
                  <Tag>{check.entry.reason}</Tag>
                  {check.entry.validUntil
                    ? t('suppressions.until', { date: formatDateTime(check.entry.validUntil) })
                    : t('suppressions.permanent')}
                </Space>
              ) : undefined
            }
          />
        )}
      </Card>

      <Space wrap>
        <Select
          allowClear
          placeholder={t('dashboard.channel')}
          aria-label={t('dashboard.channel')}
          value={channel}
          onChange={setChannel}
          options={enumOptions(CHANNELS)}
          style={{ width: 120 }}
        />
        <Select
          allowClear
          placeholder={t('dlq.reason')}
          aria-label={t('dlq.reason')}
          value={reason}
          onChange={setReason}
          options={enumOptions(SUPPRESSION_REASONS)}
          style={{ width: 180 }}
        />
        <Input
          allowClear
          placeholder={t('suppressions.clientId')}
          value={clientId}
          onChange={(e) => setClientId(e.target.value)}
          style={{ width: 160 }}
        />
        <Button type="primary" onClick={() => setAddOpen(true)}>
          {t('suppressions.add')}
        </Button>
        <Button onClick={() => setImportOpen(true)}>{t('templates.import')}</Button>
        <Button onClick={() => setRefreshToken((n) => n + 1)}>{t('common.refresh')}</Button>
      </Space>

      <ServerTable<Suppression>
        rowKey={(row) => row.entryId ?? ''}
        fetchPage={fetchPage}
        refreshToken={`${refreshToken}|${channel}|${reason}|${clientId}`}
        columns={[
          { title: t('dashboard.channel'), dataIndex: 'channel', width: 100 },
          {
            title: t('suppressions.addressHash'),
            dataIndex: 'addressHash',
            ellipsis: true,
          },
          { title: t('suppressions.clientId'), dataIndex: 'clientId' },
          {
            title: t('dlq.reason'),
            render: (_, row) => <Tag color="red">{row.reason}</Tag>,
          },
          {
            title: t('suppressions.validUntil'),
            render: (_, row) =>
              row.validUntil ? formatDateTime(row.validUntil) : t('suppressions.permanent'),
          },
          { title: t('suppressions.createdBy'), dataIndex: 'createdBy' },
          { title: t('batches.createdAt'), render: (_, row) => formatDateTime(row.createdAt) },
          {
            title: t('common.actions'),
            render: (_, row) => (
              <Popconfirm
                title={t('suppressions.removeConfirm')}
                onConfirm={() => void remove(row)}
                okText={t('common.confirm')}
                cancelText={t('common.cancel')}
              >
                <Button size="small" danger>
                  {t('common.delete')}
                </Button>
              </Popconfirm>
            ),
          },
        ]}
      />

      <Modal
        title={t('suppressions.add')}
        open={addOpen}
        onOk={() => void add()}
        onCancel={() => setAddOpen(false)}
        okText={t('common.save')}
        cancelText={t('common.cancel')}
      >
        <Alert
          type="info"
          showIcon
          message={t('suppressions.addHint')}
          style={{ marginBottom: 16 }}
        />
        <Form form={addForm} layout="vertical">
          <Form.Item
            name="channel"
            label={t('dashboard.channel')}
            tooltip={t('suppressions.channelHint')}
          >
            <Select allowClear options={enumOptions(CHANNELS)} />
          </Form.Item>
          <Form.Item
            name="address"
            label={t('suppressions.address')}
            tooltip={t('suppressions.addressHint')}
          >
            <Input placeholder="998901234567 / user@example.com" />
          </Form.Item>
          <Form.Item
            name="clientId"
            label={t('suppressions.clientId')}
            tooltip={t('suppressions.clientIdHint')}
          >
            <Input />
          </Form.Item>
          <Form.Item
            name="reason"
            label={t('dlq.reason')}
            rules={[{ required: true }]}
            tooltip={t('suppressions.reasonHint')}
          >
            <Select options={enumOptions(SUPPRESSION_REASONS)} />
          </Form.Item>
          <Form.Item
            name="validUntil"
            label={t('suppressions.validUntil')}
            tooltip={t('suppressions.validUntilHint')}
          >
            <DatePicker showTime style={{ width: '100%' }} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={t('templates.import')}
        open={importOpen}
        onCancel={() => {
          setImportOpen(false);
          setImportResult(null);
        }}
        footer={null}
      >
        <Space direction="vertical" size="large" style={{ width: '100%' }}>
          <span>{t('suppressions.importHint')}</span>
          <Upload.Dragger
            accept=".csv,text/csv"
            maxCount={1}
            showUploadList={false}
            disabled={importing}
            beforeUpload={(file) => {
              void importCsv(file);
              return false;
            }}
          >
            <p>{t('templates.dropCsv')}</p>
          </Upload.Dragger>
          {importResult && (
            <Descriptions column={1} size="small" bordered>
              <Descriptions.Item label={t('templates.imported')}>
                {importResult.imported ?? 0}
              </Descriptions.Item>
              <Descriptions.Item label={t('templates.skipped')}>
                {importResult.skipped ?? 0}
              </Descriptions.Item>
              <Descriptions.Item label={t('templates.failures')}>
                {importResult.failures?.length
                  ? importResult.failures.map((failure, index) => (
                      <div key={index}>
                        {t('suppressions.line', { line: failure.line })}: {failure.reason}
                      </div>
                    ))
                  : '—'}
              </Descriptions.Item>
            </Descriptions>
          )}
        </Space>
      </Modal>
      {reasonModal}
    </Space>
  );
}
