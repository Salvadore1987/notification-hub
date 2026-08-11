import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

import {
  BALANCING_STRATEGIES,
  BATCH_STATUSES,
  CHANNELS,
  CONTENT_LOCALES,
  MESSAGE_STATUSES,
  REJECTION_REASONS,
  SUPPRESSION_REASONS,
  batchStatusColor,
  enumOptions,
  healthColor,
  messageStatusColor,
  versionStatusColor,
} from './labels';

/**
 * Списки перечислений в labels.ts — то, что панель показывает в фильтрах, а типы приходят из
 * контракта. Тип проверяет компилятор, полноту списка — никто, поэтому здесь сверка со
 * сгенерированной схемой: новый статус §6.3 не должен молча выпасть из фильтра и из цветов.
 */
const SCHEMA = readFileSync(resolve(process.cwd(), 'src/api/generated/admin-schema.ts'), 'utf8');

function schemaEnum(name: string): string[] {
  const line = SCHEMA.split('\n').find((row) => row.trim().startsWith(`${name}: "`));
  if (!line) {
    throw new Error(`enum ${name} is not in the generated contract`);
  }
  return [...line.matchAll(/"([A-Z_]+)"/g)].map((match) => match[1]);
}

describe('contract enums', () => {
  it.each([
    ['MessageStatus', MESSAGE_STATUSES],
    ['BatchStatus', BATCH_STATUSES],
    ['Channel', CHANNELS],
    ['BalancingStrategy', BALANCING_STRATEGIES],
    ['ContentLocale', CONTENT_LOCALES],
    ['RejectionReason', REJECTION_REASONS],
    ['SuppressionReason', SUPPRESSION_REASONS],
  ])('%s covers every value of the generated contract', (name, values) => {
    expect([...values].sort()).toEqual(schemaEnum(name).sort());
  });
});

describe('tag colors', () => {
  it('gives every message status a colour', () => {
    for (const status of MESSAGE_STATUSES) {
      expect(messageStatusColor(status)).not.toBe('');
    }
    expect(messageStatusColor('DELIVERED')).toBe('green');
    expect(messageStatusColor('REJECTED')).toBe('red');
  });

  it('falls back to the neutral colour for an unknown value', () => {
    expect(messageStatusColor(undefined)).toBe('default');
    expect(batchStatusColor(undefined)).toBe('default');
    expect(healthColor(undefined)).toBe('default');
    expect(versionStatusColor(undefined)).toBe('default');
  });

  it('paints provider health and version workflow by severity', () => {
    expect(healthColor('DOWN')).toBe('red');
    expect(healthColor('DEGRADED')).toBe('orange');
    expect(versionStatusColor('PUBLISHED')).toBe('green');
    expect(versionStatusColor('ON_REVIEW')).toBe('orange');
  });
});

describe('enumOptions', () => {
  it('keeps the contract wording as the label — the status vocabulary is one (§6.3)', () => {
    expect(enumOptions(CHANNELS)).toEqual([
      { value: 'SMS', label: 'SMS' },
      { value: 'EMAIL', label: 'EMAIL' },
      { value: 'PUSH', label: 'PUSH' },
    ]);
  });
});
