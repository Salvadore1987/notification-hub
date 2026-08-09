import {
  App as AntdApp,
  Alert,
  Button,
  Card,
  Checkbox,
  Form,
  Input,
  Modal,
  Popconfirm,
  Space,
  Table,
  Tag,
} from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { api } from '../../api/client';
import type { components } from '../../api/generated/admin-schema';
import { useAppConfig } from '../../config/appConfig';
import { describeError } from '../../shared/errors';
import { formatDateTime } from '../../shared/time';

type KillSwitch = components['schemas']['KillSwitch'];
type SystemParameter = components['schemas']['SystemParameter'];

interface KillSwitchForm {
  includeCriticalOtp?: boolean;
  reason?: string;
}

interface ParameterForm {
  key: string;
  value: string;
  description?: string;
}

/**
 * Пользователей и ролей здесь нет сознательно (§10.1): Модуль не хранит ни логинов, ни паролей —
 * личность даёт корпоративный SSO, а маппинг группа → роль лежит в deployment-конфигурации
 * (config.json у панели, commhub.security у backend) и показывается только для чтения.
 */
export function AdministrationPage() {
  const { t } = useTranslation();
  const { message } = AntdApp.useApp();
  const config = useAppConfig();
  const [killSwitchForm] = Form.useForm<KillSwitchForm>();
  const [parameterForm] = Form.useForm<ParameterForm>();

  const [killSwitch, setKillSwitch] = useState<KillSwitch | null>(null);
  const [parameters, setParameters] = useState<readonly SystemParameter[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [activateOpen, setActivateOpen] = useState(false);
  const [parameterOpen, setParameterOpen] = useState(false);
  const [editingKey, setEditingKey] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const [switchResult, parametersResult] = await Promise.all([
        api().GET('/administration/kill-switch'),
        api().GET('/administration/parameters'),
      ]);
      setKillSwitch(switchResult.data ?? null);
      setParameters(parametersResult.data ?? []);
      setError(null);
    } catch (e) {
      setError(describeError(e, t));
    }
  }, [t]);

  useEffect(() => {
    void load();
  }, [load]);

  const activate = async () => {
    try {
      const values = await killSwitchForm.validateFields();
      await api().POST('/administration/kill-switch', {
        body: {
          activate: true,
          includeCriticalOtp: values.includeCriticalOtp ?? false,
          reason: values.reason,
        },
      });
      void message.success(t('common.done'));
      setActivateOpen(false);
      killSwitchForm.resetFields();
      await load();
    } catch (e) {
      void message.error(describeError(e, t));
    }
  };

  const release = async () => {
    try {
      await api().POST('/administration/kill-switch', {
        body: { activate: false, includeCriticalOtp: false },
      });
      void message.success(t('common.done'));
      await load();
    } catch (e) {
      void message.error(describeError(e, t));
    }
  };

  const openParameter = (parameter: SystemParameter | null) => {
    parameterForm.resetFields();
    if (parameter) {
      parameterForm.setFieldsValue({
        key: parameter.key,
        value: parameter.value,
        description: parameter.description,
      });
    }
    setEditingKey(parameter?.key ?? null);
    setParameterOpen(true);
  };

  const saveParameter = async () => {
    try {
      const values = await parameterForm.validateFields();
      await api().PUT('/administration/parameters/{key}', {
        params: { path: { key: values.key } },
        body: { value: values.value, description: values.description },
      });
      void message.success(t('common.saved'));
      setParameterOpen(false);
      await load();
    } catch (e) {
      void message.error(describeError(e, t));
    }
  };

  const deleteParameter = async (key: string) => {
    try {
      await api().DELETE('/administration/parameters/{key}', {
        params: { path: { key } },
      });
      void message.success(t('common.done'));
      await load();
    } catch (e) {
      void message.error(describeError(e, t));
    }
  };

  const groupRoleRows = Object.entries(config.groupRoles).map(([group, role]) => ({
    group,
    role,
  }));

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      {error && <Alert type="error" showIcon message={error} />}

      <Card size="small" title={t('administration.killSwitch')}>
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          {killSwitch?.active ? (
            <Alert
              type="error"
              showIcon
              message={t('dashboard.killSwitchActive')}
              description={
                <Space direction="vertical" size={0}>
                  <span>
                    {killSwitch.includesCriticalOtp
                      ? t('dashboard.killSwitchWithOtp')
                      : t('dashboard.killSwitchWithoutOtp')}
                  </span>
                  <span>{formatDateTime(killSwitch.changedAt)}</span>
                </Space>
              }
            />
          ) : (
            <Alert type="success" showIcon message={t('administration.killSwitchInactive')} />
          )}
          <Space>
            {killSwitch?.active ? (
              <Popconfirm
                title={t('administration.releaseConfirm')}
                onConfirm={() => void release()}
                okText={t('common.confirm')}
                cancelText={t('common.cancel')}
              >
                <Button type="primary">{t('administration.release')}</Button>
              </Popconfirm>
            ) : (
              <Button danger onClick={() => setActivateOpen(true)}>
                {t('administration.activate')}
              </Button>
            )}
          </Space>
        </Space>
      </Card>

      <Card
        size="small"
        title={t('administration.parameters')}
        extra={
          <Button size="small" type="primary" onClick={() => openParameter(null)}>
            {t('administration.addParameter')}
          </Button>
        }
      >
        <Table<SystemParameter>
          size="small"
          rowKey={(row) => row.key ?? ''}
          dataSource={parameters as SystemParameter[]}
          pagination={false}
          locale={{ emptyText: t('table.empty') }}
          columns={[
            { title: t('administration.key'), dataIndex: 'key' },
            { title: t('administration.value'), dataIndex: 'value', ellipsis: true },
            { title: t('administration.description'), dataIndex: 'description', ellipsis: true },
            { title: t('administration.updatedBy'), dataIndex: 'updatedBy' },
            {
              title: t('administration.updatedAt'),
              render: (_, row) => formatDateTime(row.updatedAt),
            },
            {
              title: t('common.actions'),
              width: 200,
              render: (_, row) => (
                <Space>
                  <Button size="small" onClick={() => openParameter(row)}>
                    {t('common.edit')}
                  </Button>
                  <Popconfirm
                    title={t('administration.deleteParameterConfirm')}
                    onConfirm={() => void deleteParameter(row.key ?? '')}
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
      </Card>

      <Card size="small" title={t('administration.ssoMapping')}>
        <Alert
          type="info"
          showIcon
          message={t('administration.ssoMappingHint')}
          style={{ marginBottom: 16 }}
        />
        <Table<{ group: string; role: string }>
          size="small"
          rowKey={(row) => row.group}
          dataSource={groupRoleRows}
          pagination={false}
          locale={{ emptyText: t('administration.ssoMappingEmpty') }}
          columns={[
            { title: t('administration.ssoGroup'), dataIndex: 'group' },
            {
              title: t('administration.role'),
              render: (_, row) => <Tag>{row.role}</Tag>,
            },
          ]}
        />
      </Card>

      <Modal
        title={t('administration.activate')}
        open={activateOpen}
        onOk={() => void activate()}
        onCancel={() => setActivateOpen(false)}
        okText={t('common.confirm')}
        cancelText={t('common.cancel')}
      >
        <Alert
          type="warning"
          showIcon
          message={t('administration.activateHint')}
          style={{ marginBottom: 16 }}
        />
        <Form form={killSwitchForm} layout="vertical">
          <Form.Item name="reason" label={t('common.reason')} rules={[{ required: true }]}>
            <Input.TextArea rows={3} />
          </Form.Item>
          <Form.Item name="includeCriticalOtp" valuePropName="checked">
            <Checkbox>{t('administration.includeOtp')}</Checkbox>
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={editingKey ? t('administration.editParameter') : t('administration.addParameter')}
        open={parameterOpen}
        onOk={() => void saveParameter()}
        onCancel={() => setParameterOpen(false)}
        okText={t('common.save')}
        cancelText={t('common.cancel')}
      >
        <Form form={parameterForm} layout="vertical">
          <Form.Item name="key" label={t('administration.key')} rules={[{ required: true }]}>
            <Input disabled={editingKey !== null} />
          </Form.Item>
          <Form.Item name="value" label={t('administration.value')} rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="description" label={t('administration.description')}>
            <Input />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  );
}
