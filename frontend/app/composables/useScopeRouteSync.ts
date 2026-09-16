/**
 * ルート（URL）を正本として {@code useScopeStore} の現在スコープを同期する。
 *
 * <p># なぜ必要か（CMP-260907-0850）</p>
 * <p>従来、現在スコープを書き込むコードはアプリ全体で {@code ScopeSelector.vue} だけだったが、
 * その ScopeSelector はどこからも使われていなかった。ヘッダーで実際に使われている
 * {@code Navigation/ScopeNavDropdown.vue} は {@code router.push} するだけでストアに触れないため、
 * 実利用者の現在スコープは既定の「個人」から一生変わらず、スコープで閉じた画面
 * （{@code /admin/receipts} 等）が恒久的に使えなかった。</p>
 *
 * <p># 方式</p>
 * <p>「どのチーム／組織のページに居るか」（＝URL）を現在スコープの正本とする。
 * {@code /teams/{slug}} 配下に入れば team スコープ、{@code /organizations/{slug}} 配下に入れば
 * organization スコープへ切り替える。ドロップダウンからのジャンプ・URL 直打ち・他画面からの
 * リンクのいずれでも同じ経路を通るため、切替 UI を増やさずに全経路が揃う。</p>
 *
 * <p>スコープ外のパス（{@code /admin/*} など）へ移っても現在スコープは解除しない。
 * {@code /admin/receipts} 等は「直前に見ていた団体」で動くことが期待される画面であり、
 * 解除するとスコープが即座に個人へ戻って元の欠陥に逆戻りするため。</p>
 *
 * <p>所属していないスコープ（自分の一覧に無い slug ＝ 公開ページの閲覧など）では
 * 何もしない。権限の無いスコープを現在スコープに据えると、以後の管理画面が
 * 403 を返し続けるだけで利用者の役に立たないため。</p>
 */

/** ルートから解決したスコープ種別と slug。 */
export interface ParsedScopeRoute {
  scopeType: 'team' | 'organization'
  slug: string
}

/**
 * {@code /teams/{slug}} / {@code /organizations/{slug}} 配下のパスなら
 * スコープ種別と slug を返す。それ以外は null。
 *
 * <p>{@code /teams}（ハブ）や {@code /teams/search}（検索ページ）は
 * 個別スコープではないので null を返す。</p>
 */
const RESERVED_SLUGS = new Set(['search', 'new'])

export function parseScopeRoute(path: string): ParsedScopeRoute | null {
  const pathname = path.split('?')[0]?.split('#')[0] ?? ''
  const segments = pathname.split('/').filter(Boolean)
  const head = segments[0]
  const slug = segments[1]
  if (!slug || RESERVED_SLUGS.has(slug)) return null
  if (head === 'teams') return { scopeType: 'team', slug }
  if (head === 'organizations') return { scopeType: 'organization', slug }
  return null
}

export function useScopeRouteSync() {
  const scopeStore = useScopeStore()
  const teamStore = useTeamStore()
  const orgStore = useOrganizationStore()

  // 所属一覧のフェッチはセッション中 1 度だけ試みる。
  // 非所属スコープのページを踏むたびに /api/v1/me/teams を叩き直さないための番人。
  let teamsFetchAttempted = false
  let orgsFetchAttempted = false

  async function ensureMyTeams(): Promise<void> {
    if (teamStore.myTeams.length > 0 || teamsFetchAttempted) return
    teamsFetchAttempted = true
    await teamStore.fetchMyTeams()
  }

  async function ensureMyOrganizations(): Promise<void> {
    if (orgStore.myOrganizations.length > 0 || orgsFetchAttempted) return
    orgsFetchAttempted = true
    await orgStore.fetchMyOrganizations()
  }

  /**
   * パスから現在スコープを同期する。切り替えた場合のみ true。
   */
  async function syncFromPath(path: string): Promise<boolean> {
    const parsed = parseScopeRoute(path)
    if (!parsed) return false

    if (parsed.scopeType === 'team') {
      await ensureMyTeams()
      const team = teamStore.myTeams.find(t => t.slug === parsed.slug)
      if (!team) return false
      if (scopeStore.current.type === 'team' && scopeStore.current.id === String(team.id)) return false
      scopeStore.setTeamScope(team.id, team.nickname1 || team.name)
      return true
    }

    await ensureMyOrganizations()
    const org = orgStore.myOrganizations.find(o => o.slug === parsed.slug)
    if (!org) return false
    if (scopeStore.current.type === 'organization' && scopeStore.current.id === String(org.id)) return false
    scopeStore.setOrganizationScope(org.id, org.nickname1 || org.name)
    return true
  }

  return { syncFromPath }
}
