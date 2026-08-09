/**
 * Роли §10.1 и секции §11.2 — клиентское зеркало Roles.java и AdminAuthority.java (FR-7.2).
 *
 * Гейтинг здесь дублирует backend только чтобы не показывать кнопку, которую backend всё равно
 * отклонит (UI-02): решение «можно ли» всегда принимает @PreAuthorize на сервере. Как и в
 * AdminAuthority, ADMIN не наследует остальные роли неявно — «OPERATOR+» из §11.2 выписан явно.
 */
export const ROLES = [
  'ADMIN',
  'OPERATOR',
  'TEMPLATE_MANAGER',
  'ANALYST',
  'VIEWER',
  'SECURITY_AUDITOR',
] as const;

export type Role = (typeof ROLES)[number];

export const SECTION_ROLES = {
  /** Экраны, которые §11.2 помечает «все». */
  any: ROLES,
  /** Конфигурация: провайдеры, маршрутизация, потоки, kill switch, системные параметры. */
  admin: ['ADMIN'],
  /** Живой трафик: действия над батчами, повторы из DLQ, suppression list. */
  operator: ['ADMIN', 'OPERATOR'],
  /** Чтение сообщений и батчей; VIEWER видит адреса маскированными (§11.2 «Сообщения»). */
  operatorOrViewer: ['ADMIN', 'OPERATOR', 'VIEWER'],
  /** Отчёты и их выгрузки. §11.2 «ANALYST+». */
  analyst: ['ADMIN', 'ANALYST'],
  /** Журнал аудита: аудитор и администратор, и сознательно никто больше. */
  auditor: ['ADMIN', 'SECURITY_AUDITOR'],
  /** Авторство и ревью шаблонов (FR-4.2). */
  templateManager: ['ADMIN', 'TEMPLATE_MANAGER'],
} as const satisfies Record<string, readonly Role[]>;

export type Section = keyof typeof SECTION_ROLES;

function isRole(value: string): value is Role {
  return (ROLES as readonly string[]).includes(value);
}

/**
 * SSO-группы из claim'а токена → роли панели. Группа маппится через groupRoles из config.json;
 * группа, названная именем роли, проходит как есть — это и есть дефолтный маппинг развёртывания.
 * Незнакомые группы молча отбрасываются: токен несёт все группы сотрудника, не только наши.
 */
export function rolesFromClaim(
  claimValue: unknown,
  groupRoles: Readonly<Record<string, string>>,
): Role[] {
  const groups: string[] = Array.isArray(claimValue)
    ? claimValue.filter((v): v is string => typeof v === 'string')
    : typeof claimValue === 'string'
      ? claimValue.split(/[\s,]+/)
      : [];
  const roles = new Set<Role>();
  for (const group of groups) {
    const mapped = groupRoles[group] ?? group;
    if (isRole(mapped)) {
      roles.add(mapped);
    }
  }
  return [...roles];
}
