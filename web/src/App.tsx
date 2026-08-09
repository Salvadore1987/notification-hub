import { App as AntApp, ConfigProvider } from 'antd';
import enUS from 'antd/locale/en_US';
import ruRU from 'antd/locale/ru_RU';
import type { ComponentType } from 'react';
import { useTranslation } from 'react-i18next';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';

import { CallbackPage } from './auth/CallbackPage';
import { RequireSection } from './auth/RequireSection';
import { AppLayout } from './layout/AppLayout';
import { NAV_ITEMS } from './layout/navigation';
import { AdministrationPage } from './pages/administration/AdministrationPage';
import { AuditPage } from './pages/audit/AuditPage';
import { BatchesPage } from './pages/batches/BatchesPage';
import { DashboardPage } from './pages/dashboard/DashboardPage';
import { DlqPage } from './pages/dlq/DlqPage';
import { MessagesPage } from './pages/messages/MessagesPage';
import { NotFoundPage } from './pages/NotFoundPage';
import { ProvidersPage } from './pages/providers/ProvidersPage';
import { RoutingPage } from './pages/routing/RoutingPage';
import { StatisticsPage } from './pages/statistics/StatisticsPage';
import { StreamsPage } from './pages/streams/StreamsPage';
import { SuppressionsPage } from './pages/suppressions/SuppressionsPage';
import { TemplateCardPage } from './pages/templates/TemplateCardPage';
import { TemplatesPage } from './pages/templates/TemplatesPage';

// У antd нет узбекской локали — компоненты для uz говорят по-русски (минимум по UI-01 — RU),
// переводы самой панели при этом узбекские.
const ANTD_LOCALES: Record<string, typeof ruRU> = { ru: ruRU, uz: ruRU, en: enUS };

/** Экран раздела по ключу navigation.tsx — маршруты и меню продолжают строиться из одного списка. */
const SECTION_PAGES: Record<string, ComponentType> = {
  dashboard: DashboardPage,
  batches: BatchesPage,
  messages: MessagesPage,
  dlq: DlqPage,
  streams: StreamsPage,
  providers: ProvidersPage,
  routing: RoutingPage,
  templates: TemplatesPage,
  suppressions: SuppressionsPage,
  statistics: StatisticsPage,
  audit: AuditPage,
  administration: AdministrationPage,
};

export function App() {
  const { i18n } = useTranslation();
  return (
    <ConfigProvider locale={ANTD_LOCALES[i18n.language] ?? ruRU}>
      <AntApp>
        <BrowserRouter>
          <Routes>
            <Route path="/auth/callback" element={<CallbackPage />} />
            <Route element={<AppLayout />}>
              <Route path="/" element={<Navigate to="/dashboard" replace />} />
              {NAV_ITEMS.map((item) => {
                const Page = SECTION_PAGES[item.key];
                return (
                  <Route
                    key={item.key}
                    path={item.path}
                    element={
                      <RequireSection section={item.section}>
                        <Page />
                      </RequireSection>
                    }
                  />
                );
              })}
              <Route
                path="/templates/:code"
                element={
                  <RequireSection section="templateManager">
                    <TemplateCardPage />
                  </RequireSection>
                }
              />
              <Route path="*" element={<NotFoundPage />} />
            </Route>
          </Routes>
        </BrowserRouter>
      </AntApp>
    </ConfigProvider>
  );
}
