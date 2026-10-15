/**
 * useScopeAutoRail のユニットテスト — サイドバー化 Phase2（検分差し戻し対応）
 *
 * 受け入れ条件:
 * - AC-14: スコープ（チーム/組織）配下ルートでのみ自動レール収縮する
 *   - 永続シェル（pages/teams/[slug].vue / organizations/[slug].vue → ScopePageShell）の
 *     タブ（/teams/{slug} 等）も対象（差し戻し2の根治）
 *   - 静的ルート（/teams/search・/teams 一覧・/public/teams/{slug}）や PERSONAL 系
 *     ルートでは発火しない
 * - スコープ進入/タブ遷移/離脱/個人復帰で forceRail・scopeExpanded が正しく遷移する
 */
import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { isScopeRailRoute } from '~/composables/useScopeAutoRail'
import { useAppShellStore } from '~/stores/useAppShellStore'

/** vue-router の matched レコード相当を path のみで作るヘルパ */
function matched(...paths: string[]): Array<{ path: string }> {
  return paths.map(path => ({ path }))
}

describe('isScopeRailRoute — スコープ配下ルート判定（単一の判定源）', () => {
  describe('スコープ配下 → true', () => {
    it('チーム永続シェルのルート（親レコード /teams/:slug()）', () => {
      expect(isScopeRailRoute(matched('/teams/:slug()'))).toBe(true)
    })

    it('チーム永続シェルのタブ子ルート（親＋子レコード）', () => {
      expect(isScopeRailRoute(matched('/teams/:slug()', '/teams/:slug()/info'))).toBe(true)
    })

    it('layout:team の深い子ルート（/teams/:slug()/advertiser/...）', () => {
      expect(isScopeRailRoute(matched('/teams/:slug()', '/teams/:slug()/advertiser/campaigns/:campaignId()'))).toBe(true)
    })

    it('組織永続シェルのルート（親レコード /organizations/:slug()）', () => {
      expect(isScopeRailRoute(matched('/organizations/:slug()'))).toBe(true)
    })

    it('組織のタブ子ルート', () => {
      expect(isScopeRailRoute(matched('/organizations/:slug()', '/organizations/:slug()/members'))).toBe(true)
    })

    it('Nuxt バージョン差（カスタム正規表現括弧なし :slug 形式）も許容する', () => {
      expect(isScopeRailRoute(matched('/teams/:slug'))).toBe(true)
      expect(isScopeRailRoute(matched('/organizations/:slug', '/organizations/:slug/info'))).toBe(true)
    })
  })

  describe('スコープ外 → false（誤発火しない）', () => {
    it('チーム検索（静的ルート /teams/search）', () => {
      expect(isScopeRailRoute(matched('/teams/search'))).toBe(false)
    })

    it('チーム一覧（/teams）', () => {
      expect(isScopeRailRoute(matched('/teams'))).toBe(false)
    })

    it('公開チーム詳細（/public/teams/:slug() は未ログイン公開ページでスコープ外）', () => {
      expect(isScopeRailRoute(matched('/public/teams/:slug()'))).toBe(false)
    })

    it('PERSONAL 系ルート（/dashboard・/my/shift・/inbox）', () => {
      expect(isScopeRailRoute(matched('/dashboard'))).toBe(false)
      expect(isScopeRailRoute(matched('/my/shift'))).toBe(false)
      expect(isScopeRailRoute(matched('/inbox'))).toBe(false)
    })

    it('パラメータ名が slug 以外（誤前方一致しない）', () => {
      expect(isScopeRailRoute(matched('/teams/:slugFoo()'))).toBe(false)
    })

    it('matched が空でも例外を投げず false', () => {
      expect(isScopeRailRoute([])).toBe(false)
    })
  })
})

describe('AC-14: forceRail 遷移 — スコープ進入/タブ遷移/離脱/個人復帰（route watcher 相当の駆動）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  /** useScopeAutoRail の watcher 本体と同じ書き込み（単一の判定源の写像） */
  function navigate(store: ReturnType<typeof useAppShellStore>, ...paths: string[]) {
    store.setForceRail(isScopeRailRoute(matched(...paths)))
  }

  it('個人ページ → チームシェル進入で forceRail=true・isRail=true', () => {
    const store = useAppShellStore()
    navigate(store, '/dashboard')
    expect(store.forceRail).toBe(false)

    navigate(store, '/teams/:slug()')
    expect(store.forceRail).toBe(true)
    expect(store.isRail).toBe(true)
  })

  it('シェルタブ間遷移では forceRail=true を維持し、一時展開（scopeExpanded）はリセットされる', () => {
    const store = useAppShellStore()
    navigate(store, '/teams/:slug()')
    store.toggleScopeExpanded() // ユーザーが一時展開
    expect(store.isRail).toBe(false)

    // タブ遷移（/teams/x → /teams/x/info）: 自動収縮が既定に戻る
    navigate(store, '/teams/:slug()', '/teams/:slug()/info')
    expect(store.forceRail).toBe(true)
    expect(store.scopeExpanded).toBe(false)
    expect(store.isRail).toBe(true)
  })

  it('スコープ間遷移（チーム → 組織）でも forceRail=true を維持する', () => {
    const store = useAppShellStore()
    navigate(store, '/teams/:slug()', '/teams/:slug()/announcements')
    expect(store.forceRail).toBe(true)

    navigate(store, '/organizations/:slug()', '/organizations/:slug()/budget')
    expect(store.forceRail).toBe(true)
    expect(store.isRail).toBe(true)
  })

  it('スコープ離脱（個人ページ復帰）で forceRail=false・userCollapsed の記憶が復元される', () => {
    const store = useAppShellStore()
    store.setUserCollapsed(true) // 個人ページの手動記憶=レール

    navigate(store, '/teams/:slug()')
    expect(store.isRail).toBe(true)

    navigate(store, '/dashboard')
    expect(store.forceRail).toBe(false)
    expect(store.isRail).toBe(true) // userCollapsed の記憶が勝つ

    store.setUserCollapsed(false)
    navigate(store, '/teams/:slug()')
    navigate(store, '/dashboard')
    expect(store.isRail).toBe(false) // 展開の記憶も正しく復元
  })

  it('スコープ内で一時展開したまま個人ページへ復帰しても展開状態が漏れない', () => {
    const store = useAppShellStore()
    navigate(store, '/teams/:slug()')
    store.toggleScopeExpanded()
    expect(store.scopeExpanded).toBe(true)

    navigate(store, '/dashboard')
    expect(store.forceRail).toBe(false)
    expect(store.scopeExpanded).toBe(false) // setForceRail(false) がリセット
    expect(store.isRail).toBe(false)
  })
})

describe('AC-5: closeMobileDrawer — ルート遷移でモバイルDrawerを閉じる（AppShell の watcher が呼ぶアクション）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('開いている Drawer が closeMobileDrawer で閉じる', () => {
    const store = useAppShellStore()
    store.openMobileDrawer()
    expect(store.mobileDrawerOpen).toBe(true)
    store.closeMobileDrawer()
    expect(store.mobileDrawerOpen).toBe(false)
  })

  it('閉じている状態で呼んでも閉じたまま（冪等）', () => {
    const store = useAppShellStore()
    store.closeMobileDrawer()
    expect(store.mobileDrawerOpen).toBe(false)
  })
})
