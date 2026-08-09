import { App as AntdApp, Alert, Button, Checkbox, Select, Space, Table, Tag } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { api } from '../../api/client';
import type { components } from '../../api/generated/admin-schema';
import { PeriodFilter, type Period } from '../../shared/components/PeriodFilter';
import { useReasonPrompt } from '../../shared/components/ReasonPrompt';
import { describeError } from '../../shared/errors';
import { REJECTION_REASONS, enumOptions } from '../../shared/labels';
import { formatDateTime } from '../../shared/time';

type DlqEntry = components['schemas']['DlqEntry'];
type RejectionReason = components['schemas']['RejectionReason'];

/**
 * Таблица здесь своя, а не ServerTable: очереди нужен выбор строк для пакетного повтора,
 * и владеть данными должен экран — после действия он перечитывает ту же страницу.
 */
export function DlqPage() {
  const { t } = useTranslation();
  const { message } = AntdApp.useApp();
  const { reasonModal, askReason } = useReasonPrompt();

  const [period, setPeriod] = useState<Period>({});
  const [reason, setReason] = useState<RejectionReason | undefined>();
  const [includeRetried, setIncludeRetried] = useState(false);
  const [includeArchived, setIncludeArchived] = useState(false);
  const [rows, setRows] = useState<readonly DlqEntry[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [selectedIds, setSelectedIds] = useState<readonly string[]>([]);
  const requestSeq = useRef(0);

  const load = useCallback(async () => {
    const seq = ++requestSeq.current;
    setLoading(true);
    try {
      const result = await api().GET('/dlq', {
        params: {
          query: {
            from: period.from,
            to: period.to,
            reason,
            includeRetried,
            includeArchived,
            limit: pageSize,
            offset: (page - 1) * pageSize,
          },
        },
      });
      if (seq === requestSeq.current) {
        setRows(result.data?.items ?? []);
        setTotal(Number(result.data?.total ?? 0));
        setError(null);
        setSelectedIds([]);
      }
    } catch (e) {
      if (seq === requestSeq.current) {
        setError(describeError(e, t));
      }
    } finally {
      if (seq === requestSeq.current) {
        setLoading(false);
      }
    }
  }, [period, reason, includeRetried, includeArchived, page, pageSize, t]);

  useEffect(() => {
    void load();
  }, [load]);

  const reportResult = (applied: readonly string[], skipped: readonly string[]) => {
    if (skipped.length > 0) {
      void message.warning(t('dlq.partial', { applied: applied.length, skipped: skipped.length }));
    } else {
      void message.success(t('dlq.applied', { applied: applied.length }));
    }
  };

  const retry = async (messageIds: readonly string[]) => {
    try {
      const result = await api().POST('/dlq/retry', {
        body: { messageIds: [...messageIds] },
      });
      reportResult(result.data?.applied ?? [], result.data?.skipped ?? []);
      await load();
    } catch (e) {
      void message.error(describeError(e, t));
    }
  };

  const archive = async (messageIds: readonly string[]) => {
    const reasonText = await askReason(t('dlq.archive'));
    if (reasonText === null) {
      return;
    }
    try {
      const result = await api().POST('/dlq/archive', {
        params: { header: reasonText ? { 'X-Commhub-Reason': reasonText } : undefined },
        body: { messageIds: [...messageIds] },
      });
      reportResult(result.data?.applied ?? [], result.data?.skipped ?? []);
      await load();
    } catch (e) {
      void message.error(describeError(e, t));
    }
  };

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Space wrap>
        <PeriodFilter value={period} onChange={setPeriod} />
        <Select
          allowClear
          placeholder={t('dlq.reason')}
          value={reason}
          onChange={setReason}
          options={enumOptions(REJECTION_REASONS)}
          style={{ width: 240 }}
        />
        <Checkbox checked={includeRetried} onChange={(e) => setIncludeRetried(e.target.checked)}>
          {t('dlq.includeRetried')}
        </Checkbox>
        <Checkbox checked={includeArchived} onChange={(e) => setIncludeArchived(e.target.checked)}>
          {t('dlq.includeArchived')}
        </Checkbox>
        <Button onClick={() => void load()}>{t('common.refresh')}</Button>
      </Space>
      <Space>
        <Button
          type="primary"
          disabled={selectedIds.length === 0}
          onClick={() => void retry(selectedIds)}
        >
          {t('dlq.retrySelected', { count: selectedIds.length })}
        </Button>
        <Button disabled={selectedIds.length === 0} onClick={() => void archive(selectedIds)}>
          {t('dlq.archiveSelected', { count: selectedIds.length })}
        </Button>
      </Space>
      {error && <Alert type="error" showIcon message={error} />}
      <Table<DlqEntry>
        rowKey={(row) => row.messageId ?? ''}
        dataSource={rows as DlqEntry[]}
        loading={loading}
        rowSelection={{
          selectedRowKeys: [...selectedIds],
          onChange: (keys) => setSelectedIds(keys.map(String)),
          getCheckboxProps: (row) => ({ disabled: !row.retryable && !!row.archived }),
        }}
        pagination={{
          current: page,
          pageSize,
          total,
          showSizeChanger: true,
          showTotal: (n) => t('table.total', { total: n }),
        }}
        onChange={(pagination) => {
          setPage(pagination.current ?? 1);
          setPageSize(pagination.pageSize ?? 20);
        }}
        locale={{ emptyText: t('table.empty') }}
        columns={[
          { title: t('messages.messageId'), dataIndex: 'messageId', width: 300 },
          {
            title: t('dlq.reason'),
            render: (_, row) => <Tag color="red">{row.reason}</Tag>,
          },
          { title: t('dlq.lastError'), dataIndex: 'lastError', ellipsis: true },
          { title: t('dlq.movedAt'), render: (_, row) => formatDateTime(row.movedAt) },
          {
            title: t('dlq.retried'),
            render: (_, row) =>
              row.retriedAt
                ? `${row.retriedBy ?? ''} ${formatDateTime(row.retriedAt)}`.trim()
                : '—',
          },
          {
            title: t('dlq.archived'),
            render: (_, row) => (row.archived ? t('common.yes') : t('common.no')),
          },
          {
            title: t('common.actions'),
            render: (_, row) => (
              <Space>
                <Button
                  size="small"
                  disabled={!row.retryable}
                  onClick={() => void retry([row.messageId ?? ''])}
                >
                  {t('dlq.retry')}
                </Button>
                <Button
                  size="small"
                  disabled={!!row.archived}
                  onClick={() => void archive([row.messageId ?? ''])}
                >
                  {t('dlq.archive')}
                </Button>
              </Space>
            ),
          },
        ]}
      />
      {reasonModal}
    </Space>
  );
}
