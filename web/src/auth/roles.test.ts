import { describe, expect, it } from 'vitest';

import { rolesFromClaim, ROLES, SECTION_ROLES } from './roles';
import { makeSession } from './sessionContext';

describe('rolesFromClaim', () => {
  it('maps SSO groups through the deployment mapping', () => {
    expect(rolesFromClaim(['cn=hub-ops'], { 'cn=hub-ops': 'OPERATOR' })).toEqual(['OPERATOR']);
  });

  it('lets a group named after a role through unmapped — the default deployment mapping', () => {
    expect(rolesFromClaim(['ADMIN'], {})).toEqual(['ADMIN']);
  });

  it('reads a claim that came as a delimited string', () => {
    expect(rolesFromClaim('ADMIN ANALYST', {})).toEqual(['ADMIN', 'ANALYST']);
    expect(rolesFromClaim('ADMIN,ANALYST', {})).toEqual(['ADMIN', 'ANALYST']);
  });

  it('drops groups that are not ours — a token carries every group an employee has', () => {
    expect(rolesFromClaim(['cn=printers', 'VIEWER', 42], {})).toEqual(['VIEWER']);
  });

  it('gives no roles when the claim is absent or not a claim at all', () => {
    expect(rolesFromClaim(undefined, {})).toEqual([]);
    expect(rolesFromClaim({ groups: ['ADMIN'] }, {})).toEqual([]);
  });

  it('does not repeat a role two groups map onto', () => {
    expect(rolesFromClaim(['g1', 'g2'], { g1: 'OPERATOR', g2: 'OPERATOR' })).toEqual(['OPERATOR']);
  });
});

describe('section gating', () => {
  const canSee = (roles: string[]) => makeSession(roles as never, 'tester', () => {}).canSee;

  it('mirrors AdminAuthority: ADMIN does not inherit the other roles implicitly', () => {
    expect(SECTION_ROLES.admin).toEqual(['ADMIN']);
    for (const section of Object.keys(SECTION_ROLES) as (keyof typeof SECTION_ROLES)[]) {
      expect(SECTION_ROLES[section]).toContain('ADMIN');
    }
  });

  it('lets VIEWER read messages and batches but not act on them', () => {
    const viewer = canSee(['VIEWER']);
    expect(viewer('operatorOrViewer')).toBe(true);
    expect(viewer('operator')).toBe(false);
    expect(viewer('admin')).toBe(false);
    expect(viewer('any')).toBe(true);
  });

  it('keeps the audit journal to the auditor and the administrator', () => {
    expect(canSee(['SECURITY_AUDITOR'])('auditor')).toBe(true);
    expect(canSee(['OPERATOR'])('auditor')).toBe(false);
    expect(canSee(['ANALYST'])('auditor')).toBe(false);
  });

  it('gives a session without roles nothing at all', () => {
    const nobody = canSee([]);
    for (const section of Object.keys(SECTION_ROLES) as (keyof typeof SECTION_ROLES)[]) {
      expect(nobody(section)).toBe(false);
    }
  });

  it('gives a session holding every role every section', () => {
    const everything = makeSession(ROLES, 'tester', () => {});
    for (const section of Object.keys(SECTION_ROLES) as (keyof typeof SECTION_ROLES)[]) {
      expect(everything.canSee(section)).toBe(true);
    }
  });
});
