import {
  App as AntdApp,
  Alert,
  Button,
  Card,
  Descriptions,
  Divider,
  Form,
  Input,
  InputNumber,
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

import { api } from '../../api/client';
import type { components } from '../../api/generated/admin-schema';
import { describeError } from '../../shared/errors';
import {
  BALANCING_STRATEGIES,
  CHANNELS,
  PRIORITIES,
  TRAFFIC_CLASSES,
  enumOptions,
} from '../../shared/labels';
import { maskAddress } from '../../shared/masking';

type RoutingPolicy = components['schemas']['RoutingPolicy'];
type RoutingPolicyRequest = components['schemas']['RoutingPolicyRequest'];
type RouteEvaluation = components['schemas']['RouteEvaluation'];

interface EvaluateForm {
  streamId: string;
  msisdn?: string;
  email?: string;
  pushToken?: string;
  clientId?: string;
  channel?: components['schemas']['Channel'];
  trafficClass?: 'CRITICAL_OTP' | 'TRANSACTIONAL' | 'NOTIFICATION';
  priority?: 'HIGH' | 'NORMAL' | 'LOW';
  text?: string;
}

export function RoutingPage() {
  const { t } = useTranslation();
  const { message } = AntdApp.useApp();
  const [policyForm] = Form.useForm<RoutingPolicyRequest>();
  const [evaluateForm] = Form.useForm<EvaluateForm>();

  const [rows, setRows] = useState<readonly RoutingPolicy[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<RoutingPolicy | null>(null);
  const [evaluating, setEvaluating] = useState(false);
  const [evaluation, setEvaluation] = useState<RouteEvaluation | null>(null);
  const [evaluatedFor, setEvaluatedFor] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const result = await api().GET('/routing/policies');
      setRows(result.data ?? []);
      setError(null);
    } catch (e) {
      setError(describeError(e, t));
    } finally {
      setLoading(false);
    }
  }, [t]);

  useEffect(() => {
    void load();
  }, [load]);

  const openForm = (policy: RoutingPolicy | null) => {
    policyForm.resetFields();
    if (policy) {
      policyForm.setFieldsValue({
        match: policy.match,
        action: policy.action,
        priority: policy.priority,
      });
    }
    setEditing(policy);
    setFormOpen(true);
  };

  const save = async () => {
    try {
      const values = await policyForm.validateFields();
      if (editing) {
        await api().PUT('/routing/policies/{policyId}', {
          params: { path: { policyId: editing.policyId ?? '' } },
          body: values,
        });
      } else {
        await api().POST('/routing/policies', { body: values });
      }
      void message.success(t('common.saved'));
      setFormOpen(false);
      setEditing(null);
      await load();
    } catch (e) {
      void message.error(describeError(e, t));
    }
  };

  const setEnabled = async (policy: RoutingPolicy, enabled: boolean) => {
    try {
      await api().POST('/routing/policies/{policyId}/state/{enabled}', {
        params: { path: { policyId: policy.policyId ?? '', enabled } },
      });
      await load();
    } catch (e) {
      void message.error(describeError(e, t));
    }
  };

  const remove = async (policy: RoutingPolicy) => {
    try {
      await api().DELETE('/routing/policies/{policyId}', {
        params: { path: { policyId: policy.policyId ?? '' } },
      });
      void message.success(t('common.done'));
      await load();
    } catch (e) {
      void message.error(describeError(e, t));
    }
  };

  const evaluate = async () => {
    const values = await evaluateForm.validateFields();
    setEvaluating(true);
    try {
      const result = await api().POST('/routing/evaluate', {
        body: {
          streamId: values.streamId,
          recipient: {
            clientId: values.clientId || undefined,
            msisdn: values.msisdn || undefined,
            email: values.email || undefined,
            pushToken: values.pushToken || undefined,
          },
          channel: values.channel,
          trafficClass: values.trafficClass,
          priority: values.priority,
          text: values.text || undefined,
        },
      });
      setEvaluation(result.data ?? null);
      setEvaluatedFor(maskAddress(values.msisdn || values.email || values.pushToken));
    } catch (e) {
      setEvaluation(null);
      void message.error(describeError(e, t));
    } finally {
      setEvaluating(false);
    }
  };

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Card
        title={t('routing.policies')}
        size="small"
        extra={
          <Space>
            <Button type="primary" onClick={() => openForm(null)}>
              {t('routing.create')}
            </Button>
            <Button onClick={() => void load()}>{t('common.refresh')}</Button>
          </Space>
        }
      >
        {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 16 }} />}
        <Table<RoutingPolicy>
          rowKey={(row) => row.policyId ?? ''}
          dataSource={rows as RoutingPolicy[]}
          loading={loading}
          pagination={false}
          locale={{ emptyText: t('table.empty') }}
          columns={[
            { title: t('routing.priority'), dataIndex: 'priority', width: 100 },
            {
              title: t('routing.match'),
              render: (_, row) =>
                [
                  row.match?.streamId && `stream=${row.match.streamId}`,
                  row.match?.trafficClass && `class=${row.match.trafficClass}`,
                  row.match?.minPriority && `priority≥${row.match.minPriority}`,
                  row.match?.channel && `channel=${row.match.channel}`,
                ]
                  .filter(Boolean)
                  .join(', ') || t('routing.matchAll'),
            },
            {
              title: t('routing.action'),
              render: (_, row) =>
                [
                  row.action?.channel,
                  row.action?.providerOrder?.join(' → '),
                  row.action?.balancingStrategy,
                ]
                  .filter(Boolean)
                  .join(' · ') || '—',
            },
            {
              title: t('routing.enabled'),
              width: 110,
              render: (_, row) => (
                <Switch
                  checked={row.enabled}
                  onChange={(checked) => void setEnabled(row, checked)}
                />
              ),
            },
            {
              title: t('common.actions'),
              width: 200,
              render: (_, row) => (
                <Space>
                  <Button size="small" onClick={() => openForm(row)}>
                    {t('common.edit')}
                  </Button>
                  <Popconfirm
                    title={t('routing.deleteConfirm')}
                    onConfirm={() => void remove(row)}
                    okText={t('common.confirm')}
                    cancelText={t('common.cancel')}
                  >
                    <Button size="small" danger>
                      {t('common.delete')}
                    </Button>
                  </Popconfirm>
                </Space>
              ),
            },
          ]}
        />
      </Card>

      <Card title={t('routing.dryRun')} size="small">
        <Alert
          type="info"
          showIcon
          message={t('routing.dryRunHint')}
          style={{ marginBottom: 16 }}
        />
        <Form form={evaluateForm} layout="inline" style={{ rowGap: 12 }}>
          <Form.Item name="streamId" rules={[{ required: true }]}>
            <Input placeholder={t('batches.streamId')} style={{ width: 160 }} />
          </Form.Item>
          <Form.Item name="msisdn">
            <Input placeholder="MSISDN" style={{ width: 150 }} />
          </Form.Item>
          <Form.Item name="email">
            <Input placeholder="Email" style={{ width: 180 }} />
          </Form.Item>
          <Form.Item name="channel">
            <Select
              allowClear
              placeholder={t('dashboard.channel')}
              aria-label={t('dashboard.channel')}
              options={enumOptions(CHANNELS)}
              style={{ width: 120 }}
            />
          </Form.Item>
          <Form.Item name="trafficClass">
            <Select
              allowClear
              placeholder={t('streams.trafficClass')}
              aria-label={t('streams.trafficClass')}
              options={enumOptions(TRAFFIC_CLASSES)}
              style={{ width: 170 }}
            />
          </Form.Item>
          <Form.Item name="priority">
            <Select
              allowClear
              placeholder={t('streams.priority')}
              aria-label={t('streams.priority')}
              options={enumOptions(PRIORITIES)}
              style={{ width: 130 }}
            />
          </Form.Item>
          <Form.Item name="text">
            <Input placeholder={t('routing.textForSegments')} style={{ width: 240 }} />
          </Form.Item>
          <Button type="primary" loading={evaluating} onClick={() => void evaluate()}>
            {t('routing.evaluate')}
          </Button>
        </Form>
        {evaluation && (
          <>
            <Divider plain>
              {t('routing.evaluationFor', { recipient: evaluatedFor || '—' })}
            </Divider>
            {evaluation.routed ? (
              <Descriptions column={2} size="small" bordered>
                <Descriptions.Item label={t('dashboard.channel')}>
                  {evaluation.channel}
                </Descriptions.Item>
                <Descriptions.Item label={t('dashboard.provider')}>
                  {evaluation.provider}
                </Descriptions.Item>
                <Descriptions.Item label={t('routing.fallback')}>
                  {evaluation.fallbackProviders?.join(' → ') || '—'}
                </Descriptions.Item>
                <Descriptions.Item label={t('providers.balancingStrategy')}>
                  {evaluation.strategy}
                </Descriptions.Item>
                <Descriptions.Item label={t('messages.segments')}>
                  {evaluation.segments ?? '—'}
                </Descriptions.Item>
                <Descriptions.Item label={t('routing.estimatedCost')}>
                  {evaluation.estimatedCost ?? '—'}
                </Descriptions.Item>
              </Descriptions>
            ) : (
              <Alert
                type="warning"
                showIcon
                message={
                  <Space>
                    <Tag color="red">{evaluation.rejection?.reason}</Tag>
                    {evaluation.rejection?.detail}
                  </Space>
                }
              />
            )}
          </>
        )}
      </Card>

      <Modal
        title={editing ? t('routing.edit') : t('routing.create')}
        open={formOpen}
        onOk={() => void save()}
        onCancel={() => {
          setFormOpen(false);
          setEditing(null);
        }}
        okText={t('common.save')}
        cancelText={t('common.cancel')}
        width={620}
      >
        <Form form={policyForm} layout="vertical">
          <Divider plain>{t('routing.match')}</Divider>
          <Form.Item name={['match', 'streamId']} label={t('batches.streamId')}>
            <Input />
          </Form.Item>
          <Form.Item name={['match', 'trafficClass']} label={t('streams.trafficClass')}>
            <Select allowClear options={enumOptions(TRAFFIC_CLASSES)} />
          </Form.Item>
          <Form.Item name={['match', 'minPriority']} label={t('routing.minPriority')}>
            <Select allowClear options={enumOptions(PRIORITIES)} />
          </Form.Item>
          <Form.Item name={['match', 'channel']} label={t('dashboard.channel')}>
            <Select allowClear options={enumOptions(CHANNELS)} />
          </Form.Item>
          <Divider plain>{t('routing.action')}</Divider>
          <Form.Item name={['action', 'channel']} label={t('dashboard.channel')}>
            <Select allowClear options={enumOptions(CHANNELS)} />
          </Form.Item>
          <Form.Item
            name={['action', 'providerOrder']}
            label={t('routing.providerOrder')}
            tooltip={t('providers.fallbackOrderHint')}
          >
            <Select mode="tags" open={false} suffixIcon={null} tokenSeparators={[',', ' ']} />
          </Form.Item>
          <Form.Item
            name={['action', 'balancingStrategy']}
            label={t('providers.balancingStrategy')}
          >
            <Select allowClear options={enumOptions(BALANCING_STRATEGIES)} />
          </Form.Item>
          <Form.Item
            name="priority"
            label={t('routing.priority')}
            tooltip={t('routing.priorityHint')}
          >
            <InputNumber style={{ width: '100%' }} />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  );
}
