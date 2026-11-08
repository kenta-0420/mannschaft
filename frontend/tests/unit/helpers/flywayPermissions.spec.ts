// @vitest-environment node
import { describe, expect, it } from 'vitest'
import { parsePermissionInserts } from './flywayPermissions'

/**
 * F01.2.1 ALIC-2 — Flyway 権限抽出ヘルパーの自己検証（番人の偽 PASS 防止）。
 *
 *   AC-ALIC2-05 陽性: 4書式（VALUES 単発・複数行・INSERT IGNORE・SELECT FROM DUAL）から name・scope を取り、
 *                     列順が違っても正しく、TEAM/ORGANIZATION を区別する
 *   AC-ALIC2-06 陰性: コメント中の INSERT・role_permissions への INSERT は拾わない。
 *                     SET 形式・name/scope 列なし・scope 不正・出現数と解析文数の不一致・1文0件は例外
 */
const pairs = (sql: string) => parsePermissionInserts(sql, 'fixture.sql').map(p => `${p.scope}:${p.name}`)

describe('flywayPermissions 自己検証（陽性 AC-ALIC2-05）', () => {
  it('VALUES 単発', () => {
    expect(pairs(`INSERT INTO permissions (name, display_name, scope, created_at, updated_at)
      VALUES ('MANAGE_SCHEDULES', '予定管理（a, b）', 'TEAM', NOW(), NOW());`)).toEqual(['TEAM:MANAGE_SCHEDULES'])
  })

  it('VALUES 複数行で TEAM と ORGANIZATION を区別する', () => {
    expect(pairs(`INSERT INTO permissions (name, display_name, scope, created_at, updated_at) VALUES
      ('MANAGE_SHIFTS', 'シフト''管理', 'TEAM', NOW(), NOW()),
      ('BUDGET_VIEW', '予算閲覧', 'ORGANIZATION', NOW(), NOW()),
      ('PLATFORM_X', 'x', 'PLATFORM', NOW(), NOW());`)).toEqual(['TEAM:MANAGE_SHIFTS', 'ORGANIZATION:BUDGET_VIEW', 'PLATFORM:PLATFORM_X'])
  })

  it('INSERT IGNORE INTO', () => {
    expect(pairs(`INSERT IGNORE INTO permissions (name, display_name, scope, created_at, updated_at)
      VALUES ('MANAGE_TODO', 'TODO', 'ORGANIZATION', NOW(), NOW());`)).toEqual(['ORGANIZATION:MANAGE_TODO'])
  })

  it('INSERT … SELECT … FROM DUAL WHERE NOT EXISTS（jobs.manage の書式）', () => {
    expect(pairs(`INSERT INTO permissions (name, display_name, scope, created_at, updated_at)
      SELECT 'jobs.manage', '求人管理', 'TEAM', NOW(), NOW() FROM DUAL
      WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE name = 'jobs.manage');`)).toEqual(['TEAM:jobs.manage'])
  })

  it('列順が違っても name・scope の位置を列リストから読む', () => {
    expect(pairs(`INSERT INTO permissions (\`scope\`, created_at, \`name\`, display_name)
      VALUES ('ORGANIZATION', NOW(), 'MANAGE_VOTES', '投票'), ('TEAM', NOW(), 'VIEW_ATTENDANCE', '出欠');`))
      .toEqual(['ORGANIZATION:MANAGE_VOTES', 'TEAM:VIEW_ATTENDANCE'])
  })

  it('1ファイルに複数文があっても全部拾う', () => {
    expect(pairs(`INSERT INTO permissions (name, scope) VALUES ('A1', 'TEAM');
      UPDATE something SET x = 1;
      INSERT INTO permissions (name, scope) VALUES ('B1', 'ORGANIZATION');`)).toEqual(['TEAM:A1', 'ORGANIZATION:B1'])
  })
})

describe('flywayPermissions 自己検証（陰性 AC-ALIC2-06）', () => {
  it('行コメント・ブロックコメント中の INSERT は拾わない', () => {
    expect(pairs(`-- INSERT INTO permissions (name, scope) VALUES ('COMMENTED', 'TEAM');
      /* INSERT INTO permissions (name, scope) VALUES ('BLOCK', 'TEAM'); */
      # INSERT INTO permissions (name, scope) VALUES ('HASH', 'TEAM');
      INSERT INTO permissions (name, scope) VALUES ('REAL', 'TEAM');`)).toEqual(['TEAM:REAL'])
  })

  it('role_permissions など別テーブルへの INSERT は拾わない', () => {
    expect(pairs(`INSERT INTO role_permissions (role_id, permission_id) SELECT r.id, p.id FROM roles r JOIN permissions p;
      INSERT INTO permission_group_permissions (group_id, name) VALUES (1, 'X');`)).toEqual([])
  })

  it('SET 形式は例外', () => {
    expect(() => pairs(`INSERT INTO permissions SET name = 'X', scope = 'TEAM';`)).toThrow(/未対応書式/)
  })

  it('REPLACE INTO は例外（出現数と解析文数の不一致または未対応書式）', () => {
    expect(() => pairs(`REPLACE INTO permissions (name, scope) VALUES ('X', 'TEAM');`)).toThrow()
  })

  it('name 列なしは例外', () => {
    expect(() => pairs(`INSERT INTO permissions (display_name, scope) VALUES ('x', 'TEAM');`)).toThrow(/name 列が無い/)
  })

  it('scope 列なしは例外', () => {
    expect(() => pairs(`INSERT INTO permissions (name, display_name) VALUES ('X', 'x');`)).toThrow(/scope 列が無い/)
  })

  it('scope が TEAM/ORGANIZATION/PLATFORM 以外は例外', () => {
    expect(() => pairs(`INSERT INTO permissions (name, scope) VALUES ('X', 'PERSONAL');`)).toThrow(/未知の値/)
  })

  it('値が文字列リテラルでなければ例外', () => {
    expect(() => pairs(`INSERT INTO permissions (name, scope) SELECT name, scope FROM legacy_permissions;`)).toThrow(/文字列リテラルでない/)
  })

  it('1文から0件なら例外', () => {
    expect(() => pairs(`INSERT INTO permissions (name, scope) VALUES ;`)).toThrow(/1件も抽出できなかった/)
  })

  it('INTO permissions ( の出現数と解析文数が一致しなければ例外', () => {
    expect(() => pairs(`INSERT INTO permissions (name, scope) VALUES ('X', 'TEAM');
      INSERT LOW_PRIORITY INTO permissions (name, scope) VALUES ('Y', 'TEAM');`)).toThrow(/出現数/)
  })
})
