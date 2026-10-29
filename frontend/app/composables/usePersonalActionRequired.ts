import { hasInjectionContext, inject, onScopeDispose, provide, watch, type InjectionKey } from 'vue'
import { getAuthSessionContext } from '~/composables/authSessionContext'

/**
 * 個人ダッシュボード向け「要対応」一覧取得 composable。
 *
 * GET /api/v1/dashboard/action-required を呼び出し、全スコープの要対応アイテムを返す。
 * レスポンスの snake_case を camelCase に変換して返す。
 *
 * 設計書: docs/features/F22.1_swipe_scope_dashboard/04_widgets.md §5
 */

// API レスポンスの snake_case 型
interface RawActionItem {
  item_type: string
  scope_type: string
  scope_id: number
  scope_slug: string
  scope_name: string
  item_id: string
  title: string
  circulated_at: string | null
  deadline: string | null
  starts_at: string | null
}

interface RawActionRequiredResponse {
  items: RawActionItem[]
  total_count: number
}

type ActionRequiredResponse = { data: RawActionRequiredResponse }
interface InitialActionRead {
  read: (load: () => Promise<ActionRequiredResponse>) => Promise<ActionRequiredResponse> | null
  stopJoining: () => void
}
const INITIAL_ACTION_READ_KEY: InjectionKey<InitialActionRead> = Symbol('personal-initial-action-read')

/** 個人パネル初回の同時GETだけを共有し、結果は保持しない。 */
export function provideInitialPersonalActionRequired() {
  if (import.meta.server) return
  const auth = useAuthStore()
  const proxy = useProxyDeskStore()
  const guardian = useGuardianshipSwitchStore()
  const impersonation = useAdminImpersonationStore()
  const normalMode = () => !proxy.isPinned && !guardian.isActingAs && !impersonation.isImpersonating
  if (!auth.user || !normalMode()) return
  const session = getAuthSessionContext(auth)
  const owner = session.capture()
  let disposed = false
  let joinable = true
  let flight: Promise<ActionRequiredResponse> | null = null
  const current = () => !disposed && session.current(owner) && normalMode()
  const stopJoining = () => { joinable = false }
  const dispose = () => { disposed = true; stopJoining(); flight = null }
  watch([() => proxy.isPinned, () => guardian.isActingAs, () => impersonation.isImpersonating], dispose, { flush: 'sync' })
  onScopeDispose(dispose)
  provide(INITIAL_ACTION_READ_KEY, {
    stopJoining,
    read(load) {
      if (!joinable || !current()) return null
      if (!flight) {
        const pending = load().then(response => {
          if (!current()) throw new Error('PERSONAL_ACTION_SESSION_CHANGED')
          return response
        }).finally(() => {
          if (flight === pending) flight = null
        })
        flight = pending
      }
      return flight
    },
  })
  return stopJoining
}

// camelCase に変換した型
export interface PersonalActionItem {
  itemType: string // 'CIRCULATION' | 'SURVEY' | 'ATTENDANCE'
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: number
  scopeSlug: string
  scopeName: string
  itemId: string
  title: string
  circulatedAt: string | null
  deadline: string | null
  startsAt: string | null
}

export function usePersonalActionRequired() {
  const api = useApi()
  const shared = hasInjectionContext() ? inject(INITIAL_ACTION_READ_KEY, null) : null
  if (shared) onScopeDispose(shared.stopJoining)

  async function fetchActionRequired(options: { shareInitial?: boolean } = {}): Promise<{ items: PersonalActionItem[]; totalCount: number }> {
    if (!options.shareInitial) shared?.stopJoining()
    const load = () => api<ActionRequiredResponse>('/api/v1/dashboard/action-required')
    const res = await (options.shareInitial ? shared?.read(load) ?? load() : load())
    return {
      items: res.data.items.map(i => ({
        itemType: i.item_type,
        scopeType: i.scope_type as 'TEAM' | 'ORGANIZATION',
        scopeId: i.scope_id,
        scopeSlug: i.scope_slug,
        scopeName: i.scope_name,
        itemId: i.item_id,
        title: i.title,
        circulatedAt: i.circulated_at,
        deadline: i.deadline,
        startsAt: i.starts_at,
      })),
      totalCount: res.data.total_count,
    }
  }

  return { fetchActionRequired }
}
