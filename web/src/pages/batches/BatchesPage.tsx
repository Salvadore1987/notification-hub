import {
  App as AntdApp,
  Button,
  Checkbox,
  Descriptions,
  Drawer,
  Input,
  Progress,
  Select,
  Space,
  Tag,
} from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate, useSearchParams } from 'react-router-dom';

import { api } from '../../api/client';
import type { components } from '../../api/generated/admin-schema';
import { useSession } from '../../auth/sessionContext';
import { PeriodFilter, type Period } from '../../shared/components/PeriodFilter';
import { useReasonPrompt } from '../../shared/components/ReasonPrompt';
import { ServerTable, type PageQuery } from '../../shared/components/ServerTable';
import { describeError } from '../../shared/errors';
import {
  BATCH_STATUSES,
  CHANNELS,
  batchStatusColor,
  enumOptions,
  type BatchStatus,
  type Channel,
} from '../../shared/labels';
import { formatDateTime } from '../../shared/time';

type Batch = components['schemas']['BatchStatus_'];
type BatchAction = 'start' | 'pause' | 'resume' | 'stop';

/** Какие действия FR-3.2 осмысленны из какого статуса; backend проверит ещё раз (409). */
function availableActions(status: BatchStatus | undefined): BatchAction[] {
  switch (status) {
    case 'ACCEPTED':
      return ['start', 'stop'];
    case 'PROCESSING':
      return ['pause', 'stop'];
    case 'PAUSED':
      return ['resume', 'stop'];
    default:
      return [];
  }
}

