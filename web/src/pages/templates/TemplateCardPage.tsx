import {
  App as AntdApp,
  Alert,
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  Modal,
  Popconfirm,
  Select,
  Space,
  Switch,
  Table,
  Tag,
} from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate, useParams } from 'react-router-dom';

import { api } from '../../api/client';
import type { components } from '../../api/generated/admin-schema';
import { useReasonPrompt } from '../../shared/components/ReasonPrompt';
import { describeError } from '../../shared/errors';
import { reasonHeader } from '../../shared/reason';
import {
  CONTENT_LOCALES,
  enumOptions,
  versionStatusColor,
  type VersionStatus,
} from '../../shared/labels';
import { formatDateTime } from '../../shared/time';
import { ProviderSelect } from '../providers/ProviderSelect';
import { TemplatePreviewModal } from './TemplatePreviewModal';

type Template = components['schemas']['Template'];
type TemplateVersion = components['schemas']['TemplateVersion'];
type ContentLocale = components['schemas']['ContentLocale'];
type ProviderMapping = NonNullable<Template['providerMappings']>[number];

interface VersionForm {
  locale: ContentLocale;
  version?: number | null;
  subject?: string;
  text: string;
  html?: string;
}

interface MappingForm {
  providerCode: string;
  providerTemplateId: string;
  approved?: boolean;
}

/** Переходы workflow FR-4.2, какие осмысленны из какого статуса; право решает use case. */
const NEXT_STATES: Record<VersionStatus, VersionStatus[]> = {
  DRAFT: ['IN_REVIEW'],
  IN_REVIEW: ['PUBLISHED', 'REJECTED', 'DRAFT'],
  PUBLISHED: ['ARCHIVED'],
  ARCHIVED: [],
  REJECTED: ['DRAFT'],
};

