import {
  App as AntdApp,
  Button,
  Descriptions,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Tag,
  Upload,
} from 'antd';
import { useCallback, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';

import { api } from '../../api/client';
import type { components } from '../../api/generated/admin-schema';
import { ServerTable, type PageQuery } from '../../shared/components/ServerTable';
import { readFileText } from '../../shared/download';
import { describeError } from '../../shared/errors';
import { CHANNELS, enumOptions, type Channel } from '../../shared/labels';

type TemplateSummary = components['schemas']['TemplateSummary'];
type TemplateImportResult = components['schemas']['TemplateImportResult'];

interface CreateForm {
  code: string;
  channel: Channel;
  direction?: string;
  owner?: string;
}

export function TemplatesPage() {
  const { t } = useTranslation();
  const { message } = AntdApp.useApp();
  const navigate = useNavigate();
  const [createForm] = Form.useForm<CreateForm>();

  const [channel, setChannel] = useState<Channel | undefined>();
  const [direction, setDirection] = useState('');
  const [catalogStatus, setCatalogStatus] = useState<'ACTIVE' | 'ARCHIVED' | undefined>();
  const [refreshToken, setRefreshToken] = useState(0);
  const [createOpen, setCreateOpen] = useState(false);
  const [importOpen, setImportOpen] = useState(false);
  const [approver, setApprover] = useState('');
  const [importing, setImporting] = useState(false);
  const [importResult, setImportResult] = useState<TemplateImportResult | null>(null);

  const fetchPage = useCallback(
    async (query: PageQuery) => {
      const result = await api().GET('/templates', {
        params: {
          query: {
            channel,
            direction: direction || undefined,
            catalogStatus,
            limit: query.limit,
            offset: query.offset,
          },
        },
      });
      return { rows: result.data?.items ?? [], total: Number(result.data?.total ?? 0) };
    },
    [channel, direction, catalogStatus],
  );

  const create = async () => {
    try {
      const values = await createForm.validateFields();
      await api().POST('/templates/{code}', {
        params: { path: { code: values.code } },
        body: { channel: values.channel, direction: values.direction, owner: values.owner },
      });
      void message.success(t('common.saved'));
      setCreateOpen(false);
      createForm.resetFields();
      navigate(`/templates/${encodeURIComponent(values.code)}`);
    } catch (e) {
      void message.error(describeError(e, t));
    }
  };

  const importCsv = async (file: Blob) => {
    setImporting(true);
    try {
      const csv = await readFileText(file);
      const result = await api().POST('/templates/import', {
        params: { query: approver ? { approver } : undefined },
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
      <Space wrap>
        <Select
          allowClear
          placeholder={t('dashboard.channel')}
          aria-label={t('dashboard.channel')}
          value={channel}
          onChange={setChannel}
          options={enumOptions(CHANNELS)}
          style={{ width: 130 }}
        />
        <Input
          allowClear
          placeholder={t('templates.direction')}
          value={direction}
          onChange={(e) => setDirection(e.target.value)}
          style={{ width: 180 }}
        />
        <Select
          allowClear
          placeholder={t('templates.catalogStatus')}
          aria-label={t('templates.catalogStatus')}
          value={catalogStatus}
          onChange={setCatalogStatus}
          options={enumOptions(['ACTIVE', 'ARCHIVED'])}
          style={{ width: 150 }}
        />
        <Button type="primary" onClick={() => setCreateOpen(true)}>
          {t('templates.create')}
        </Button>
        <Button onClick={() => setImportOpen(true)}>{t('templates.import')}</Button>
        <Button onClick={() => setRefreshToken((n) => n + 1)}>{t('common.refresh')}</Button>
      </Space>
      <ServerTable<TemplateSummary>
        rowKey={(row) => row.templateId ?? ''}
        fetchPage={fetchPage}
        refreshToken={`${refreshToken}|${channel}|${direction}|${catalogStatus}`}
        onRowClick={(row) => navigate(`/templates/${encodeURIComponent(row.code ?? '')}`)}
        columns={[
          { title: t('templates.code'), dataIndex: 'code' },
          { title: t('dashboard.channel'), dataIndex: 'channel' },
          { title: t('templates.direction'), dataIndex: 'direction' },
          { title: t('templates.owner'), dataIndex: 'owner' },
          {
            title: t('templates.catalogStatus'),
            render: (_, row) => (
              <Tag color={row.catalogStatus === 'ACTIVE' ? 'green' : 'default'}>
                {row.catalogStatus}
              </Tag>
            ),
          },
          {
            title: t('templates.publishedLocales'),
            render: (_, row) =>
              row.publishedLocales?.length
                ? row.publishedLocales.map((locale) => <Tag key={locale}>{locale}</Tag>)
                : '—',
          },
        ]}
      />

      <Modal
        title={t('templates.create')}
        open={createOpen}
        onOk={() => void create()}
        onCancel={() => setCreateOpen(false)}
        okText={t('common.save')}
        cancelText={t('common.cancel')}
      >
        <Form form={createForm} layout="vertical">
          <Form.Item name="code" label={t('templates.code')} rules={[{ required: true }]}>
            <Input placeholder="OTP_LOGIN" />
          </Form.Item>
          <Form.Item name="channel" label={t('dashboard.channel')} rules={[{ required: true }]}>
            <Select options={enumOptions(CHANNELS)} />
          </Form.Item>
          <Form.Item name="direction" label={t('templates.direction')}>
            <Input />
          </Form.Item>
          <Form.Item name="owner" label={t('templates.owner')}>
            <Input />
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
          <span>{t('templates.importHint')}</span>
          <Input
            placeholder={t('templates.approver')}
            value={approver}
            onChange={(e) => setApprover(e.target.value)}
          />
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
              <Descriptions.Item label={t('templates.created')}>
                {importResult.created ?? 0}
              </Descriptions.Item>
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
                        {failure.code} {failure.locale}: {failure.reason}
                      </div>
                    ))
                  : '—'}
              </Descriptions.Item>
            </Descriptions>
          )}
        </Space>
      </Modal>
    </Space>
  );
}
