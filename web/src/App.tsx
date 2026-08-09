import { App as AntApp, ConfigProvider } from 'antd';
import enUS from 'antd/locale/en_US';
import ruRU from 'antd/locale/ru_RU';
import { useTranslation } from 'react-i18next';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';

import { CallbackPage } from './auth/CallbackPage';
import { RequireSection } from './auth/RequireSection';
import { AppLayout } from './layout/AppLayout';
import { NAV_ITEMS } from './layout/navigation';
import { NotFoundPage } from './pages/NotFoundPage';
import { PlaceholderPage } from './pages/PlaceholderPage';

// У antd нет узбекской локали — компоненты для uz говорят по-русски (минимум по UI-01 — RU),
// переводы самой панели при этом узбекские.
const ANTD_LOCALES: Record<string, typeof ruRU> = { ru: ruRU, uz: ruRU, en: enUS };

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
              {NAV_ITEMS.map((item) => (
                <Route
                  key={item.key}
                  path={item.path}
                  element={
                    <RequireSection section={item.section}>
                      <PlaceholderPage titleKey={item.labelKey} />
                    </RequireSection>
                  }
                />
              ))}
              <Route path="*" element={<NotFoundPage />} />
            </Route>
          </Routes>
        </BrowserRouter>
      </AntApp>
    </ConfigProvider>
  );
}
