import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { effectScope, nextTick, reactive, ref, type EffectScope } from 'vue'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { useReservationTab } from './useReservationTab'

const mocks = vi.hoisted(() => ({
  route: null as unknown as RouteLocationNormalizedLoaded,
  replace: vi.fn(),
}))
// Nuxt自身の初期化には本物を渡し、テスト中もrouterの他メソッドを保つ。
mockNuxtImport('useRoute', (original) => () => mocks.route ?? original())
mockNuxtImport(
  'useRouter',
  (original) => () => (mocks.route ? { ...original(), replace: mocks.replace } : original()),
)

let scope: EffectScope
beforeEach(() => {
  mocks.route = reactive({
    path: '/teams/team-a/reservations',
    params: { slug: 'team-a' },
    query: {},
    hash: '#history',
  }) as unknown as RouteLocationNormalizedLoaded
  mocks.replace.mockReset().mockResolvedValue(undefined)
  scope = effectScope()
})
afterEach(() => {
  scope.stop()
  mocks.route = null as unknown as RouteLocationNormalizedLoaded
})

function setup(management = false, permissionsResolved = true) {
  const allowed = ref(management)
  const resolved = ref(permissionsResolved)
  const activeTab = scope.run(() => useReservationTab(allowed, resolved))!
  return { activeTab, allowed, resolved }
}

describe('useReservationTab', () => {
  it.each([
    ['book', 0],
    ['list', 1],
  ] as const)('AC-7: 未確定・失敗・nullロールでも%sは有効', (tab, expected) => {
    mocks.route.query = { tab }
    const { activeTab } = setup(false, false)
    expect(activeTab.value).toBe(expected)
    expect(mocks.replace).not.toHaveBeenCalled()
  })

  it.each(['/teams/team-a/reservations', '/teams/team-a/admin/reservations'])(
    'AC-6: %s のqueryから選択を復元して以降のquery変更にも追従する',
    (path) => {
      mocks.route.path = path
      mocks.route.query = { tab: 'list' }
      const { activeTab } = setup()
      expect(activeTab.value).toBe(1)
      mocks.route.query = { tab: 'book' }
      expect(activeTab.value).toBe(0)
      mocks.route.query = { tab: 'list' }
      expect(activeTab.value).toBe(1)
      expect(mocks.replace).not.toHaveBeenCalled()
    },
  )

  it('AC-6: 他queryとhashを保持してreplaceし、同値選択は書き換えない', () => {
    mocks.route.query = { source: 'dashboard' }
    const { activeTab } = setup()
    activeTab.value = 0
    expect(mocks.replace).not.toHaveBeenCalled()
    activeTab.value = 1
    expect(mocks.replace).toHaveBeenCalledExactlyOnceWith({
      path: '/teams/team-a/reservations',
      query: { source: 'dashboard', tab: 'list' },
      hash: '#history',
    })
    mocks.route.query = { source: 'dashboard', tab: 'list' }
    activeTab.value = 1
    expect(mocks.replace).toHaveBeenCalledTimes(1)
  })

  it.each(
    [undefined, null, '', 'unknown', '1', ['list'], ['manage', 'book']].map((tab) => ({ tab })),
  )('AC-7: 無効query $tab は安全な既定表示で不要な正規化をしない', ({ tab }) => {
    mocks.route.query = tab === undefined ? {} : { tab }
    const { activeTab } = setup()
    expect(activeTab.value).toBe(0)
    expect(mocks.replace).not.toHaveBeenCalled()
  })

  it.each(['manage', 'closure'])(
    'AC-7: 権限確定前・失敗時は%sを破壊せず成功後に復元する',
    async (tab) => {
      mocks.route.query = { tab }
      const { activeTab, allowed, resolved } = setup(false, false)
      expect(activeTab.value).toBe(0)
      expect(mocks.replace).not.toHaveBeenCalled()
      // 取得失敗は未確定のまま。再試行の成功で元の選択を復元する。
      await nextTick()
      expect(mocks.replace).not.toHaveBeenCalled()
      allowed.value = true
      resolved.value = true
      await nextTick()
      expect(activeTab.value).toBe(tab === 'manage' ? 2 : 3)
      expect(mocks.replace).not.toHaveBeenCalled()
    },
  )

  it.each(['manage', 'closure'])('AC-7: 確定した非管理者の%sを予約タブへ戻す', (tab) => {
    mocks.route.query = { tab }
    const { activeTab } = setup()
    expect(activeTab.value).toBe(0)
    expect(mocks.replace).toHaveBeenCalledExactlyOnceWith({
      path: mocks.route.path,
      query: { tab: 'book' },
      hash: '#history',
    })
  })

  it('AC-7: 権限失効・管理レンズOFFの後は管理タブを残さない', async () => {
    mocks.route.query = { tab: 'manage' }
    const { activeTab, allowed } = setup(true)
    expect(activeTab.value).toBe(2)
    allowed.value = false
    expect(activeTab.value).toBe(0)
    await nextTick()
    expect(mocks.replace).toHaveBeenCalledOnce()
    activeTab.value = 3
    expect(mocks.replace).toHaveBeenCalledOnce()
  })

  it.each(['/teams/team-a/info', '/teams/team-b/reservations'])(
    'AC-8: 遷移先%sへ旧パネルの同期を書き戻さない',
    async (path) => {
      mocks.route.query = { tab: 'manage' }
      const { activeTab, allowed } = setup(true)
      mocks.route.path = path
      allowed.value = false
      activeTab.value = 1
      await nextTick()
      expect(mocks.replace).not.toHaveBeenCalled()
    },
  )

  it('AC-8: 同一pathでも別slugの状態には書き戻さない', () => {
    const { activeTab } = setup()
    mocks.route.params = { slug: 'team-b' }
    activeTab.value = 1
    expect(mocks.replace).not.toHaveBeenCalled()
  })
})
