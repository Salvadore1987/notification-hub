import { Alert, Card, Checkbox, Col, Progress, Row, Space, Statistic, Table, Tag } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';

import { api } from '../../api/client';
import type { components } from '../../api/generated/admin-schema';
import { PeriodFilter, type Period } from '../../shared/components/PeriodFilter';
import { describeError } from '../../shared/errors';
import { batchStatusColor, healthColor, type ProviderHealth } from '../../shared/labels';
import { formatDateTime } from '../../shared/time';

type Dashboard = components['schemas']['Dashboard'];
type StatisticsRow = components['schemas']['StatisticsRow'];
type ActiveBatch = NonNullable<NonNullable<Dashboard['backlog']>['activeBatches']>[number];
type ProviderRow = NonNullable<Dashboard['providers']>[number];

/** Контракт советует polling, а не SSE: он переживает прокси и заснувший ноутбук. */
const REFRESH_MILLIS = 15_000;

export function DashboardPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [period, setPeriod] = useState<Period>({});
  const [includeTest, setIncludeTest] = useState(false);
  const [data, setData] = useState<Dashboard | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const result = await api().GET('/dashboard', {
        params: { query: { from: period.from, to: period.to, includeTest } },
      });
      setData(result.data ?? null);
      setError(null);
    } catch (e) {
      setError(describeError(e, t));
    }
  }, [period, includeTest, t]);

  useEffect(() => {
    void load();
    const timer = setInterval(() => void load(), REFRESH_MILLIS);
    return () => clearInterval(timer);
  }, [load]);

  const totals = data?.totals;
  const deliveryRate = totals?.deliveryRate;

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Space wrap>
        <PeriodFilter value={period} onChange={setPeriod} />
        <Checkbox checked={includeTest} onChange={(e) => setIncludeTest(e.target.checked)}>
          {t('common.includeTest')}
        </Checkbox>
      </Space>
      {error && <Alert type="error" showIcon message={error} />}
      {data?.killSwitch?.active && (
        <Alert
          type="error"
          showIcon
          message={t('dashboard.killSwitchActive')}
          description={
            data.killSwitch.includesCriticalOtp
              ? t('dashboard.killSwitchWithOtp')
              : t('dashboard.killSwitchWithoutOtp')
          }
        />
      )}
      <Row gutter={[16, 16]}>
        <Col xs={12} md={6} xl={3}>
          <Card>
            <Statistic title={t('dashboard.accepted')} value={totals?.accepted ?? 0} />
          </Card>
        </Col>
        <Col xs={12} md={6} xl={3}>
          <Card>
            <Statistic title={t('dashboard.delivered')} value={totals?.delivered ?? 0} />
          </Card>
        </Col>
        <Col xs={12} md={6} xl={3}>
          <Card>
            <Statistic title={t('dashboard.failed')} value={totals?.failed ?? 0} />
          </Card>
        </Col>
        <Col xs={12} md={6} xl={3}>
          <Card>
            <Statistic title={t('dashboard.rejected')} value={totals?.rejected ?? 0} />
          </Card>
        </Col>
        <Col xs={12} md={6} xl={3}>
          <Card>
            <Statistic title={t('dashboard.inFlight')} value={totals?.inFlight ?? 0} />
          </Card>
        </Col>
        <Col xs={12} md={6} xl={4}>
          <Card>
            <Statistic
              title={t('dashboard.deliveryRate')}
              value={deliveryRate !== undefined ? deliveryRate * 100 : '—'}
              precision={deliveryRate !== undefined ? 1 : undefined}
              suffix={deliveryRate !== undefined ? '%' : undefined}
            />
          </Card>
        </Col>
        <Col xs={12} md={6} xl={5}>
          <Card>
            {/* null — OTP-трафика не было; нулём это рисовать нельзя (контракт /dashboard). */}
            <Statistic
              title={t('dashboard.otpLatency')}
              value={data?.otpLatencyP99Millis ?? t('dashboard.noOtpTraffic')}
              suffix={data?.otpLatencyP99Millis != null ? t('dashboard.millis') : undefined}
            />
          </Card>
        </Col>
      </Row>
      <Row gutter={[16, 16]}>
        <Col xs={24} xl={12}>
          <Card title={t('dashboard.byChannel')} size="small">
            <Table<StatisticsRow>
              size="small"
              rowKey={(row) => row.key ?? ''}
              dataSource={data?.byChannel ?? []}
              pagination={false}
              locale={{ emptyText: t('table.empty') }}
              columns={[
                { title: t('dashboard.channel'), dataIndex: 'key' },
                { title: t('dashboard.accepted'), dataIndex: 'accepted' },
                { title: t('dashboard.delivered'), dataIndex: 'delivered' },
                { title: t('dashboard.failed'), dataIndex: 'failed' },
                { title: t('dashboard.rejected'), dataIndex: 'rejected' },
                { title: t('dashboard.segments'), dataIndex: 'segments' },
                {
                  title: t('dashboard.cost'),
                  render: (_, row) => row.cost?.amount ?? '—',
                },
              ]}
            />
          </Card>
        </Col>
        <Col xs={24} xl={12}>
          <Card title={t('dashboard.providers')} size="small">
            <Table<ProviderRow>
              size="small"
              rowKey={(row) => `${row.provider}-${row.channel}`}
              dataSource={data?.providers ?? []}
              pagination={false}
              locale={{ emptyText: t('table.empty') }}
              columns={[
                { title: t('dashboard.provider'), dataIndex: 'provider' },
                { title: t('dashboard.channel'), dataIndex: 'channel' },
                {
                  title: t('dashboard.health'),
                  render: (_, row) => (
                    <Tag color={healthColor(row.health as ProviderHealth)}>{row.health}</Tag>
                  ),
                },
                {
                  title: t('dashboard.selectable'),
                  render: (_, row) => (row.selectable ? t('common.yes') : t('common.no')),
                },
              ]}
            />
          </Card>
        </Col>
      </Row>
      <Card
        title={t('dashboard.activeBatches', { dlq: data?.backlog?.dlqPending ?? 0 })}
        size="small"
      >
        <Table<ActiveBatch>
          size="small"
          rowKey={(row) => row.batchId ?? ''}
          dataSource={data?.backlog?.activeBatches ?? []}
          pagination={false}
          locale={{ emptyText: t('table.empty') }}
          onRow={(row) => ({
            onClick: () => navigate(`/batches?batchId=${row.batchId}`),
            style: { cursor: 'pointer' },
          })}
          columns={[
            { title: t('batches.batchId'), dataIndex: 'batchId' },
            { title: t('batches.streamId'), dataIndex: 'streamId' },
            { title: t('dashboard.channel'), dataIndex: 'channel' },
            {
              title: t('batches.status'),
              render: (_, row) => <Tag color={batchStatusColor(row.status)}>{row.status}</Tag>,
            },
            {
              title: t('batches.progress'),
              width: 220,
              render: (_, row) => (
                <Progress
                  percent={Math.round(row.completionPercent ?? 0)}
                  size="small"
                  status={row.status === 'STOPPED' ? 'exception' : undefined}
                />
              ),
            },
            {
              title: t('batches.createdAt'),
              render: (_, row) => formatDateTime(row.createdAt),
            },
          ]}
        />
      </Card>
    </Space>
  );
}
