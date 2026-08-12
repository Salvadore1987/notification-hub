import { App as AntdApp, Alert, Button, Space, Table, Tag } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { api } from '../../api/client';
import type { components } from '../../api/generated/admin-schema';
import { useReasonPrompt } from '../../shared/components/ReasonPrompt';
import { describeError } from '../../shared/errors';
import { reasonHeader } from '../../shared/reason';
import { formatDateTime } from '../../shared/time';
import { StreamFormModal, type StreamRequest } from './StreamFormModal';

type Stream = components['schemas']['Stream'];

export function StreamsPage() {
  const { t } = useTranslation();
  const { message } = AntdApp.useApp();
  const { reasonModal, askReason } = useReasonPrompt();

  const [rows, setRows] = useState<readonly Stream[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<Stream | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const result = await api().GET('/streams');
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

  const save = async (streamId: string, request: StreamRequest): Promise<boolean> => {
    try {
      if (editing) {
        await api().PUT('/streams/{streamId}', {
          params: { path: { streamId } },
          body: request,
        });
      } else {
        await api().POST('/streams/{streamId}', {
          params: { path: { streamId } },
          body: request,
        });
      }
      void message.success(t('common.saved'));
      setFormOpen(false);
      setEditing(null);
      await load();
      return true;
    } catch (e) {
      void message.error(describeError(e, t));
      return false;
    }
  };

  const toggle = async (stream: Stream) => {
    const suspend = stream.status === 'ACTIVE';
    const reason = await askReason(t(suspend ? 'streams.suspend' : 'streams.resume'));
    if (reason === null) {
      return;
    }
    try {
      await api().POST(suspend ? '/streams/{streamId}/suspend' : '/streams/{streamId}/resume', {
        params: {
          path: { streamId: stream.streamId ?? '' },
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
      <Space>
        <Button
          type="primary"
          onClick={() => {
            setEditing(null);
            setFormOpen(true);
          }}
        >
          {t('streams.create')}
        </Button>
        <Button onClick={() => void load()}>{t('common.refresh')}</Button>
      </Space>
      {error && <Alert type="error" showIcon message={error} />}
      <Table<Stream>
        rowKey={(row) => row.streamId ?? ''}
        dataSource={rows as Stream[]}
        loading={loading}
        pagination={false}
        locale={{ emptyText: t('table.empty') }}
        columns={[
          { title: t('batches.streamId'), dataIndex: 'streamId' },
          { title: t('streams.name'), dataIndex: 'name' },
          {
            title: t('batches.status'),
            render: (_, row) => (
              <Tag color={row.status === 'ACTIVE' ? 'green' : 'red'}>{row.status}</Tag>
            ),
          },
          {
            title: t('streams.connection'),
            render: (_, row) => (
              <Tag
                color={
                  row.connectionStatus === 'CONNECTED'
                    ? 'green'
                    : row.connectionStatus === 'IDLE'
                      ? 'orange'
                      : 'default'
                }
              >
                {row.connectionStatus}
              </Tag>
            ),
          },
          {
            title: t('streams.defaults'),
            render: (_, row) =>
              [row.defaults?.channel, row.defaults?.trafficClass, row.defaults?.priority]
                .filter(Boolean)
                .join(' · ') || '—',
          },
          {
            title: t('streams.lastActivity'),
            render: (_, row) => formatDateTime(row.lastActivityAt),
          },
          {
            title: t('common.actions'),
            render: (_, row) => (
              <Space>
                <Button
                  size="small"
                  onClick={() => {
                    setEditing(row);
                    setFormOpen(true);
                  }}
                >
                  {t('common.edit')}
                </Button>
                <Button
                  size="small"
                  danger={row.status === 'ACTIVE'}
                  onClick={() => void toggle(row)}
                >
                  {t(row.status === 'ACTIVE' ? 'streams.suspend' : 'streams.resume')}
                </Button>
              </Space>
            ),
          },
        ]}
      />
      <StreamFormModal
        open={formOpen}
        initial={editing}
        onSubmit={save}
        onCancel={() => {
          setFormOpen(false);
          setEditing(null);
        }}
      />
      {reasonModal}
    </Space>
  );
}
