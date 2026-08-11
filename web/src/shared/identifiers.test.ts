import { describe, expect, it } from 'vitest';

import { IDENTIFIER_PATTERNS, identifierRule } from './identifiers';

/**
 * Форматы идентификаторов: три поля панели, три разных правила регистра.
 *
 * Примеры выписаны буквально, потому что проверяется не регулярное выражение само по себе, а то,
 * что оператор, набравший `PlayMobile`, узнает об этом от формы, а не от ответа 400.
 */
describe('identifier patterns', () => {
  it('идентификатор потока — строчными', () => {
    expect(IDENTIFIER_PATTERNS.streamId.test('playmobile')).toBe(true);
    expect(IDENTIFIER_PATTERNS.streamId.test('mobile-app')).toBe(true);
    expect(IDENTIFIER_PATTERNS.streamId.test('core.banking_1')).toBe(true);
    expect(IDENTIFIER_PATTERNS.streamId.test('PlayMobile')).toBe(false);
    expect(IDENTIFIER_PATTERNS.streamId.test('-leading')).toBe(false);
    expect(IDENTIFIER_PATTERNS.streamId.test('a')).toBe(false);
  });

  it('код провайдера — заглавными, без точки и дефиса', () => {
    expect(IDENTIFIER_PATTERNS.providerCode.test('PLAYMOBILE')).toBe(true);
    expect(IDENTIFIER_PATTERNS.providerCode.test('SMS_GATE')).toBe(true);
    expect(IDENTIFIER_PATTERNS.providerCode.test('playmobile')).toBe(false);
    // Тип адаптера пишется строчными и через дефис; в поле кода он не подойдёт — и это правильно.
    expect(IDENTIFIER_PATTERNS.providerCode.test('playmobile-http')).toBe(false);
  });

  it('код шаблона — заглавными, точка и дефис разрешены', () => {
    expect(IDENTIFIER_PATTERNS.templateCode.test('OTP_LOGIN')).toBe(true);
    expect(IDENTIFIER_PATTERNS.templateCode.test('CARD.BLOCKED-RU')).toBe(true);
    expect(IDENTIFIER_PATTERNS.templateCode.test('otp_login')).toBe(false);
  });

  it('правило antd несёт и шаблон, и сообщение экрана', () => {
    expect(identifierRule('streamId', 'нужны строчные')).toEqual({
      pattern: IDENTIFIER_PATTERNS.streamId,
      message: 'нужны строчные',
    });
  });
});
