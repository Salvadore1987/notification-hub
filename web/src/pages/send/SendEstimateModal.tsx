import { Alert, Descriptions, Modal, Space, Tag, Typography } from 'antd';
import { useTranslation } from 'react-i18next';

import type { components } from '../../api/generated/admin-schema';

type SendEstimate = components['schemas']['SendEstimate'];

/**
 * Смета перед отправкой (ADR-0038, FR-4.4).
 *
 * Показывает то, что ловил бы второй человек при maker/checker: сколько получателей, какой
 * шаблон и какой версии, во что это обойдётся и какие merge-поля никто не заполнил. Пока
 * маршрута нет, подтвердить нельзя — кнопка отключена, а причина названа.
 */
export function SendEstimateModal({
  estimate,
  open,
  sending,
  onConfirm,
  onCancel,
}: {
  readonly estimate: SendEstimate | null;
  readonly open: boolean;
  readonly sending: boolean;
  readonly onConfirm: () => void;
  readonly onCancel: () => void;
}) {
  const { t } = useTranslation();
  const sendable = estimate != null && !estimate.rejection;

  return (
    <Modal
      title={t('send.estimateTitle')}
      open={open}
      onOk={onConfirm}
      onCancel={onCancel}
      okText={t('send.confirm')}
      cancelText={t('common.cancel')}
      okButtonProps={{ disabled: !sendable, loading: sending, danger: true }}
      width={640}
    >
      {estimate && (
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          {estimate.rejection ? (
            <Alert
              type="error"
              showIcon
              message={
                <Space>
                  <Tag color="red">{estimate.rejection.reason}</Tag>
                  {estimate.rejection.detail}
                </Space>
              }
            />
          ) : (
            <Alert type="warning" showIcon message={t('send.estimateWarning')} />
          )}
          <Descriptions column={2} size="small" bordered>
            <Descriptions.Item label={t('send.recipients')}>
              {estimate.recipients ?? 0}
            </Descriptions.Item>
            <Descriptions.Item label={t('messages.segments')}>
              {estimate.segments ?? 0}
            </Descriptions.Item>
            <Descriptions.Item label={t('dashboard.provider')}>
              {estimate.provider ?? '—'}
            </Descriptions.Item>
            <Descriptions.Item label={t('dashboard.cost')}>
              {estimate.estimatedCost ?? '—'}
            </Descriptions.Item>
            <Descriptions.Item label={t('templates.version')} span={2}>
              {estimate.template
                ? `${estimate.template.version} (${estimate.template.status})`
                : '—'}
            </Descriptions.Item>
          </Descriptions>
          {(estimate.missingVariables?.length ?? 0) > 0 && (
            <Alert
              type="warning"
              showIcon
              message={t('send.missingVariables')}
              description={
                <Space wrap>
                  {estimate.missingVariables?.map((name) => (
                    <Tag key={name} color="orange">
                      {name}
                    </Tag>
                  ))}
                </Space>
              }
            />
          )}
          {(estimate.failures?.length ?? 0) > 0 && (
            <Alert
              type="warning"
              showIcon
              message={t('send.fileFailures')}
              description={
                <Space direction="vertical" size={0}>
                  {estimate.failures?.map((failure, index) => (
                    <Typography.Text key={index} type="secondary">
                      {t('suppressions.line', { line: failure.line })}: {failure.reason}
                    </Typography.Text>
                  ))}
                </Space>
              }
            />
          )}
        </Space>
      )}
    </Modal>
  );
}
