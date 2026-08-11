import { Alert, Button, Card, Form, Input, Layout, Space } from 'antd';
import type { TFunction } from 'i18next';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import { LanguageSwitch } from '../layout/LanguageSwitch';
import { SignInError } from './tokenClient';

/**
 * Вход в панель (ADR-0043): форму показывает сама панель, издатель только выдаёт токен.
 *
 * <p>Экран рисуется вместо содержимого по текущему адресу, а не по отдельному маршруту, поэтому
 * глубокая ссылка переживает вход сама собой: `/dlq` остаётся `/dlq`, и после успеха отрисуется
 * именно он. Восстанавливать адрес после возврата от издателя больше не нужно.
 */
interface Credentials {
  readonly username: string;
  readonly password: string;
}

interface LoginPageProps {
  readonly onSignIn: (username: string, password: string) => Promise<void>;
  /** Сессия истекла и продлить её не удалось — оператор ушёл на форму не сам. */
  readonly expired?: boolean;
}

export function LoginPage({ onSignIn, expired = false }: LoginPageProps) {
  const { t } = useTranslation();
  const [submitting, setSubmitting] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);

  // Валидация — дело Form: onFinish вызывается уже проверенным. Отправка не занимается проверкой,
  // иначе незаполненная форма превращается в необработанный reject (урок Phase 18).
  const submit = async (values: Credentials) => {
    setSubmitting(true);
    setFailure(null);
    try {
      await onSignIn(values.username, values.password);
    } catch (error) {
      setFailure(describeFailure(error, t));
      setSubmitting(false);
    }
  };

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Layout.Content
        style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 24 }}
      >
        <Card title={t('auth.title')} extra={<LanguageSwitch />} style={{ width: 380 }}>
          <Space direction="vertical" size="middle" style={{ display: 'flex' }}>
            {expired && !failure && <Alert type="warning" message={t('auth.sessionExpired')} />}
            {failure && <Alert type="error" message={failure} />}
            <Form<Credentials> layout="vertical" onFinish={submit} requiredMark={false}>
              <Form.Item
                name="username"
                label={t('auth.username')}
                rules={[{ required: true, message: t('auth.usernameRequired') }]}
              >
                <Input autoComplete="username" autoFocus />
              </Form.Item>
              <Form.Item
                name="password"
                label={t('auth.password')}
                rules={[{ required: true, message: t('auth.passwordRequired') }]}
              >
                <Input.Password autoComplete="current-password" />
              </Form.Item>
              <Button type="primary" htmlType="submit" loading={submitting} block>
                {t('auth.signIn')}
              </Button>
            </Form>
          </Space>
        </Card>
      </Layout.Content>
    </Layout>
  );
}

/**
 * Отказ издателя словами оператора. Причину, которую назвал сам издатель («Account is not fully
 * set up»), показываем как есть: она точнее любой нашей формулировки и адресована тому, кто читает.
 */
function describeFailure(error: unknown, t: TFunction): string {
  if (!(error instanceof SignInError)) {
    return t('auth.error');
  }
  switch (error.failure) {
    case 'invalidCredentials':
      return t('auth.invalidCredentials');
    case 'issuerUnavailable':
      return t('auth.issuerUnavailable');
    default:
      return error.detail ?? t('auth.error');
  }
}
