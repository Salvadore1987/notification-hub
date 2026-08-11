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
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { api } from '../../api/client';
import type { components } from '../../api/generated/admin-schema';
import { useReasonPrompt } from '../../shared/components/ReasonPrompt';
import { describeError } from '../../shared/errors';
import { BALANCING_STRATEGIES, CHANNELS, enumOptions } from '../../shared/labels';
import { ProviderOrderSelect } from './ProviderSelect';
import { reasonHeader } from '../../shared/reason';

type Channel = components['schemas']['Channel'];
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

  // Каналов ровно три, и это перечисление (AR-05), а профиль канала заводится первой же правкой —
  // ненастроенный не приходит с backend и без этой строки настроить его было бы неоткуда.
  const configured = useMemo(() => new Set(rows.map((row) => row.channel)), [rows]);
  const isConfigured = (row: ChannelConfig) => configured.has(row.channel);
  const table = useMemo<ChannelConfig[]>(
    () =>
      CHANNELS.map(
        (channel) =>
          rows.find((row) => row.channel === channel) ?? {
            channel,
            fallbackOrder: [],
            available: false,
          },
      ),
    [rows],
  );

  const openEdit = (channel: ChannelConfig) => {
    form.resetFields();
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
    // Незаполненную форму подсвечивает сама Form; наружу это не ошибка сохранения, а его отсутствие.
    const values = await form.validateFields().catch(() => null);
    if (!values) {
      return;
    }
    try {
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
          header: reasonHeader(reason),
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
        dataSource={table}
        loading={loading}
        pagination={false}
        locale={{ emptyText: t('table.empty') }}
        columns={[
          { title: t('dashboard.channel'), dataIndex: 'channel' },
          {
            title: t('batches.status'),
            render: (_, row) =>
              row.status ? (
                <Tag color={STATE_COLORS[row.status]}>{row.status}</Tag>
              ) : (
                <Tag>{t('providers.channelNotConfigured')}</Tag>
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
                  {isConfigured(row) ? t('common.edit') : t('providers.configureChannel')}
                </Button>
                {/* Смена состояния у незаведённого профиля — гарантированный 409, предлагать нечего. */}
                {isConfigured(row) &&
                  (['ACTIVE', 'DISABLED', 'MAINTENANCE'] as const)
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
        title={
          editing && isConfigured(editing)
            ? t('providers.editChannel', { channel: editing.channel })
            : t('providers.configureChannelTitle', { channel: editing?.channel })
        }
        open={editing !== null}
        onOk={() => void save()}
        onCancel={() => setEditing(null)}
        okText={t('common.save')}
        cancelText={t('common.cancel')}
      >
        <Form form={form} layout="vertical">
          {/* Обязательна: BFF разбирает её как requiredEnum, и без неё профиль канала не построить. */}
          <Form.Item
            name="balancingStrategy"
            label={t('providers.balancingStrategy')}
            tooltip={t('providers.balancingStrategyHint')}
            rules={[{ required: true }]}
          >
            <Select options={enumOptions(BALANCING_STRATEGIES)} />
          </Form.Item>
          <Form.Item
            name="fallbackOrder"
            label={t('providers.fallbackOrder')}
            tooltip={t('providers.fallbackOrderHint')}
          >
            <ProviderOrderSelect channel={editing?.channel as Channel | undefined} />
          </Form.Item>
          <Form.Item
            name={['quietHours', 'start']}
            label={t('streams.quietStart')}
            tooltip={t('providers.quietHoursChannelHint')}
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
            name={['quietHours', 'behavior']}
            label={t('streams.quietBehavior')}
            tooltip={t('streams.quietBehaviorHint')}
          >
            <Select allowClear options={enumOptions(['DEFER', 'REJECT'])} />
          </Form.Item>
          <Form.Item
            name={['quota', 'dailyCount']}
            label={t('streams.dailyCount')}
            tooltip={t('streams.dailyCountHint')}
          >
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item
            name={['quota', 'behavior']}
            label={t('streams.quotaBehavior')}
            tooltip={t('streams.quotaBehaviorHint')}
          >
            <Select allowClear options={enumOptions(['BLOCK', 'ALERT_ONLY'])} />
          </Form.Item>
        </Form>
      </Modal>
      {reasonModal}
    </Space>
  );
}
