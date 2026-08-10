import type { VariableRow } from './VariablesField';

/**
 * Строки формы → карта переменных, как её ждёт контракт.
 *
 * Отдельный файл, потому что рядом с компонентом функция ломает fast refresh — то же правило,
 * по которому returnTo живёт отдельно от session.tsx.
 */
export function variablesOf(rows: VariableRow[] | undefined): Record<string, string> {
  return Object.fromEntries(
    (rows ?? [])
      .filter((row) => row.name?.trim())
      .map((row) => [row.name?.trim() ?? '', row.value ?? '']),
  );
}
