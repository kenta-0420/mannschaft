// @vitest-environment happy-dom
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  BUDGET_ADMIN_PERMISSION,
  hasBudgetAdminPermission,
  resolveOrganizationSlug,
} from './useShiftBudgetAdminAccess'

/**
 * 予算管理画面の操作導線の出し分け（CMP-260913-1251）。
 *
 * <p>壊れていたこと: `/admin/shift-budget/*` の操作ボタン（月次締め実行・割当の作成/編集/削除・
 * 警告の承認応答・失敗イベントの再実行/手動補正済）が、BUDGET_ADMIN を持たない一般 MEMBER にも
 * 他テナントのユーザーにも表示されていた。押すと BE が 403 を返し「失敗しました」とだけ出る。
 * BE のガード（Service 層の BUDGET_ADMIN チェック）は正しく効いており、これは表示の作法の問題。</p>
 *
 * <p>ここでは「誰に出すか」の判定そのもの（純粋関数）をロール横断で検証し、
 * 併せて各画面がその判定を実際に v-if に結線しているかをソース契約として検証する。</p>
 */
describe('予算管理操作の可視判定（ロール横断）', () => {
  const organizations = [
    { id: 10, slug: 'acme' },
    { id: 20, slug: 'globex' },
  ]

  describe('resolveOrganizationSlug', () => {
    it('組織スコープなら自分の所属組織から slug を解決する', () => {
      expect(resolveOrganizationSlug({ type: 'organization', id: '10' }, organizations)).toBe('acme')
    })

    it('他テナントの組織 ID（自分が所属していない）なら null', () => {
      expect(resolveOrganizationSlug({ type: 'organization', id: '99' }, organizations)).toBeNull()
    })

    it('個人スコープ・チームスコープでは null（組織の予算権限は存在しない）', () => {
      expect(resolveOrganizationSlug({ type: 'personal', id: null }, organizations)).toBeNull()
      expect(resolveOrganizationSlug({ type: 'team', id: '10' }, organizations)).toBeNull()
    })

    it('所属組織が未取得（空配列）なら null', () => {
      expect(resolveOrganizationSlug({ type: 'organization', id: '10' }, [])).toBeNull()
    })
  })

  describe('hasBudgetAdminPermission', () => {
    // BE の /me/permissions が返す実際の権限名に合わせた検体。
    // ADMIN は BUDGET_ADMIN を持ち、一般 MEMBER は V11.034 で BUDGET_VIEW のみ自動付与される。
    const adminPermissions = ['BUDGET_VIEW', BUDGET_ADMIN_PERMISSION, 'SHIFT_VIEW']
    const memberPermissions = ['BUDGET_VIEW', 'SHIFT_VIEW']

    it('権限あり（BUDGET_ADMIN 保有）なら true', () => {
      expect(hasBudgetAdminPermission('acme', adminPermissions)).toBe(true)
    })

    it('権限なし（BUDGET_VIEW のみの一般 MEMBER）なら false', () => {
      expect(hasBudgetAdminPermission('acme', memberPermissions)).toBe(false)
    })

    it('他テナント（slug 未解決）なら権限リストに関わらず false', () => {
      expect(hasBudgetAdminPermission(null, adminPermissions)).toBe(false)
    })

    it('権限取得に失敗した場合（空配列）は false（見せて弾かれるより安全側）', () => {
      expect(hasBudgetAdminPermission('acme', [])).toBe(false)
    })
  })
})

/** 実際に動くコード部分（コメントを除いたソース）。 */
function codeOf(relativePath: string): string {
  return readFileSync(resolve(process.cwd(), relativePath), 'utf8')
    .replace(/\r\n/g, '\n')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/^\s*\/\/.*$/gm, '')
    .replace(/<!--[\s\S]*?-->/g, '')
}

describe('予算管理画面が可視判定を結線していること', () => {
  const pages = [
    'app/pages/admin/shift-budget/dashboard.vue',
    'app/pages/admin/shift-budget/allocations.vue',
    'app/pages/admin/shift-budget/alerts.vue',
    'app/pages/admin/shift-budget/failed-events.vue',
  ]

  it.each(pages)('%s は useShiftBudgetAdminAccess を使う', (page) => {
    expect(codeOf(page)).toContain('useShiftBudgetAdminAccess()')
  })

  it('月次締め実行ボタンは canManageBudget で出し分ける', () => {
    const code = codeOf('app/pages/admin/shift-budget/dashboard.vue')
    expect(code).toMatch(/v-if="organizationId && canManageBudget"/)
  })

  it('割当の作成・編集・削除ボタンは canManageBudget で出し分ける', () => {
    const code = codeOf('app/pages/admin/shift-budget/allocations.vue')
    expect(code).toMatch(/v-if="organizationId && canManageBudget"/)
    // 行内の編集・削除ボタンも同様に隠す（操作列そのものを出さない）
    expect(code).toMatch(/v-if="canManageBudget"/)
  })

  it('警告の承認応答は canManageBudget を渡す（true 直書きをやめる）', () => {
    const code = codeOf('app/pages/admin/shift-budget/alerts.vue')
    expect(code).toContain(':can-acknowledge="canManageBudget"')
    expect(code).not.toContain(':can-acknowledge="true"')
  })

  it('失敗イベントの再実行・手動補正済ボタンは canManageBudget で出し分ける', () => {
    const code = codeOf('app/pages/admin/shift-budget/failed-events.vue')
    expect(code).toMatch(/v-if="canManageBudget"/)
  })
})
