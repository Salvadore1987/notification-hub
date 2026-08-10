const DISCOVERY = 'http://localhost:8180/realms/commhub/.well-known/openid-configuration';

/**
 * E2E ходят в настоящий issuer, поэтому его отсутствие должно быть первой строкой вывода, а не
 * таймаутом навигации через полминуты в файле, который про Keycloak ничего не говорит.
 */
export default async function globalSetup(): Promise<void> {
  const response = await fetch(DISCOVERY).catch(() => null);
  if (!response?.ok) {
    throw new Error(
      `Keycloak не отвечает на ${DISCOVERY}. E2E логинятся демо-пользователем в настоящем issuer:\n` +
        '  docker compose up -d keycloak\n' +
        'и дождитесь состояния healthy (docker compose ps).',
    );
  }
}
