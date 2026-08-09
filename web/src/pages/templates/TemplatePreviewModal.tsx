import {
  App as AntdApp,
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Table,
  Tag,
} from 'antd';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import { api } from '../../api/client';
import type { components } from '../../api/generated/admin-schema';
import { describeError } from '../../shared/errors';
import { CONTENT_LOCALES, enumOptions } from '../../shared/labels';

type TemplatePreview = components['schemas']['TemplatePreview'];
type ContentLocale = components['schemas']['ContentLocale'];
type PreviewCost = NonNullable<TemplatePreview['costs']>[number];

interface PreviewForm {
  locale: ContentLocale;
  version?: number;
  variables?: { name?: string; value?: string }[];
}

/**
 * FR-4.4: предпросмотр не строг — незаполненная переменная остаётся `{NAME}` и перечисляется,
 * потому что смотрят черновик; сегменты и стоимость считает настоящий SegmentCalculator.
 */
export function TemplatePreviewModal({
  code,
  open,
  onClose,
}: {
  readonly code: string;
  readonly open: boolean;
  readonly onClose: () => void;
}) {
  const { t } = useTranslation();
  const { message } = AntdApp.useApp();
  const [form] = Form.useForm<PreviewForm>();
  const [loading, setLoading] = useState(false);
  const [preview, setPreview] = useState<TemplatePreview | null>(null);

  const render = async () => {
    const values = await form.validateFields();
    setLoading(true);
    try {
      const result = await api().POST('/templates/{code}/preview', {
        params: { path: { code } },
        body: {
          locale: values.locale,
          version: values.version ?? null,
          variables: Object.fromEntries(
            (values.variables ?? [])
              .filter((variable) => variable.name?.trim())
              .map((variable) => [variable.name?.trim() ?? '', variable.value ?? '']),
          ),
        },
      });
      setPreview(result.data ?? null);
    } catch (e) {
      void message.error(describeError(e, t));
    } finally {
      setLoading(false);
    }
  };

  return (
    <Modal
      title={t('templates.preview')}
      open={open}
      onCancel={() => {
        setPreview(null);
        onClose();
      }}
      footer={null}
      width={720}
    >
      <Space direction="vertical" size="large" style={{ width: '100%' }}>
        <Form form={form} layout="inline" initialValues={{ locale: 'RU' }} style={{ rowGap: 12 }}>
          <Form.Item name="locale" rules={[{ required: true }]}>
            <Select options={enumOptions(CONTENT_LOCALES)} style={{ width: 100 }} />
          </Form.Item>
          <Form.Item name="version" tooltip={t('templates.previewVersionHint')}>
            <InputNumber min={1} placeholder={t('templates.version')} style={{ width: 130 }} />
          </Form.Item>
          <Button type="primary" loading={loading} onClick={() => void render()}>
            {t('templates.render')}
          </Button>
        </Form>
        <Form form={form} layout="vertical">
          <Form.List name="variables">
            {(fields, { add, remove }) => (
              <Space direction="vertical" style={{ width: '100%' }}>
                {fields.map((field) => (
                  <Space key={field.key} align="baseline">
                    <Form.Item name={[field.name, 'name']} noStyle>
                      <Input placeholder={t('templates.variable')} style={{ width: 200 }} />
                    </Form.Item>
                    <Form.Item name={[field.name, 'value']} noStyle>
                      <Input placeholder={t('providers.configValue')} style={{ width: 280 }} />
                    </Form.Item>
                    <Button type="link" danger onClick={() => remove(field.name)}>
                      {t('common.remove')}
                    </Button>
                  </Space>
                ))}
                <Button onClick={() => add({})}>{t('templates.addVariable')}</Button>
              </Space>
            )}
          </Form.List>
        </Form>
        {preview && (
          <>
            <Card
              size="small"
              title={t('templates.rendered', {
                version: preview.version?.number,
                status: preview.version?.status,
              })}
            >
              <Space direction="vertical" style={{ width: '100%' }}>
                {preview.rendered?.subject && <strong>{preview.rendered.subject}</strong>}
                <div style={{ whiteSpace: 'pre-wrap' }}>{preview.rendered?.text}</div>
                {(preview.missingVariables?.length ?? 0) > 0 && (
                  <div>
                    {t('templates.missingVariables')}:{' '}
                    {preview.missingVariables?.map((name) => (
                      <Tag key={name} color="orange">
                        {name}
                      </Tag>
                    ))}
                  </div>
                )}
              </Space>
            </Card>
            {preview.segmentation && (
              <Descriptions column={3} size="small" bordered>
                <Descriptions.Item label={t('templates.encoding')}>
                  {preview.segmentation.encoding}
                </Descriptions.Item>
                <Descriptions.Item label={t('templates.characters')}>
                  {preview.segmentation.characterCount}
                </Descriptions.Item>
                <Descriptions.Item label={t('messages.segments')}>
                  {preview.segmentation.segments}
                </Descriptions.Item>
              </Descriptions>
            )}
            {(preview.costs?.length ?? 0) > 0 && (
              <Table<PreviewCost>
                size="small"
                rowKey={(row) => row.providerCode ?? ''}
                dataSource={preview.costs as PreviewCost[]}
                pagination={false}
                columns={[
                  { title: t('dashboard.provider'), dataIndex: 'providerCode' },
                  { title: t('batches.cost'), dataIndex: 'cost' },
                  {
                    title: t('dashboard.selectable'),
                    render: (_, row) => (row.selectable ? t('common.yes') : t('common.no')),
                  },
                ]}
              />
            )}
          </>
        )}
      </Space>
    </Modal>
  );
}
