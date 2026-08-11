/**
 * Форматы идентификаторов, которые оператор набирает руками.
 *
 * Правило живёт в доменных value object'ах (`StreamId`, `ProviderCode`, `TemplateCode`) и
 * опубликовано в контракте как `pattern` у соответствующего параметра пути — там его держит в
 * согласии с доменом `AdminOpenApiContractTest.identifierPatternsMatchTheDomain`. Здесь копия для
 * формы: сгенерированные типы `pattern` не переносят, а узнавать о формате из ответа 400 с
 * регулярным выражением внутри — худший способ его узнать.
 *
 * **Регистр у трёх полей разный и это не опечатка.** Поток — строчными (`mobile-app`), потому что
 * его пишут системы-источники в каждом сообщении; код провайдера и код шаблона — заглавными
 * (`PLAYMOBILE`, `OTP_LOGIN`), потому что они попадают в отчёты и в историю сообщений. Форма обязана
 * сказать это до нажатия «Сохранить».
 */
export const IDENTIFIER_PATTERNS = {
  streamId: /^[a-z0-9][a-z0-9._-]{1,63}$/,
  providerCode: /^[A-Z0-9][A-Z0-9_]{1,31}$/,
  templateCode: /^[A-Z0-9][A-Z0-9._-]{1,63}$/,
} as const;

export type IdentifierKind = keyof typeof IDENTIFIER_PATTERNS;

/** Правило antd для поля-идентификатора; сообщение приходит с экрана, потому что оно локализовано. */
export function identifierRule(kind: IdentifierKind, message: string) {
  return { pattern: IDENTIFIER_PATTERNS[kind], message };
}
