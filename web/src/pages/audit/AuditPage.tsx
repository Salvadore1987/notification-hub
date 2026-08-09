import { App as AntdApp, Button, Input, Space, Typography } from 'antd';
import { useCallback, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { api } from '../../api/client';
import type { components } from '../../api/generated/admin-schema';
import { PeriodFilter, type Period } from '../../shared/components/PeriodFilter';
import { ServerTable, type PageQuery } from '../../shared/components/ServerTable';
import { saveTextFile } from '../../shared/download';
import { describeError } from '../../shared/errors';
import { formatDateTime } from '../../shared/time';

type AuditEntry = components['schemas']['AuditEntry'];

/** FR-7.3, SEC-08: entityType+entityId — «кто трогал эти данные», username — «что делал этот человек». */
export function AuditPage() {
  const { t } = useTranslation();
  const { message } = AntdApp.useApp();

  const [period, setPeriod] = useState<Period>({});
  const [username, setUsername] = useState('');
  const [action, setAction] = useState('');
  const [entityType, setEntityType] = useState('');
  const [entityId, setEntityId] = useState('');
  const [refreshToken, setRefreshToken] = useState(0);
  const [exporting, setExporting] = useState(false);

  const filters = useCallback(
    () => ({
      from: period.from,
      to: period.to,
      username: username || undefined,
      action: action || undefined,
      entityType: entityType || undefined,
      entityId: entityId || undefined,
    }),
    [period, username, action, entityType, entityId],
  );

  const fetchPage = useCallback(
    async (query: PageQuery) => {
      const result = await api().GET('/audit', {
        params: { query: { ...filters(), limit: query.limit, offset: query.offset } },
      });
      return { rows: result.data?.items ?? [], total: Number(result.data?.total ?? 0) };
    },
    [filters],
  );

  const exportCsv = async () => {
    setExporting(true);
    try {
      const result = await api().GET('/audit/export', {
        params: { query: filters() },
        parseAs: 'text',
      });
      saveTextFile(result.data ?? '', 'audit.csv');
      // Необъявленный потолок — выгрузка, которая выглядит полной и не является ею (§11.2).
      if (result.response.headers.get('X-Commhub-Truncated') === 'true') {
        void message.warning(t('audit.truncated'));
      }
    } catch (e) {
      void message.error(describeError(e, t));
    } finally {
      setExporting(false);
    }
  };

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Space wrap>
        <PeriodFilter value={period} onChange={setPeriod} />
        <Input
          allowClear
          placeholder={t('audit.username')}
          value={username}
          onChange={(e) => setUsername(e.target.value)}
          style={{ width: 160 }}
        />
        <Input
          allowClear
          placeholder={t('audit.action')}
          value={action}
          onChange={(e) => setAction(e.target.value)}
          style={{ width: 160 }}
        />
        <Input
          allowClear
          placeholder={t('audit.entityType')}
          value={entityType}
          onChange={(e) => setEntityType(e.target.value)}
          style={{ width: 160 }}
        />
        <Input
          allowClear
          placeholder={t('audit.entityId')}
          value={entityId}
          onChange={(e) => setEntityId(e.target.value)}
          style={{ width: 200 }}
        />
        <Button type="primary" onClick={() => setRefreshToken((n) => n + 1)}>
          {t('common.search')}
        </Button>
        <Button loading={exporting} onClick={() => void exportCsv()}>
          {t('statistics.exportCsv')}
        </Button>
      </Space>
      <ServerTable<AuditEntry>
        rowKey={(row) => `${row.occurredAt}-${row.username}-${row.action}-${row.entityId}`}
        fetchPage={fetchPage}
        refreshToken={refreshToken}
        virtualHeight={520}
        columns={[
          {
            title: t('audit.occurredAt'),
            width: 180,
            render: (_, row) => formatDateTime(row.occurredAt),
          },
          { title: t('audit.username'), dataIndex: 'username' },
          { title: t('audit.action'), dataIndex: 'action' },
          { title: t('audit.entityType'), dataIndex: 'entityType' },
          { title: t('audit.entityId'), dataIndex: 'entityId', ellipsis: true },
          {
            title: t('audit.change'),
            render: (_, row) =>
              row.before || row.after ? (
                <Typography.Text
                  ellipsis={{ tooltip: `${row.before ?? ''} → ${row.after ?? ''}` }}
                  style={{ maxWidth: 320 }}
                >
                  {row.before ?? '∅'} → {row.after ?? '∅'}
                </Typography.Text>
              ) : (
                '—'
              ),
          },
          { title: t('audit.sourceIp'), dataIndex: 'sourceIp', width: 130 },
        ]}
      />
    </Space>
  );
}
