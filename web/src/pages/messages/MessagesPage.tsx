import {
  App as AntdApp,
  Button,
  Descriptions,
  Drawer,
  Input,
  Select,
  Space,
  Tag,
  Timeline,
} from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router-dom';

import { api } from '../../api/client';
import type { components } from '../../api/generated/admin-schema';
import { PeriodFilter, type Period } from '../../shared/components/PeriodFilter';
import { ServerTable, type PageQuery } from '../../shared/components/ServerTable';
import { describeError } from '../../shared/errors';
import {
  MESSAGE_STATUSES,
  enumOptions,
  messageStatusColor,
  type MessageStatus,
} from '../../shared/labels';
import { formatDateTime } from '../../shared/time';

type MessageDigest = components['schemas']['MessageDigest'];
type MessageCard = components['schemas']['MessageCard'];

export function MessagesPage() {
  const { t } = useTranslation();
  const { message } = AntdApp.useApp();
  const [searchParams, setSearchParams] = useSearchParams();

  const [period, setPeriod] = useState<Period>({});
  const [streamId, setStreamId] = useState('');
  const [status, setStatus] = useState<MessageStatus | undefined>();
  const [externalMessageId, setExternalMessageId] = useState('');
  const [recipient, setRecipient] = useState('');
  const [correlationId, setCorrelationId] = useState('');
  // Drill-down из карточки рассылки: /messages?batchId=… — это фильтр списка, не отдельный экран.
  const [batchId, setBatchId] = useState(searchParams.get('batchId') ?? '');
  const [refreshToken, setRefreshToken] = useState(0);
  const [card, setCard] = useState<MessageCard | null>(null);

  useEffect(() => {
    if (searchParams.get('batchId')) {
      setSearchParams({}, { replace: true });
    }
  }, [searchParams, setSearchParams]);

  const fetchPage = useCallback(
    async (query: PageQuery) => {
      const result = await api().GET('/messages', {
        params: {
          query: {
            from: period.from,
            to: period.to,
            streamId: streamId || undefined,
            status,
            externalMessageId: externalMessageId || undefined,
            recipient: recipient || undefined,
            correlationId: correlationId || undefined,
            batchId: batchId || undefined,
            limit: query.limit,
            offset: query.offset,
          },
        },
      });
      return { rows: result.data?.items ?? [], total: Number(result.data?.total ?? 0) };
    },
    [period, streamId, status, externalMessageId, recipient, correlationId, batchId],
  );

  const openCard = async (messageId: string) => {
    try {
      const result = await api().GET('/messages/{messageId}', {
        params: { path: { messageId } },
      });
      setCard(result.data ?? null);
    } catch (e) {
      void message.error(describeError(e, t));
    }
  };

  const delivery = card?.delivery;

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Space wrap>
        <PeriodFilter value={period} onChange={setPeriod} />
        <Input
          allowClear
          placeholder={t('messages.externalMessageId')}
          value={externalMessageId}
          onChange={(e) => setExternalMessageId(e.target.value)}
          style={{ width: 200 }}
        />
        <Input
          allowClear
          placeholder={t('messages.recipient')}
          value={recipient}
          onChange={(e) => setRecipient(e.target.value)}
          style={{ width: 180 }}
        />
        <Input
          allowClear
          placeholder={t('messages.correlationId')}
          value={correlationId}
          onChange={(e) => setCorrelationId(e.target.value)}
          style={{ width: 180 }}
        />
        <Input
          allowClear
          placeholder={t('batches.batchId')}
          value={batchId}
          onChange={(e) => setBatchId(e.target.value)}
          style={{ width: 200 }}
        />
        <Input
          allowClear
          placeholder={t('batches.streamId')}
          value={streamId}
          onChange={(e) => setStreamId(e.target.value)}
          style={{ width: 160 }}
        />
        <Select
          allowClear
          placeholder={t('messages.status')}
          aria-label={t('messages.status')}
          value={status}
          onChange={setStatus}
          options={enumOptions(MESSAGE_STATUSES)}
          style={{ width: 190 }}
        />
        <Button type="primary" onClick={() => setRefreshToken((n) => n + 1)}>
          {t('common.search')}
        </Button>
      </Space>
      <ServerTable<MessageDigest>
        rowKey={(row) => row.messageId ?? ''}
        fetchPage={fetchPage}
        refreshToken={refreshToken}
        virtualHeight={520}
        onRowClick={(row) => void openCard(row.messageId ?? '')}
        columns={[
          { title: t('messages.messageId'), dataIndex: 'messageId', width: 300 },
          { title: t('batches.streamId'), dataIndex: 'streamId' },
          { title: t('messages.externalMessageId'), dataIndex: 'externalMessageId' },
          { title: t('dashboard.channel'), dataIndex: 'channel' },
          {
            title: t('messages.status'),
            render: (_, row) => <Tag color={messageStatusColor(row.status)}>{row.status}</Tag>,
          },
          { title: t('messages.recipient'), dataIndex: 'recipient' },
          { title: t('dashboard.provider'), render: (_, row) => row.routing?.provider ?? '—' },
          {
            title: t('messages.acceptedAt'),
            render: (_, row) => formatDateTime(row.acceptedAt),
          },
        ]}
      />
      <Drawer
        title={t('messages.card')}
        width={640}
        open={card !== null}
        onClose={() => setCard(null)}
      >
        {card && (
          <Space direction="vertical" size="large" style={{ width: '100%' }}>
            <Descriptions column={1} size="small" bordered>
              <Descriptions.Item label={t('messages.messageId')}>
                {card.messageId}
              </Descriptions.Item>
              <Descriptions.Item label={t('batches.streamId')}>{card.streamId}</Descriptions.Item>
              <Descriptions.Item label={t('messages.externalMessageId')}>
                {card.externalMessageId ?? '—'}
              </Descriptions.Item>
              <Descriptions.Item label={t('batches.batchId')}>
                {card.batchId ?? '—'}
              </Descriptions.Item>
              <Descriptions.Item label={t('messages.status')}>
                <Tag color={messageStatusColor(card.status)}>{card.status}</Tag>
                {card.reason && <Tag color="red">{card.reason}</Tag>}
              </Descriptions.Item>
              <Descriptions.Item label={t('dashboard.channel')}>
                {delivery?.channel ?? '—'}
              </Descriptions.Item>
              <Descriptions.Item label={t('dashboard.provider')}>
                {delivery?.provider ?? '—'}
              </Descriptions.Item>
              <Descriptions.Item label={t('messages.segments')}>
                {delivery?.segments ?? '—'}
              </Descriptions.Item>
              <Descriptions.Item label={t('batches.cost')}>
                {delivery?.cost ?? '—'}
              </Descriptions.Item>
              <Descriptions.Item label={t('messages.correlationId')}>
                {delivery?.correlationId ?? '—'}
              </Descriptions.Item>
              <Descriptions.Item label={t('messages.acceptedAt')}>
                {formatDateTime(delivery?.acceptedAt)}
              </Descriptions.Item>
              <Descriptions.Item label={t('messages.terminalAt')}>
                {formatDateTime(delivery?.terminalAt)}
              </Descriptions.Item>
              {delivery?.test && (
                <Descriptions.Item label={t('messages.test')}>
                  <Tag color="purple">TEST</Tag>
                </Descriptions.Item>
              )}
            </Descriptions>
            <Timeline
              items={(card.history ?? []).map((entry) => ({
                color:
                  entry.status === 'DELIVERED'
                    ? 'green'
                    : entry.status && ['FAILED', 'REJECTED', 'UNDELIVERED'].includes(entry.status)
                      ? 'red'
                      : 'blue',
                children: (
                  <Space direction="vertical" size={0}>
                    <Space>
                      <Tag color={messageStatusColor(entry.status)}>{entry.status}</Tag>
                      {entry.reason && <Tag>{entry.reason}</Tag>}
                    </Space>
                    {entry.detail && <span>{entry.detail}</span>}
                    <span style={{ color: 'rgba(0,0,0,0.45)' }}>
                      {formatDateTime(entry.occurredAt)}
                    </span>
                  </Space>
                ),
              }))}
            />
          </Space>
        )}
      </Drawer>
    </Space>
  );
}
