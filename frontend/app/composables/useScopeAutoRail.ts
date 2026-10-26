/**
 * サイドバー化 Phase2 AC-14: スコープ（チーム/組織）ページ滞在中の自動レール収縮を
 * 「現在ルートがスコープ配下か」という単一の判定源で管理する composable。
 *
 * # なぜ route 判定か（mount/unmount 結線にしなかった理由）
 * スコープ面は2系統ある:
 *  - layout 'team' / 'organization' のページ（pages/teams/[slug]/announcements.vue 等）
 *  - 永続シェル（pages/teams/[slug].vue / organizations/[slug].vue が ScopePageShell を
 *    マウントし、タブ子ページは SPA 遷移で親を再マウントしない）
 * 両者に onMounted/onUnmounted で setForceRail を結線すると、レイアウト切替時に
 * 「旧コンポーネントの unmount(false) が 新コンポーネントの mount(true) や
 * pre-flush の route watcher より後に走って正しい状態を上書きする」順序競合が起きうる。
 * route.matched からの導出なら書き込み元が一箇所になり、順序競合が構造的に消える。
 *
 * # PERSONAL スコープの除外
 * ScopePageShell の実マウント箇所は pages/teams/[slug].vue と
 * pages/organizations/[slug].vue の2つのみ（2026-07-15 全数調査）。PERSONAL スコープでの
 * 使用は無いが、本判定はコンポーネントではなくルート（/teams/:slug, /organizations/:slug
 * 配下）で行うため、仮に将来 PERSONAL 文脈で ScopePageShell が使われても誤発火しない。
 */

/**
 * マッチ済みルートレコード群がスコープ（チーム/組織）配下かを判定する純関数。
 *
 * 親レコード（pages/teams/[slug].vue → '/teams/:slug()'）が matched に含まれるかで判定する。
 * 静的ルート（/teams/search・/teams 一覧・/public/teams/:slug 等）は ':slug' パラメータ
 * レコードを持たないため誤マッチしない。Nuxt のバージョン差（':slug()' / ':slug'）を許容する。
 */
export function isScopeRailRoute(matched: ReadonlyArray<{ path: string }>): boolean {
  return matched.some(record => /^\/(teams|organizations)\/:slug\b/.test(record.path))
}

/**
 * 現在ルートを監視し、スコープ配下なら forceRail=true・離脱で false を維持する。
 * AppShell（新シェルのルート）の setup で1回だけ呼ぶこと。
 *
 * setForceRail は呼び出しのたび scopeExpanded（一時展開）を既定へ戻すため、
 * ルート遷移ごとに「自動収縮が既定」へリセットされる（プロトタイプ仕様）。
 */
export function useScopeAutoRail() {
  const route = useRoute()
  const appShellStore = useAppShellStore()

  watch(
    () => route.path,
    () => {
      appShellStore.setForceRail(isScopeRailRoute(route.matched))
    },
    { immediate: true },
  )
}
