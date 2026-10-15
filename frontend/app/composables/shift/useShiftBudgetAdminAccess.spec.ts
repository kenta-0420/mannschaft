// @vitest-environment happy-dom
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  BUDGET_ADMIN_PERMISSION,
  canManageBudgetWith,
  createBudgetPermissionState,
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

/**
 * 組織スコープを切り替えたときの権限の追随（Codex 検分 P2）。
 *
 * <p>壊れていたこと: 権限の取得結果を「どの組織のものか」と紐付けずに保持していたため、
 * 組織を切り替えた直後は切替前の組織の権限で判定していた。旧組織では BUDGET_ADMIN、
 * 新組織では一般 MEMBER という利用者に、新しい取得が終わるまで管理ボタンが見えていた。
 * さらに切替前後のリクエストが並行したとき、遅れて返った旧組織のレスポンスが後から
 * 書き込まれ、古い権限が居座り続けた。</p>
 *
 * <p>BE の認可は維持されているためデータは漏れないが、この修正の目的そのものが
 * 「権限の無い利用者に導線を見せないこと」なので、見せる窓が残っていては目的を達しない。</p>
 */
describe('組織切替時の権限の追随', () => {
  /** 解決時期を呼び出し側で決められる fetch（並行・順序逆転を検体にするため）。 */
  function deferredFetcher() {
    const pending = new Map<string, (permissions: string[] | null) => void>()
    const fetchPermissions = (slug: string): Promise<string[] | null> =>
      new Promise((resolvePromise) => {
        pending.set(slug, resolvePromise)
      })
    const settle = (slug: string, permissions: string[] | null) => {
      const resolvePromise = pending.get(slug)
      if (!resolvePromise) throw new Error(`${slug} は要求されていない`)
      pending.delete(slug)
      resolvePromise(permissions)
    }
    return { fetchPermissions, settle }
  }

  const adminPermissions = ['BUDGET_VIEW', BUDGET_ADMIN_PERMISSION]
  const memberPermissions = ['BUDGET_VIEW']

  it('組織を切り替えたら新しい組織の権限で判定する（旧組織の値が残らない）', async () => {
    const { fetchPermissions, settle } = deferredFetcher()
    const state = createBudgetPermissionState(fetchPermissions)

    const first = state.load('acme')
    settle('acme', adminPermissions)
    await first
    expect(canManageBudgetWith('acme', state.granted.value)).toBe(true)

    const second = state.load('globex')
    settle('globex', memberPermissions)
    await second
    expect(canManageBudgetWith('globex', state.granted.value)).toBe(false)
  })

  it('切替直後、新しい組織の権限が届くまでは導線を出さない（出してから消さない）', async () => {
    const { fetchPermissions, settle } = deferredFetcher()
    const state = createBudgetPermissionState(fetchPermissions)

    const first = state.load('acme')
    settle('acme', adminPermissions)
    await first

    // 取得はまだ終わっていない（await していない）
    const second = state.load('globex')
    expect(state.granted.value).toBeNull()
    expect(canManageBudgetWith('globex', state.granted.value)).toBe(false)

    settle('globex', adminPermissions)
    await second
    expect(canManageBudgetWith('globex', state.granted.value)).toBe(true)
  })

  it('保持した権限は組織に紐付く（別組織の判定には使われない）', async () => {
    const { fetchPermissions, settle } = deferredFetcher()
    const state = createBudgetPermissionState(fetchPermissions)

    const first = state.load('acme')
    settle('acme', adminPermissions)
    await first

    expect(canManageBudgetWith('acme', state.granted.value)).toBe(true)
    // 表示中のスコープが別組織に変わっていれば、保持している権限は使えない
    expect(canManageBudgetWith('globex', state.granted.value)).toBe(false)
  })

  it('遅れて返った古いレスポンスは新しい結果を上書きしない（先に投げたものが後に返る）', async () => {
    const { fetchPermissions, settle } = deferredFetcher()
    const state = createBudgetPermissionState(fetchPermissions)

    // 旧組織（BUDGET_ADMIN 保有）の取得を投げたまま、新組織へ切り替える
    const stale = state.load('acme')
    const fresh = state.load('globex')

    // 新組織の結果が先に返る
    settle('globex', memberPermissions)
    await fresh
    expect(canManageBudgetWith('globex', state.granted.value)).toBe(false)

    // 旧組織の結果が後から返っても、新しい結果を上書きしない
    settle('acme', adminPermissions)
    await stale
    expect(state.granted.value).toEqual({ slug: 'globex', permissions: memberPermissions })
    expect(canManageBudgetWith('globex', state.granted.value)).toBe(false)
  })

  it('権限の取得に失敗した場合は導線を出さない（失敗を成功に見せかけない）', async () => {
    const { fetchPermissions, settle } = deferredFetcher()
    const state = createBudgetPermissionState(fetchPermissions)

    const loading = state.load('acme')
    settle('acme', null)
    await loading

    expect(state.granted.value).toBeNull()
    expect(canManageBudgetWith('acme', state.granted.value)).toBe(false)
  })

  it('スコープが組織でない（slug が null）なら取得せず false', async () => {
    const { fetchPermissions } = deferredFetcher()
    const state = createBudgetPermissionState(fetchPermissions)

    await state.load(null)

    expect(state.granted.value).toBeNull()
    expect(canManageBudgetWith(null, state.granted.value)).toBe(false)
  })
})
