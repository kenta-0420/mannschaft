import type { GlobalNavItem, SidebarGroup } from '~/types/nav'
import { NAV_GROUP_LABEL_KEYS, NAV_GROUP_ORDER, resolveNavGroup } from '~/constants/navGroups'

/**
 * サイドバー化 Phase1: navSettingsStore.visibleFeatures ＋ 固定/条件付き項目を
 * navGroups 定義でグループ化した SidebarGroup[] へ射影する。
 *
 * 現 default.vue 108-131行のロジック（固定ダッシュボード・代理入力デスク・SYSTEM・同期の
 * 合流条件）をそのまま移植する。挙動は1ビットも変えない（表示条件・パスは既存と同一）。
 * 追加の API 呼び出しは行わない（各ストアは既存プラグイン/認証フローで既にフェッチ済み）。
 *
 * Phase2 AC-21: 受信箱バッジ — inboxStore.inboxCount（layouts/default.vue が既に60秒間隔で
 * fetchSummary() をポーリング済み）を 'inbox' キーの項目に結線する。追加のポーリングは発生しない。
 * チャット未読・TODO残数は、全ページ共通で参照できるグローバルなカウント源が現状存在しない
 * （chatStore/todoStore とも特定ページ限定のフェッチのみ）ため、Phase2 では未結線のまま据え置く。
 */
export function useAppNavGroups() {
  const navSettingsStore = useNavSettingsStore()
  const teamStore = useTeamStore()
  const authStore = useAuthStore()
  const syncStore = useSyncStore()
  const inboxStore = useInboxStore()

  /** NEIGHBORHOOD/CONDO テンプレートかつ DEPUTY_ADMIN 以上のチームが1つでもあれば表示 */
  const showProxyDeskNav = computed(() =>
    teamStore.myTeams.some(
      team =>
        (team.template === 'NEIGHBORHOOD' || team.template === 'CONDO')
        && (team.role === 'ADMIN' || team.role === 'SYSTEM_ADMIN' || team.role === 'DEPUTY_ADMIN'),
    ),
  )

  /** 未解決コンフリクトがある場合のみ「同期」ナビを表示 */
  const showSyncNav = computed(() => syncStore.hasConflicts)

  /** 固定＋動的＋条件付き項目をこの順で合流させたフラットな配列 */
  const items = computed<GlobalNavItem[]>(() => {
    const list: GlobalNavItem[] = []

    // 固定: ダッシュボード（先頭・home グループ）
    list.push({
      key: 'dashboard',
      labelKey: 'global_nav.item.dashboard',
      icon: 'pi pi-home',
      path: '/dashboard',
    })

    // ナビ設定ストアの表示項目（BE 側で表示順ソート済み。配列順をそのまま尊重する）
    for (const feature of navSettingsStore.visibleFeatures) {
      list.push({
        key: feature.key,
        labelKey: feature.labelKey,
        icon: feature.icon,
        path: feature.path,
        // Phase2 AC-21: 受信箱のみ既存のグローバルポーリング元（inboxStore）と結線する
        badgeCount: feature.key === 'inbox' ? inboxStore.inboxCount : undefined,
      })
    }

    // 代理入力デスク（NEIGHBORHOOD/CONDO かつ DEPUTY_ADMIN 以上のみ）
    if (showProxyDeskNav.value) {
      list.push({
        key: 'proxy-desk',
        labelKey: 'proxy.title',
        icon: 'pi pi-tablet',
        path: '/admin/proxy-desk',
        variant: 'admin',
      })
    }

    // SYSTEM（システム管理者のみ）
    if (authStore.isSystemAdmin) {
      list.push({
        key: 'system-admin',
        labelKey: 'global_nav.item.systemAdmin',
        icon: 'pi pi-shield',
        path: '/system-admin',
        variant: 'admin',
      })
    }

    // 同期（未解決コンフリクトがある場合のみ・件数バッジ付き）
    if (showSyncNav.value) {
      list.push({
        key: 'sync',
        labelKey: 'sync.nav_label',
        icon: 'pi pi-sync',
        path: '/sync/conflicts',
        badgeCount: syncStore.conflictCount,
      })
    }

    return list
  })

  /** グループ見出し順に振り分け。空グループは出力から除外する */
  const groups = computed<SidebarGroup[]>(() => {
    const byGroup = new Map<string, GlobalNavItem[]>()
    for (const item of items.value) {
      const groupKey = resolveNavGroup(item.key)
      const bucket = byGroup.get(groupKey)
      if (bucket) {
        bucket.push(item)
      } else {
        byGroup.set(groupKey, [item])
      }
    }

    return NAV_GROUP_ORDER
      .filter(groupKey => (byGroup.get(groupKey)?.length ?? 0) > 0)
      .map(groupKey => ({
        key: groupKey,
        labelKey: NAV_GROUP_LABEL_KEYS[groupKey],
        items: byGroup.get(groupKey) ?? [],
      }))
  })

  /** 現在ルートが完全一致しているか判定するための全パス一覧（default.vue の allNavPaths と同義） */
  const allPaths = computed(() => items.value.map(item => item.path))

  return { groups, items, allPaths, showProxyDeskNav, showSyncNav }
}
