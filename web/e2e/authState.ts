/**
 * Где лежит SSO-состояние демо-входа.
 *
 * Отдельным файлом, потому что его читает и playwright.config.ts: импортировать константу из
 * auth.setup.ts нельзя — конфиг выполнил бы setup() при загрузке.
 */
export const STORAGE_STATE = 'e2e/.auth/keycloak.json';
