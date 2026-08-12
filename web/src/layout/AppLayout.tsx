import { LogoutOutlined, UserOutlined } from '@ant-design/icons';
import { Dropdown, Layout, Menu, Space, Typography } from 'antd';
import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';

import { useSession } from '../auth/sessionContext';
import { LanguageSwitch } from './LanguageSwitch';
import { NAV_ITEMS } from './navigation';

/** Каркас панели: меню — разделы §11.2, отфильтрованные по ролям сессии (FR-7.2). */
export function AppLayout() {
  const { t } = useTranslation();
  const session = useSession();
  const navigate = useNavigate();
  const location = useLocation();

  const menuItems = useMemo(
    () =>
      NAV_ITEMS.filter((item) => session.canSee(item.section)).map((item) => ({
        key: item.path,
        icon: item.icon,
        label: t(item.labelKey),
      })),
    [session, t],
  );

  const selected = NAV_ITEMS.find((item) => location.pathname.startsWith(item.path))?.path;

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Layout.Sider breakpoint="lg" collapsible>
        <div style={{ color: '#fff', padding: 16, fontWeight: 600 }}>{t('app.title')}</div>
        <Menu
          theme="dark"
          mode="inline"
          items={menuItems}
          selectedKeys={selected ? [selected] : []}
          onClick={({ key }) => navigate(key)}
        />
      </Layout.Sider>
      <Layout>
        <Layout.Header
          style={{
            background: '#fff',
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            paddingInline: 24,
          }}
        >
          <Typography.Text strong>{t('app.subtitle')}</Typography.Text>
          <Space size="middle">
            <LanguageSwitch />
            <Dropdown
              menu={{
                items: [
                  {
                    key: 'signout',
                    icon: <LogoutOutlined />,
                    label: t('auth.signOut'),
                    onClick: () => session.signOut(),
                  },
                ],
              }}
            >
              <Space style={{ cursor: 'pointer' }}>
                <UserOutlined />
                {session.userName}
              </Space>
            </Dropdown>
          </Space>
        </Layout.Header>
        <Layout.Content style={{ margin: 24 }}>
          <Outlet />
        </Layout.Content>
      </Layout>
    </Layout>
  );
}
