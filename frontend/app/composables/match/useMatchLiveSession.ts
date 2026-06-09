/**
 * F08.10 ライブ記録セッションの結線 composable（04_frontend_and_ux.md §G.2）。
 *
 * live.vue を「薄いオーケストレータ」に保つため、sender（オンライン/オフライン切替）・recorder・
 * スコア導出・PERIOD_* 自動記録・オフライン flush の結線をここに集約する。
 * UI（スコアボード/ボタン/シート/タイムライン）は live.vue 側、状態とロジックは本 composable と
 * 各専用 composable（useMatchTimer/useMatchLiveRecorder/useMatchOfflineQueue 等）に委譲する。
 */
import type {
  CardReasonCode,
  MatchEventRequest,
  MatchEventResponse,
  MatchEventType,
} from '~/types/match'
import { useMatchLiveRecorder, type RecorderPlayer } from '~/composables/match/useMatchLiveRecorder'
import { useMatchTimer, type PeriodTransition } from '~/composables/match/useMatchTimer'

export interface MatchLiveSessionContext {
  orgId: Ref<number | null>
  matchId: string
  ownTeamSide: Ref<'HOME' | 'AWAY'>
}

export function useMatchLiveSession(sessionCtx: MatchLiveSessionContext) {
  const eventApi = useMatchEventApi()
  const offlineQueue = useMatchOfflineQueue()
  const notification = useNotification()
  const { t } = useI18n()

  const { orgId, matchId, ownTeamSide } = sessionCtx

  // === 導出スコア（イベント由来） ===
  const homeScore = ref(0)
  const awayScore = ref(0)

  function applyDerivedScore(res: { derivedHomeScore?: number; derivedAwayScore?: number }): void {
    if (typeof res.derivedHomeScore === 'number') homeScore.value = res.derivedHomeScore
    if (typeof res.derivedAwayScore === 'number') awayScore.value = res.derivedAwayScore
  }

  // === sender（オンライン直 / 失敗時オフライン退避） ===
  async function onlineSender(body: MatchEventRequest): Promise<MatchEventResponse> {
    if (orgId.value === null) throw new Error('orgId unresolved')
    return eventApi.addEvent(orgId.value, matchId, body)
  }

  async function resilientSender(body: MatchEventRequest): Promise<MatchEventResponse> {
    try {
      return await onlineSender(body)
    } catch (err) {
      if (isNetworkError(err) && orgId.value !== null) {
        const clientId = genId()
        await offlineQueue.enqueue({ orgId: orgId.value, matchId, clientId, body })
        notification.info(t('match.live.offline.queued'))
        return { ...body, id: `local-${clientId}` } as MatchEventResponse
      }
      throw err
    }
  }

  // === timer + recorder ===
  async function onPeriodTransition(tr: PeriodTransition): Promise<void> {
    if (orgId.value === null) return
    const side = ownTeamSide.value
    if (tr.endingPeriod) {
      await safeRecord({ eventType: 'PERIOD_END', period: tr.endingPeriod, teamSide: side, minute: tr.minute ?? undefined })
    }
    if (tr.startingPeriod) {
      await safeRecord({ eventType: 'PERIOD_START', period: tr.startingPeriod, teamSide: side, minute: tr.minute ?? undefined })
    }
  }
  const timer = useMatchTimer({ onPeriodTransition })

  const recorder = useMatchLiveRecorder({
    sender: resilientSender,
    deleter: async (eventId: string) => {
      if (orgId.value === null || eventId.startsWith('local-')) return
      await eventApi.deleteEvent(orgId.value, matchId, eventId)
    },
    reload: async () => {
      if (orgId.value === null) return []
      const res = await eventApi.listEvents(orgId.value, matchId)
      applyDerivedScore(res)
      return res.events ?? []
    },
    onConflict: () => notification.warn(t('match.live.conflict.title'), t('match.live.conflict.detail')),
  })

  async function safeRecord(body: MatchEventRequest): Promise<void> {
    try {
      await resilientSender(body)
    } catch {
      // sender / composable 内で通知済み
    }
  }

  /** 現在の記録文脈（ピリオド・minute・自チーム側）。 */
  function currentCtx(teamSide: 'HOME' | 'AWAY' = ownTeamSide.value) {
    const s = timer.state.value
    const period = (s === 'WAITING' || s === 'HALF_TIME')
      ? 'FIRST_HALF'
      : (s === 'COMPLETED' ? 'SECOND_HALF' : s)
    return {
      period: period as MatchEventRequest['period'],
      minute: timer.currentMinute.value,
      stoppageMinute: timer.stoppageMinute.value,
      teamSide,
    }
  }

  async function refreshScore(): Promise<void> {
    if (orgId.value === null) return
    try {
      applyDerivedScore(await eventApi.listEvents(orgId.value, matchId))
    } catch {
      // 通知済み
    }
  }

  // === 高レベル記録ハンドラ（シートからの emit を受ける） ===
  async function recordGoal(p: { scorer: RecorderPlayer; assist: RecorderPlayer | null; goalType: 'GOAL' | 'PENALTY_GOAL' | 'OWN_GOAL'; note: string | null; assistNote: string | null }): Promise<void> {
    await recorder.recordGoalWithAssist(currentCtx(), p.scorer, p.assist, p.goalType, { goalNote: p.note, assistNote: p.assistNote })
    await refreshScore()
  }
  async function recordAssist(p: { assist: RecorderPlayer; scorer: RecorderPlayer | null; note: string | null }): Promise<void> {
    await recorder.recordAssistThenGoal(currentCtx(), p.assist, p.scorer, 'GOAL', { assistNote: p.note })
    await refreshScore()
  }
  async function recordCard(p: { player: RecorderPlayer; cardType: MatchEventType; reasonCode: CardReasonCode | null; note: string | null }): Promise<void> {
    await recorder.recordSingle(p.cardType, currentCtx(), p.player, { cardReasonCode: p.reasonCode, note: p.note })
  }
  async function recordSub(p: { out: RecorderPlayer; in: RecorderPlayer }): Promise<void> {
    await recorder.recordSubstitution(currentCtx(), p.out, p.in)
  }
  async function recordOther(p: { label: string; note: string | null }): Promise<void> {
    await recorder.recordSingle('OTHER', currentCtx(), null, { customLabel: p.label, note: p.note })
  }

  // === オフライン flush（online 復帰時） ===
  async function flushOffline(): Promise<void> {
    // flushAll は path から復元した orgId/matchId を渡してくる。現セッションの addEvent に転送する。
    const results = await offlineQueue.flushAll((oid, mid, body) => eventApi.addEvent(oid, mid, body))
    const ok = results.filter((r) => r.response).length
    if (ok > 0) {
      notification.success(t('match.live.offline.flushed', { count: ok }))
      await reloadEvents()
    }
  }
  async function reloadEvents(): Promise<void> {
    if (orgId.value === null) return
    const res = await eventApi.listEvents(orgId.value, matchId)
    recorder.setEvents(res.events ?? [])
    applyDerivedScore(res)
  }

  return {
    homeScore,
    awayScore,
    timer,
    recorder,
    applyDerivedScore,
    refreshScore,
    recordGoal,
    recordAssist,
    recordCard,
    recordSub,
    recordOther,
    flushOffline,
    reloadEvents,
  }
}

// ============================================================
// ヘルパ
// ============================================================

function genId(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return crypto.randomUUID()
  return `${Date.now()}-${Math.random().toString(36).slice(2)}`
}

/** ネットワークエラー（オフライン・接続不能）か。サーバ 4xx/5xx は除く。 */
export function isNetworkError(err: unknown): boolean {
  if (typeof err !== 'object' || err === null) return false
  const e = err as { statusCode?: number; name?: string; message?: string }
  if (typeof e.statusCode === 'number') return false
  return e.name === 'FetchError' || e.name === 'TypeError' || /fetch|network/i.test(e.message ?? '')
}
