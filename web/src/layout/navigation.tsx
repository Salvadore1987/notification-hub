import {
  AuditOutlined,
  BarChartOutlined,
  ClusterOutlined,
  ControlOutlined,
  DashboardOutlined,
  FileTextOutlined,
  ImportOutlined,
  MailOutlined,
  RocketOutlined,
  SendOutlined,
  SettingOutlined,
  StopOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import type { ReactNode } from 'react';

import type { Section } from '../auth/roles';

/**
 * Разделы §11.2 один к одному: путь, ключ перевода и секция AdminAuthority, которой раздел
 * принадлежит. Меню и маршруты строятся из этого списка, поэтому раздел добавляется здесь один раз.
 */
export interface NavItem {
  readonly key: string;
  readonly path: string;
  readonly labelKey: string;
  readonly icon: ReactNode;
  readonly section: Section;
}

export const NAV_ITEMS: readonly NavItem[] = [
  {
    key: 'dashboard',
    path: '/dashboard',
    labelKey: 'nav.dashboard',
    icon: <DashboardOutlined />,
    section: 'any',
  },
  {
    key: 'batches',
    path: '/batches',
    labelKey: 'nav.batches',
    icon: <SendOutlined />,
    section: 'operatorOrViewer',
  },
  {
    // Отправка из панели (ADR-0038): своя секция, потому что это действие, а не список.
    key: 'send',
    path: '/send',
    labelKey: 'nav.send',
    icon: <RocketOutlined />,
    section: 'operator',
  },
  {
    key: 'messages',
    path: '/messages',
    labelKey: 'nav.messages',
    icon: <MailOutlined />,
    section: 'operatorOrViewer',
  },
  { key: 'dlq', path: '/dlq', labelKey: 'nav.dlq', icon: <WarningOutlined />, section: 'operator' },
  {
    key: 'streams',
    path: '/streams',
    labelKey: 'nav.streams',
    icon: <ImportOutlined />,
    section: 'admin',
  },
  {
    key: 'providers',
    path: '/providers',
    labelKey: 'nav.providers',
    icon: <ClusterOutlined />,
    section: 'admin',
  },
  {
    key: 'routing',
    path: '/routing',
    labelKey: 'nav.routing',
    icon: <ControlOutlined />,
    section: 'admin',
  },
  {
    key: 'templates',
    path: '/templates',
    labelKey: 'nav.templates',
    icon: <FileTextOutlined />,
    section: 'templateManager',
  },
  {
    key: 'suppressions',
    path: '/suppressions',
    labelKey: 'nav.suppressions',
    icon: <StopOutlined />,
    section: 'operator',
  },
  {
    key: 'statistics',
    path: '/statistics',
    labelKey: 'nav.statistics',
    icon: <BarChartOutlined />,
    section: 'analyst',
  },
  {
    key: 'audit',
    path: '/audit',
    labelKey: 'nav.audit',
    icon: <AuditOutlined />,
    section: 'auditor',
  },
  {
    key: 'administration',
    path: '/administration',
    labelKey: 'nav.administration',
    icon: <SettingOutlined />,
    section: 'admin',
  },
];
