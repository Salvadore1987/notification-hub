import {
  App as AntdApp,
  Alert,
  Button,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Table,
  Tag,
} from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { api } from '../../api/client';
import type { components } from '../../api/generated/admin-schema';
import { useReasonPrompt } from '../../shared/components/ReasonPrompt';
import { describeError } from '../../shared/errors';
import { BALANCING_STRATEGIES, enumOptions } from '../../shared/labels';

type ChannelConfig = components['schemas']['ChannelConfig'];
type ChannelRequest = components['schemas']['ChannelRequest'];
type ChannelState = 'ACTIVE' | 'DISABLED' | 'MAINTENANCE';

const STATE_COLORS: Record<ChannelState, string> = {
  ACTIVE: 'green',
  DISABLED: 'red',
  MAINTENANCE: 'orange',
};

export function ChannelsTab() {
  const { t } = useTranslation();
  const { message } = AntdApp.useApp();
  const { reasonModal, askReason } = useReasonPrompt();
  const [form] = Form.useForm<ChannelRequest>();

  const [rows, setRows] = useState<readonly ChannelConfig[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<ChannelConfig | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const result = await api().GET('/channels');
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

  const openEdit = (channel: ChannelConfig) => {
    form.setFieldsValue({
      balancingStrategy: channel.balancingStrategy,
      fallbackOrder: channel.fallbackOrder,
      quietHours: channel.quietHours,
      quota: channel.quota,
    });
    setEditing(channel);
  };

  const save = async () => {
    if (!editing) {
      return;
    }
    try {
      const values = await form.validateFields();
      await api().PUT('/channels/{channel}', {
        params: { path: { channel: editing.channel ?? 'SMS' } },
        body: values,
      });
      void message.success(t('common.saved'));
      setEditing(null);
      await load();
    } catch (e) {
      void message.error(describeError(e, t));
    }
  };

  const setState = async (channel: ChannelConfig, status: ChannelState) => {
    const reason = await askReason(t('providers.setState', { state: status }));
    if (reason === null) {
      return;
    }
    try {
      await api().POST('/channels/{channel}/state/{status}', {
        params: {
          path: { channel: channel.channel ?? 'SMS', status },
          header: reason ? { 'X-Commhub-Reason': reason } : undefined,
        },
      });
      void message.success(t('common.done'));
      await load();
    } catch (e) {
      void message.error(describeError(e, t));
    }
  };

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      {error && <Alert type="error" showIcon message={error} />}
      <Table<ChannelConfig>
        rowKey={(row) => row.channel ?? ''}
        dataSource={rows as ChannelConfig[]}
        loading={loading}
        pagination={false}
        locale={{ emptyText: t('table.empty') }}
        columns={[
          { title: t('dashboard.channel'), dataIndex: 'channel' },
          {
            title: t('batches.status'),
            render: (_, row) => (
              <Tag color={row.status ? STATE_COLORS[row.status] : 'default'}>{row.status}</Tag>
            ),
          },
          { title: t('providers.balancingStrategy'), dataIndex: 'balancingStrategy' },
          {
            title: t('providers.fallbackOrder'),
            render: (_, row) => row.fallbackOrder?.join(' → ') || '—',
          },
          {
            title: t('streams.quietHours'),
            render: (_, row) =>
              row.quietHours ? `${row.quietHours.start}–${row.quietHours.end}` : '—',
          },
          {
            title: t('providers.available'),
            render: (_, row) => (row.available ? t('common.yes') : t('common.no')),
          },
          {
            title: t('common.actions'),
            render: (_, row) => (
              <Space wrap>
                <Button size="small" onClick={() => openEdit(row)}>
                  {t('common.edit')}
                </Button>
                {(['ACTIVE', 'DISABLED', 'MAINTENANCE'] as const)
                  .filter((state) => state !== row.status)
                  .map((state) => (
                    <Button
                      key={state}
                      size="small"
                      danger={state === 'DISABLED'}
                      onClick={() => void setState(row, state)}
                    >
                      {state}
                    </Button>
                  ))}
              </Space>
            ),
          },
        ]}
      />
      <Modal
        title={t('providers.editChannel', { channel: editing?.channel })}
        open={editing !== null}
        onOk={() => void save()}
        onCancel={() => setEditing(null)}
        okText={t('common.save')}
        cancelText={t('common.cancel')}
      >
        <Form form={form} layout="vertical">
          <Form.Item name="balancingStrategy" label={t('providers.balancingStrategy')}>
            <Select allowClear options={enumOptions(BALANCING_STRATEGIES)} />
          </Form.Item>
          <Form.Item
            name="fallbackOrder"
            label={t('providers.fallbackOrder')}
            tooltip={t('providers.fallbackOrderHint')}
          >
            <Select mode="tags" open={false} suffixIcon={null} tokenSeparators={[',', ' ']} />
          </Form.Item>
          <Form.Item name={['quietHours', 'start']} label={t('streams.quietStart')}>
            <Input placeholder="21:00" />
          </Form.Item>
          <Form.Item name={['quietHours', 'end']} label={t('streams.quietEnd')}>
            <Input placeholder="09:00" />
          </Form.Item>
          <Form.Item name={['quietHours', 'behavior']} label={t('streams.quietBehavior')}>
            <Select allowClear options={enumOptions(['DEFER', 'REJECT'])} />
          </Form.Item>
          <Form.Item name={['quota', 'dailyCount']} label={t('streams.dailyCount')}>
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name={['quota', 'behavior']} label={t('streams.quotaBehavior')}>
            <Select allowClear options={enumOptions(['BLOCK', 'ALERT_ONLY'])} />
          </Form.Item>
        </Form>
      </Modal>
      {reasonModal}
    </Space>
  );
}
