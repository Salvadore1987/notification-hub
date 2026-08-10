import { Button, Form, Input, Space } from 'antd';
import { useTranslation } from 'react-i18next';

/** Одна пара «переменная — значение» в форме отправки. */
export interface VariableRow {
  name?: string;
  value?: string;
}

/** Merge-поля шаблона, набранные руками; в рассылке их приносит файл (FR-4.3). */
export function VariablesField() {
  const { t } = useTranslation();
  return (
    <Form.List name="variables">
      {(fields, { add, remove }) => (
        <Space direction="vertical" style={{ width: '100%', marginBottom: 16 }}>
          {fields.map((field) => (
            <Space key={field.key} align="baseline">
              <Form.Item name={[field.name, 'name']} noStyle>
                <Input placeholder={t('templates.variable')} style={{ width: 200 }} />
              </Form.Item>
              <Form.Item name={[field.name, 'value']} noStyle>
                <Input placeholder={t('providers.configValue')} style={{ width: 280 }} />
              </Form.Item>
              <Button type="link" danger onClick={() => remove(field.name)}>
                {t('common.remove')}
              </Button>
            </Space>
          ))}
          <Button onClick={() => add({})}>{t('templates.addVariable')}</Button>
        </Space>
      )}
    </Form.List>
  );
}
