import { App as AntdApp, Alert, Button, Checkbox, Input, Select, Space, Table } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { api } from '../../api/client';
import type { components } from '../../api/generated/admin-schema';
import { PeriodFilter, type Period } from '../../shared/components/PeriodFilter';
import { saveTextFile } from '../../shared/download';
import { describeError } from '../../shared/errors';
import { CHANNELS, enumOptions, type Channel } from '../../shared/labels';

type StatisticsRow = components['schemas']['StatisticsRow'];
type Dimension = 'CHANNEL' | 'PROVIDER' | 'STREAM' | 'BATCH' | 'DAY' | 'HOUR';

const DIMENSIONS: readonly Dimension[] = ['CHANNEL', 'PROVIDER', 'STREAM', 'BATCH', 'DAY', 'HOUR'];

/** FR-6.2: разрез задаёт dimension, фильтры сужают; выгрузка — тот же запрос в CSV (§11.2). */
export function StatisticsPage() {
  const { t } = useTranslation();
  const { message } = AntdApp.useApp();

  const [dimension, setDimension] = useState<Dimension>('CHANNEL');
  const [period, setPeriod] = useState<Period>({});
  const [channel, setChannel] = useState<Channel | undefined>();
  const [streamId, setStreamId] = useState('');
  const [provider, setProvider] = useState('');
  const [batchId, setBatchId] = useState('');
  const [includeTest, setIncludeTest] = useState(false);
  const [rows, setRows] = useState<readonly StatisticsRow[]>([]);
  const [loading, setLoading] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const requestSeq = useRef(0);

  const queryParams = useCallback(
    () => ({
      dimension,
      from: period.from,
      to: period.to,
      channel,
      streamId: streamId || undefined,
      provider: provider || undefined,
      batchId: batchId || undefined,
      includeTest,
    }),
    [dimension, period, channel, streamId, provider, batchId, includeTest],
  );

  const load = useCallback(async () => {
    const seq = ++requestSeq.current;
    setLoading(true);
    try {
      const result = await api().GET('/statistics', { params: { query: queryParams() } });
      if (seq === requestSeq.current) {
        setRows(result.data ?? []);
        setError(null);
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
  }, [queryParams, t]);

  useEffect(() => {
    void load();
  }, [load]);

  const exportCsv = async () => {
    setExporting(true);
    try {
      const result = await api().GET('/statistics/export', {
        params: { query: queryParams() },
        parseAs: 'text',
      });
      saveTextFile(result.data ?? '', `statistics-${dimension.toLowerCase()}.csv`);
    } catch (e) {
      void message.error(describeError(e, t));
    } finally {
      setExporting(false);
    }
  };

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Space wrap>
        <Select<Dimension>
          value={dimension}
          onChange={setDimension}
          options={DIMENSIONS.map((value) => ({
            value,
            label: t(`statistics.dimension.${value}`),
          }))}
          style={{ width: 170 }}
        />
        <PeriodFilter value={period} onChange={setPeriod} />
        <Select
          allowClear
          placeholder={t('dashboard.channel')}
          aria-label={t('dashboard.channel')}
          value={channel}
          onChange={setChannel}
          options={enumOptions(CHANNELS)}
          style={{ width: 120 }}
        />
        <Input
          allowClear
          placeholder={t('batches.streamId')}
          value={streamId}
          onChange={(e) => setStreamId(e.target.value)}
          style={{ width: 150 }}
        />
        <Input
          allowClear
          placeholder={t('dashboard.provider')}
          value={provider}
          onChange={(e) => setProvider(e.target.value)}
          style={{ width: 150 }}
        />
        <Input
          allowClear
          placeholder={t('batches.batchId')}
          value={batchId}
          onChange={(e) => setBatchId(e.target.value)}
          style={{ width: 200 }}
        />
        <Checkbox checked={includeTest} onChange={(e) => setIncludeTest(e.target.checked)}>
          {t('common.includeTest')}
        </Checkbox>
        <Button onClick={() => void load()}>{t('common.refresh')}</Button>
        <Button type="primary" loading={exporting} onClick={() => void exportCsv()}>
          {t('statistics.exportCsv')}
        </Button>
      </Space>
      {error && <Alert type="error" showIcon message={error} />}
      <Table<StatisticsRow>
        rowKey={(row) => row.key ?? ''}
        dataSource={rows as StatisticsRow[]}
        loading={loading}
        pagination={rows.length > 50 ? { pageSize: 50 } : false}
        locale={{ emptyText: t('table.empty') }}
        columns={[
          { title: t(`statistics.dimension.${dimension}`), dataIndex: 'key' },
          { title: t('dashboard.accepted'), dataIndex: 'accepted' },
          { title: t('dashboard.delivered'), dataIndex: 'delivered' },
          { title: t('dashboard.failed'), dataIndex: 'failed' },
          { title: t('dashboard.rejected'), dataIndex: 'rejected' },
          { title: t('dashboard.inFlight'), dataIndex: 'inFlight' },
          { title: t('dashboard.segments'), dataIndex: 'segments' },
          { title: t('dashboard.cost'), render: (_, row) => row.cost?.amount ?? '—' },
          {
            title: t('dashboard.deliveryRate'),
            render: (_, row) =>
              row.cost?.deliveryRate !== undefined
                ? `${(row.cost.deliveryRate * 100).toFixed(1)}%`
                : '—',
          },
        ]}
      />
    </Space>
  );
}
