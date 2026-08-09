import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

import './i18n';
import { initApi } from './api/client';
import { App } from './App';
import { SessionProvider } from './auth/session';
import { AppConfigProvider, loadAppConfig } from './config/appConfig';

// Конфигурация развёртывания читается до первого рендера: клиент API и OIDC-провайдер
// не должны прожить ни одного кадра с дефолтами, которые затем поменяются.
async function bootstrap(): Promise<void> {
  const config = await loadAppConfig();
  initApi(config);

  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <AppConfigProvider value={config}>
        <SessionProvider>
          <App />
        </SessionProvider>
      </AppConfigProvider>
    </StrictMode>,
  );
}

void bootstrap();