export function TemplateCardPage() {
  const { t } = useTranslation();
  const { message } = AntdApp.useApp();
  const { code = '' } = useParams();
  const navigate = useNavigate();
  const { reasonModal, askReason } = useReasonPrompt();
  const [versionForm] = Form.useForm<VersionForm>();
  const [cardForm] = Form.useForm<{ direction?: string; owner?: string }>();
  const [mappingForm] = Form.useForm<MappingForm>();

  const [template, setTemplate] = useState<Template | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [versionOpen, setVersionOpen] = useState(false);
  const [cardEditOpen, setCardEditOpen] = useState(false);
  const [mappingOpen, setMappingOpen] = useState(false);
  const [previewOpen, setPreviewOpen] = useState(false);

  const load = useCallback(async () => {
    try {
      const result = await api().GET('/templates/{code}', { params: { path: { code } } });
      setTemplate(result.data ?? null);
      setError(null);
    } catch (e) {
      setError(describeError(e, t));
    }
  }, [code, t]);

  useEffect(() => {
    void load();
  }, [load]);

  const failing = (e: unknown) => void message.error(describeError(e, t));

  const saveCard = async () => {
    try {
      const values = await cardForm.validateFields();
      await api().PUT('/templates/{code}', {
        params: { path: { code } },
        body: { channel: template?.channel, direction: values.direction, owner: values.owner },
      });
      void message.success(t('common.saved'));
      setCardEditOpen(false);
      await load();
    } catch (e) {
      failing(e);
    }
  };

  const archiveOrRestore = async (archive: boolean) => {
    const reason = await askReason(t(archive ? 'templates.archive' : 'templates.restore'));
    if (reason === null) {
      return;
    }
    try {
      if (archive) {
        await api().DELETE('/templates/{code}', {
          params: {
            path: { code },
            header: reasonHeader(reason),
          },
        });
      } else {
        await api().POST('/templates/{code}/restore', {
          params: {
            path: { code },
            header: reasonHeader(reason),
          },
        });
      }
      void message.success(t('common.done'));
      await load();
    } catch (e) {
      failing(e);
    }
  };

  const openVersionEditor = (version: TemplateVersion | null) => {
    versionForm.resetFields();
    if (version) {
      versionForm.setFieldsValue({
        locale: version.locale,
        version: version.version,
        subject: version.body?.subject,
        text: version.body?.text ?? '',
        html: version.body?.html,
      });
    }
    setVersionOpen(true);
  };

  const saveVersion = async () => {
    try {
      const values = await versionForm.validateFields();
      await api().PUT('/templates/{code}/versions', {
        params: { path: { code } },
        body: {
          locale: values.locale,
          version: values.version ?? null,
          subject: values.subject || undefined,
          text: values.text,
          html: values.html || undefined,
        },
      });
      void message.success(t('common.saved'));
      setVersionOpen(false);
      await load();
    } catch (e) {
      failing(e);
    }
  };

  const transition = async (version: TemplateVersion, status: VersionStatus) => {
    try {
      await api().POST('/templates/{code}/versions/{locale}/{version}/state/{status}', {
        params: {
          path: {
            code,
            locale: version.locale ?? 'RU',
            version: version.version ?? 1,
            status,
          },
        },
      });
      void message.success(t('common.done'));
      await load();
    } catch (e) {
      failing(e);
    }
  };

  const saveMapping = async () => {
    try {
      const values = await mappingForm.validateFields();
      await api().PUT('/templates/{code}/providers/{providerCode}', {
        params: { path: { code, providerCode: values.providerCode } },
        body: {
          providerTemplateId: values.providerTemplateId,
          approved: values.approved ?? false,
        },
      });
      void message.success(t('common.saved'));
      setMappingOpen(false);
      mappingForm.resetFields();
      await load();
    } catch (e) {
      failing(e);
    }
  };

  const removeMapping = async (providerCode: string) => {
    try {
      await api().DELETE('/templates/{code}/providers/{providerCode}', {
        params: { path: { code, providerCode } },
      });
      void message.success(t('common.done'));
      await load();
    } catch (e) {
      failing(e);
    }
  };

  const isEmail = template?.channel === 'EMAIL';
  const archived = template?.catalogStatus === 'ARCHIVED';

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Space>
        <Button onClick={() => navigate('/templates')}>{t('common.back')}</Button>
        <Button onClick={() => void load()}>{t('common.refresh')}</Button>
      </Space>
      {error && <Alert type="error" showIcon message={error} />}
      {template && (
        <>
          <Card
            size="small"
            title={template.code}
            extra={
              <Space>
                <Button
                  size="small"
                  onClick={() => {
                    cardForm.setFieldsValue({
                      direction: template.direction,
                      owner: template.owner,
                    });
                    setCardEditOpen(true);
                  }}
                >
                  {t('common.edit')}
                </Button>
                <Button size="small" onClick={() => setPreviewOpen(true)}>
                  {t('templates.preview')}
                </Button>
                <Button
                  size="small"
                  danger={!archived}
                  onClick={() => void archiveOrRestore(!archived)}
                >
                  {t(archived ? 'templates.restore' : 'templates.archive')}
                </Button>
              </Space>
            }
          >
            <Descriptions column={4} size="small">
              <Descriptions.Item label={t('dashboard.channel')}>
                {template.channel}
              </Descriptions.Item>
              <Descriptions.Item label={t('templates.direction')}>
                {template.direction ?? '—'}
              </Descriptions.Item>
              <Descriptions.Item label={t('templates.owner')}>
                {template.owner ?? '—'}
              </Descriptions.Item>
              <Descriptions.Item label={t('templates.catalogStatus')}>
                <Tag color={archived ? 'default' : 'green'}>{template.catalogStatus}</Tag>
              </Descriptions.Item>
            </Descriptions>
          </Card>

          <Card
            size="small"
            title={t('templates.versions')}
            extra={
              <Button size="small" type="primary" onClick={() => openVersionEditor(null)}>
                {t('templates.newDraft')}
              </Button>
            }
          >
            <Table<TemplateVersion>
              size="small"
              rowKey={(row) => row.versionId ?? ''}
              dataSource={(template.versions ?? []) as TemplateVersion[]}
              pagination={false}
              locale={{ emptyText: t('table.empty') }}
              columns={[
                { title: t('templates.locale'), dataIndex: 'locale', width: 80 },
                { title: t('templates.version'), dataIndex: 'version', width: 80 },
                {
                  title: t('batches.status'),
                  width: 130,
                  render: (_, row) => (
                    <Tag color={versionStatusColor(row.status)}>{row.status}</Tag>
                  ),
                },
                {
                  title: t('templates.text'),
                  ellipsis: true,
                  render: (_, row) => row.body?.text ?? '—',
                },
                {
                  title: t('templates.mergeFields'),
                  render: (_, row) =>
                    row.variables?.length
                      ? row.variables.map((name) => <Tag key={name}>{name}</Tag>)
                      : '—',
                },
                {
                  title: t('templates.review'),
                  render: (_, row) =>
                    [
                      row.review?.createdBy && `✎ ${row.review.createdBy}`,
                      row.review?.reviewedBy && `✔ ${row.review.reviewedBy}`,
                      row.review?.publishedAt && formatDateTime(row.review.publishedAt),
                    ]
                      .filter(Boolean)
                      .join(' · ') || '—',
                },
                {
                  title: t('common.actions'),
                  width: 280,
                  render: (_, row) => (
                    <Space wrap>
                      {row.status === 'DRAFT' && (
                        <Button size="small" onClick={() => openVersionEditor(row)}>
                          {t('common.edit')}
                        </Button>
                      )}
                      {(row.status ? NEXT_STATES[row.status] : []).map((next) => (
                        <Button
                          key={next}
                          size="small"
                          type={next === 'PUBLISHED' ? 'primary' : 'default'}
                          danger={next === 'REJECTED'}
                          onClick={() => void transition(row, next)}
                        >
                          {t(`templates.transition.${next}`)}
                        </Button>
                      ))}
                    </Space>
                  ),
                },
              ]}
            />
          </Card>

          <Card
            size="small"
            title={t('templates.providerMappings')}
            extra={
              <Button size="small" onClick={() => setMappingOpen(true)}>
                {t('templates.addMapping')}
              </Button>
            }
          >
            <Table<ProviderMapping>
              size="small"
              rowKey={(row) => row.providerCode ?? ''}
              dataSource={(template.providerMappings ?? []) as ProviderMapping[]}
              pagination={false}
              locale={{ emptyText: t('table.empty') }}
              columns={[
                { title: t('dashboard.provider'), dataIndex: 'providerCode' },
                { title: t('templates.providerTemplateId'), dataIndex: 'providerTemplateId' },
                {
                  title: t('templates.approved'),
                  render: (_, row) => (row.approved ? t('common.yes') : t('common.no')),
                },
                {
                  title: t('common.actions'),
                  render: (_, row) => (
                    <Popconfirm
                      title={t('templates.removeMappingConfirm')}
                      onConfirm={() => void removeMapping(row.providerCode ?? '')}
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
          </Card>
        </>
      )}

      <Modal
        title={t('templates.editCard')}
        open={cardEditOpen}
        onOk={() => void saveCard()}
        onCancel={() => setCardEditOpen(false)}
        okText={t('common.save')}
        cancelText={t('common.cancel')}
      >
        <Form form={cardForm} layout="vertical">
          <Form.Item
            name="direction"
            label={t('templates.direction')}
            tooltip={t('templates.directionHint')}
          >
            <Input />
          </Form.Item>
          <Form.Item name="owner" label={t('templates.owner')} tooltip={t('templates.ownerHint')}>
            <Input />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={t('templates.draftEditor')}
        open={versionOpen}
        onOk={() => void saveVersion()}
        onCancel={() => setVersionOpen(false)}
        okText={t('common.save')}
        cancelText={t('common.cancel')}
        width={680}
      >
        <Alert
          type="info"
          showIcon
          message={t('templates.draftHint')}
          style={{ marginBottom: 16 }}
        />
        <Form form={versionForm} layout="vertical" initialValues={{ locale: 'RU' }}>
          <Form.Item
            name="locale"
            label={t('templates.locale')}
            rules={[{ required: true }]}
            tooltip={t('templates.localeFieldHint')}
          >
            <Select options={enumOptions(CONTENT_LOCALES)} />
          </Form.Item>
          <Form.Item
            name="version"
            label={t('templates.version')}
            tooltip={t('templates.versionHint')}
          >
            <Input disabled />
          </Form.Item>
          {isEmail && (
            <Form.Item
              name="subject"
              label={t('providers.subject')}
              tooltip={t('templates.subjectHint')}
            >
              <Input />
            </Form.Item>
          )}
          <Form.Item
            name="text"
            label={t('templates.text')}
            rules={[{ required: true }]}
            tooltip={t('templates.textHint')}
          >
            <Input.TextArea rows={5} placeholder={t('templates.textPlaceholder')} />
          </Form.Item>
          {isEmail && (
            <Form.Item name="html" label="HTML" tooltip={t('templates.htmlHint')}>
              <Input.TextArea rows={5} />
            </Form.Item>
          )}
        </Form>
      </Modal>

      <Modal
        title={t('templates.addMapping')}
        open={mappingOpen}
        onOk={() => void saveMapping()}
        onCancel={() => setMappingOpen(false)}
        okText={t('common.save')}
        cancelText={t('common.cancel')}
      >
        <Form form={mappingForm} layout="vertical">
          <Form.Item
            name="providerCode"
            label={t('dashboard.provider')}
            rules={[{ required: true }]}

            tooltip={t('templates.mappingProviderHint')}
          >
            <ProviderSelect channel={template?.channel as Channel | undefined} />
          </Form.Item>
          <Form.Item
            name="providerTemplateId"
            label={t('templates.providerTemplateId')}
            rules={[{ required: true }]}

            tooltip={t('templates.providerTemplateIdHint')}
          >
            <Input />
          </Form.Item>
          <Form.Item
            name="approved"
            label={t('templates.approved')}
            valuePropName="checked"
            tooltip={t('templates.approvedHint')}
          >
            <Switch />
          </Form.Item>
        </Form>
      </Modal>

      <TemplatePreviewModal code={code} open={previewOpen} onClose={() => setPreviewOpen(false)} />
      {reasonModal}
    </Space>
  );
}
