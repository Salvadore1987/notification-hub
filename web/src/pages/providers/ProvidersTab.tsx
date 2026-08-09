import { App as AntdApp, Alert, Button, Popconfirm, Space, Table, Tag } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { api } from '../../api/client';
import type { components } from '../../api/generated/admin-schema';
import { useReasonPrompt } from '../../shared/components/ReasonPrompt';
import { describeError } from '../../shared/errors';
import { healthColor, type ProviderHealth } from '../../shared/labels';
import { reasonHeader } from '../../shared/reason';
import { ProviderFormModal, type ProviderRequest } from './ProviderFormModal';

type Provider = components['schemas']['Provider'];
type ProviderState = 'ENABLED' | 'DISABLED' | 'MAINTENANCE';

export function ProvidersTab() {
  const { t } = useTranslation();
  const { message } = AntdApp.useApp();
  const { reasonModal, askReason } = useReasonPrompt();

  const [rows, setRows] = useState<readonly Provider[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<Provider | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const result = await api().GET('/providers');
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

  const save = async (code: string, request: ProviderRequest): Promise<boolean> => {
    try {
      if (editing) {
        await api().PUT('/providers/{providerId}', {
          params: { path: { providerId: editing.providerId ?? '' } },
          body: request,
        });
      } else {
        await api().POST('/providers/{code}', {
          params: { path: { code } },
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

  const setState = async (provider: Provider, state: ProviderState) => {
    const reason = await askReason(t('providers.setState', { state }));
    if (reason === null) {
      return;
    }
    try {
      await api().POST('/providers/{providerId}/state/{state}', {
        params: {
          path: { providerId: provider.providerId ?? '', state },
          header: reasonHeader(reason),
        },
      });
      void message.success(t('common.done'));
      await load();
    } catch (e) {
      void message.error(describeError(e, t));
    }
  };

  const remove = async (provider: Provider) => {
    const reason = await askReason(t('providers.delete'));
    if (reason === null) {
      return;
    }
    try {
      await api().DELETE('/providers/{providerId}', {
        params: {
          path: { providerId: provider.providerId ?? '' },
          header: reasonHeader(reason),
        },
      });
      void message.success(t('common.done'));
      await load();
    } catch (e) {
      void message.error(describeError(e, t));
    }
  };

  const stateOf = (provider: Provider): ProviderState =>
    provider.state?.maintenance ? 'MAINTENANCE' : provider.state?.enabled ? 'ENABLED' : 'DISABLED';

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
          {t('providers.create')}
        </Button>
        <Button onClick={() => void load()}>{t('common.refresh')}</Button>
      </Space>
      {error && <Alert type="error" showIcon message={error} />}
      <Table<Provider>
        rowKey={(row) => row.providerId ?? ''}
        dataSource={rows as Provider[]}
        loading={loading}
        pagination={false}
        locale={{ emptyText: t('table.empty') }}
        columns={[
          { title: t('providers.code'), dataIndex: 'code' },
          { title: t('dashboard.channel'), dataIndex: 'channel' },
          { title: t('providers.adapterType'), dataIndex: 'adapterType' },
          { title: t('providers.weight'), dataIndex: 'weight' },
          {
            title: t('providers.tariff'),
            render: (_, row) => row.tariff?.perSegment ?? row.tariff?.perMessage ?? '—',
          },
          {
            title: t('batches.status'),
            render: (_, row) => {
              const state = stateOf(row);
              return (
                <Tag
                  color={state === 'ENABLED' ? 'green' : state === 'MAINTENANCE' ? 'orange' : 'red'}
                >
                  {state}
                </Tag>
              );
            },
          },
          {
            title: t('dashboard.health'),
            render: (_, row) => (
              <Tag color={healthColor(row.state?.health as ProviderHealth)}>
                {row.state?.health}
              </Tag>
            ),
          },
          {
            title: t('dashboard.selectable'),
            render: (_, row) => (row.state?.selectable ? t('common.yes') : t('common.no')),
          },
          {
            title: t('common.actions'),
            width: 320,
            render: (_, row) => (
              <Space wrap>
                <Button
                  size="small"
                  onClick={() => {
                    setEditing(row);
                    setFormOpen(true);
                  }}
                >
                  {t('common.edit')}
                </Button>
                {(['ENABLED', 'DISABLED', 'MAINTENANCE'] as const)
                  .filter((state) => state !== stateOf(row))
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
                <Popconfirm
                  title={t('providers.deleteConfirm')}
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
      <ProviderFormModal
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