export function BatchesPage() {
  const { t } = useTranslation();
  const session = useSession();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const { reasonModal, askReason } = useReasonPrompt();
  const { message } = AntdApp.useApp();

  const [period, setPeriod] = useState<Period>({});
  const [streamId, setStreamId] = useState('');
  const [channel, setChannel] = useState<Channel | undefined>();
  const [status, setStatus] = useState<BatchStatus | undefined>();
  const [activeOnly, setActiveOnly] = useState(false);
  const [refreshToken, setRefreshToken] = useState(0);
  const [selected, setSelected] = useState<Batch | null>(null);

  const canOperate = session.canSee('operator');

  const fetchPage = useCallback(
    async (query: PageQuery) => {
      const result = await api().GET('/batches', {
        params: {
          query: {
            from: period.from,
            to: period.to,
            streamId: streamId || undefined,
            channel,
            status,
            activeOnly,
            limit: query.limit,
            offset: query.offset,
          },
        },
      });
      return { rows: result.data?.items ?? [], total: Number(result.data?.total ?? 0) };
    },
    [period, streamId, channel, status, activeOnly],
  );

  const openCard = useCallback(
    async (batchId: string) => {
      try {
        const result = await api().GET('/batches/{batchId}', {
          params: { path: { batchId } },
        });
        setSelected(result.data ?? null);
      } catch (e) {
        void message.error(describeError(e, t));
      }
    },
    [message, t],
  );

  // Прямая ссылка с дашборда: /batches?batchId=… открывает карточку сразу.
  useEffect(() => {
    const batchId = searchParams.get('batchId');
    if (batchId) {
      void openCard(batchId);
      setSearchParams({}, { replace: true });
    }
  }, [searchParams, setSearchParams, openCard]);

  const runAction = async (batch: Batch, action: BatchAction) => {
    const reason = await askReason(t(`batches.action.${action}`));
    if (reason === null) {
      return;
    }
    try {
      await api().POST('/batches/{batchId}/actions/{action}', {
        params: {
          path: { batchId: batch.batchId ?? '', action },
          header: reason ? { 'X-Commhub-Reason': reason } : undefined,
        },
      });
      void message.success(t('common.done'));
      setRefreshToken((n) => n + 1);
      await openCard(batch.batchId ?? '');
    } catch (e) {
      void message.error(describeError(e, t));
    }
  };

  const progress = selected?.progress;

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Space wrap>
        <PeriodFilter value={period} onChange={setPeriod} />
        <Input
          allowClear
          placeholder={t('batches.streamId')}
          value={streamId}
          onChange={(e) => setStreamId(e.target.value)}
          style={{ width: 180 }}
        />
        <Select
          allowClear
          placeholder={t('dashboard.channel')}
          value={channel}
          onChange={setChannel}
          options={enumOptions(CHANNELS)}
          style={{ width: 130 }}
        />
        <Select
          allowClear
          placeholder={t('batches.status')}
          value={status}
          onChange={setStatus}
          options={enumOptions(BATCH_STATUSES)}
          style={{ width: 160 }}
        />
        <Checkbox checked={activeOnly} onChange={(e) => setActiveOnly(e.target.checked)}>
          {t('batches.activeOnly')}
        </Checkbox>
        <Button onClick={() => setRefreshToken((n) => n + 1)}>{t('common.refresh')}</Button>
      </Space>
      <ServerTable<Batch>
        rowKey={(row) => row.batchId ?? ''}
        fetchPage={fetchPage}
        refreshToken={`${refreshToken}|${period.from}|${period.to}|${streamId}|${channel}|${status}|${activeOnly}`}
        onRowClick={(row) => void openCard(row.batchId ?? '')}
        columns={[
          { title: t('batches.batchId'), dataIndex: 'batchId' },
          { title: t('batches.streamId'), dataIndex: 'streamId' },
          { title: t('dashboard.channel'), dataIndex: 'channel' },
          {
            title: t('batches.status'),
            render: (_, row) => <Tag color={batchStatusColor(row.status)}>{row.status}</Tag>,
          },
          { title: t('batches.total'), dataIndex: 'total' },
          {
            title: t('batches.progress'),
            width: 200,
            render: (_, row) => (
              <Progress percent={Math.round(row.progress?.completionPercent ?? 0)} size="small" />
            ),
          },
          { title: t('batches.cost'), dataIndex: 'costEstimate' },
          { title: t('batches.createdAt'), render: (_, row) => formatDateTime(row.createdAt) },
        ]}
      />
      <Drawer
        title={t('batches.card')}
        width={560}
        open={selected !== null}
        onClose={() => setSelected(null)}
      >
        {selected && (
          <Space direction="vertical" size="large" style={{ width: '100%' }}>
            <Descriptions column={1} size="small" bordered>
              <Descriptions.Item label={t('batches.batchId')}>{selected.batchId}</Descriptions.Item>
              <Descriptions.Item label={t('batches.streamId')}>
                {selected.streamId}
              </Descriptions.Item>
              <Descriptions.Item label={t('dashboard.channel')}>
                {selected.channel}
              </Descriptions.Item>
              <Descriptions.Item label={t('batches.status')}>
                <Tag color={batchStatusColor(selected.status)}>{selected.status}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label={t('batches.total')}>{selected.total}</Descriptions.Item>
              <Descriptions.Item label={t('batches.processed')}>
                {progress?.processed ?? 0}
              </Descriptions.Item>
              <Descriptions.Item label={t('batches.sent')}>{progress?.sent ?? 0}</Descriptions.Item>
              <Descriptions.Item label={t('dashboard.delivered')}>
                {progress?.delivered ?? 0}
              </Descriptions.Item>
              <Descriptions.Item label={t('dashboard.failed')}>
                {progress?.failed ?? 0}
              </Descriptions.Item>
              <Descriptions.Item label={t('batches.cost')}>
                {selected.costEstimate ?? '—'}
              </Descriptions.Item>
              <Descriptions.Item label={t('batches.createdAt')}>
                {formatDateTime(selected.createdAt)}
              </Descriptions.Item>
            </Descriptions>
            <Progress percent={Math.round(progress?.completionPercent ?? 0)} />
            <Space wrap>
              {canOperate &&
                availableActions(selected.status).map((action) => (
                  <Button
                    key={action}
                    danger={action === 'stop'}
                    onClick={() => void runAction(selected, action)}
                  >
                    {t(`batches.action.${action}`)}
                  </Button>
                ))}
              <Button onClick={() => navigate(`/messages?batchId=${selected.batchId}`)}>
                {t('batches.drillDown')}
              </Button>
            </Space>
          </Space>
        )}
      </Drawer>
      {reasonModal}
    </Space>
  );
}
