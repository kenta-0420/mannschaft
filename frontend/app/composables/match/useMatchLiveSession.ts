/**
 * F08.10 ライブ記録セッションの結線 composable（04_frontend_and_ux.md §G.2）。
 *
 * live.vue を「薄いオーケストレータ」に保つため、sender（オンライン/オフライン切替）・recorder・
 * スコア導出・PERIOD_* 自動記録・オフライン flush の結線をここに集約する。
 * UI（スコアボード/ボタン/シート/タイムライン）は live.vue 側、状態とロジックは本 composable と
 * 各専用 composable（useMatchTimer/useMatchLiveRecorder/useMatchOfflineQueue 等）に委譲する。
 */
import type {
  BasketballEventType,
  BasketballFoulCode,
  CardReasonCode,
  CatalogEventType,
  MatchEventRequest,
  MatchEventResponse,
  MatchEventType,
} from '~/types/match'
import { useMatchLiveRecorder, type RecorderPlayer } from '~/composables/match/useMatchLiveRecorder'
import { useMatchTimer, type PeriodTransition } from '~/composables/match/useMatchTimer'
import type { SportTimer } from '~/composables/match/sport/sportModuleRegistry'
import type { UseMatchTimerCoreOptions } from '~/composables/match/sport/useMatchTimerCore'

export interface MatchLiveSessionContext {
  orgId: Ref<number | null>
  /** 数値 teamId（試合終了の status 永続化に必要・publicId ではない）。 */
  teamId: Ref<number | null>
  matchId: string
  ownTeamSide: Ref<'HOME' | 'AWAY'>
  /**
   * 競技別タイマーのファクトリ（04 §G.16・動的 import で解決した SportLiveModule.createTimer）。
   * 未指定時はサッカー用タイマー（前後半・延長・PK）を使う（既存挙動の互換）。
   * これにより共通シェル（live.vue）は競技に応じて注入を差し替えるだけで多競技に対応する。
   */
  createTimer?: (options?: UseMatchTimerCoreOptions) => SportTimer
}

export function useMatchLiveSession(sessionCtx: MatchLiveSessionContext) {
  const eventApi = useMatchEventApi()
  const matchApi = useMatchApi()
  const offlineQueue = useMatchOfflineQueue()
  const notification = useNotification()
  const { t } = useI18n()

  const { orgId, teamId, matchId, ownTeamSide } = sessionCtx

  /** 現在の試合ステータス（COMPLETED 冪等判定用・live.vue から同期される）。 */
  const matchStatus = ref<string | null>(null)
  function setMatchStatus(status: string | null): void {
    matchStatus.value = status
  }

  // === 導出スコア（イベント由来） ===
  const homeScore = ref(0)
  const awayScore = ref(0)

  function applyDerivedScore(res: { derivedHomeScore?: number; derivedAwayScore?: number }): void {
    if (typeof res.derivedHomeScore === 'number') homeScore.value = res.derivedHomeScore
    if (typeof res.derivedAwayScore === 'number') awayScore.value = res.derivedAwayScore
  }

  // === sender（オンライン直 / 失敗時オフライン退避） ===
  async function onlineSender(body: MatchEventRequest): Promise<MatchEventResponse> {
    if (teamId.value === null) throw new Error('teamId unresolved')
    return eventApi.addEvent(orgId.value, teamId.value, matchId, body)
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
    if (teamId.value === null) return
    const side = ownTeamSide.value
    if (tr.endingPeriod) {
      await safeRecord({ eventType: 'PERIOD_END', period: tr.endingPeriod, teamSide: side, minute: tr.minute ?? undefined })
    }
    if (tr.startingPeriod) {
      await safeRecord({ eventType: 'PERIOD_START', period: tr.startingPeriod, teamSide: side, minute: tr.minute ?? undefined })
    }
  }
  // 競技別タイマー（注入があればそれを・無ければサッカー＝既存挙動の互換）。
  const timer: SportTimer = sessionCtx.createTimer
    ? sessionCtx.createTimer({ onPeriodTransition })
    : useMatchTimer({ onPeriodTransition })

  const recorder = useMatchLiveRecorder({
    sender: resilientSender,
    deleter: async (eventId: string) => {
      if (teamId.value === null || eventId.startsWith('local-')) return
      await eventApi.deleteEvent(orgId.value, teamId.value, matchId, eventId)
    },
    reload: async () => {
      if (teamId.value === null) return []
      const res = await eventApi.listEvents(orgId.value, teamId.value, matchId)
      applyDerivedScore(res)
      return res.events ?? []
    },
    onConflict: () => notification.warn(t('match.live.conflict.title'), t('match.live.conflict.detail')),
    onCompensationFailed: () => notification.error(t('match.live.error.substitution_rollback_failed')),
  })

  /**
   * PERIOD_START / PERIOD_END の自動記録。
   *
   * <p>ピリオド境界はスコア（前後半の按分）・出場時間（PERIOD_START 基準の minute 自動補完・
   * soccer §8.5）の基礎であり、欠落を握り潰してはならない。</p>
   *
   * <ul>
   *   <li>ネットワークエラー時は {@code resilientSender} がオフライン退避し「queued」を通知（throw しない）
   *       ＝復帰時 flush で送出されるので欠落しない。</li>
   *   <li>真の失敗（4xx/5xx）時は throw される。汎用の add_event_failed では「境界が欠けた」ことが
   *       伝わらないため、ここで <b>ピリオド境界の記録失敗を明示警告</b>する（症状を隠さない・根治原則）。</li>
   * </ul>
   */
  async function safeRecord(body: MatchEventRequest): Promise<void> {
    try {
      await resilientSender(body)
    } catch {
      // 4xx/5xx の恒久失敗（network はここに来ず offline 退避済み）。
      // 境界欠落はスコア/出場時間の基礎を壊すため、汎用エラーに加えて明示警告する。
      notification.warn(
        t('match.live.error.period_boundary_failed.title'),
        t('match.live.error.period_boundary_failed.detail'),
      )
    }
  }

  /**
   * 現在の記録文脈（ピリオド・minute・自チーム側）。
   *
   * <p>停止状態（WAITING/HALF_TIME/COMPLETED）には match_events.period の具体値が存在しない
   * （soccer §3: HALF_TIME は UI タイマー状態であって period 値ではない）。停止中に記録された
   * イベントの period は「直近の進行ピリオド」へ丸める（04 §G.2 / soccer §8.5）。</p>
   *
   * <ul>
   *   <li>HALF_TIME / COMPLETED → {@code timer.lastActivePeriod}（直前の FIRST_HALF / SECOND_HALF /
   *       EXTRA_FIRST / EXTRA_SECOND / PENALTY_SHOOTOUT）へ丸める。これにより延長・PK 戦を経た試合の COMPLETED 後の
   *       記録が一律 SECOND_HALF に潰れる従来バグを是正する。</li>
   *   <li>WAITING（キックオフ前で直近進行ピリオドが無い） → 直後に始まる FIRST_HALF へ丸める。</li>
   * </ul>
   *
   * <p>進行状態（FIRST_HALF/SECOND_HALF/EXTRA_FIRST/EXTRA_SECOND/PENALTY_SHOOTOUT）はそのまま period として用いる。</p>
   */
  function currentCtx(teamSide: 'HOME' | 'AWAY' = ownTeamSide.value) {
    const s = timer.state.value
    // 競技別の写像（注入タイマーは自身の stateToPeriod を持つ・無ければサッカー）。
    const concrete = timer.stateToPeriod(s)
    const period = concrete ?? timer.lastActivePeriod.value ?? 'FIRST_HALF'
    return {
      period: period as MatchEventRequest['period'],
      minute: timer.currentMinute.value,
      stoppageMinute: timer.stoppageMinute.value,
      teamSide,
    }
  }

  async function refreshScore(): Promise<void> {
    if (teamId.value === null) return
    try {
      applyDerivedScore(await eventApi.listEvents(orgId.value, teamId.value, matchId))
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

  // ============================================================
  // バスケ用の高レベル記録ハンドラ（MatchEventSheetBasketball からの emit を受ける）
  // ============================================================
  //
  // 【FE→BE 器の境界・正直な説明】バスケ固有 event_type（FIELD_GOAL_2 等・03 §2）は
  // BE の `MatchEventType` enum（器・全競技横断）へ後続の BE 波で追加される想定であり、
  // 現時点の生成型 `MatchEventRequest.eventType` には含まれない。FE は本波で UI・タイマー・
  // カタログの基盤を先行実装するため、唯一この一箇所（POST 直前の器への載せ替え）で
  // カタログ型 → 生成型へ寄せる（拡張点は明示・他に散らさない・他箇所は生成型のまま厳格）。
  // BE が enum を拡張・型再生成したらこの境界は不要になる（recordSingle へ統合）。

  /** カタログ event_type を生成型の器へ載せて 1 イベント記録する（バスケ専用の境界）。 */
  async function recordCatalogSingle(
    eventType: CatalogEventType,
    player: { playerUserId?: number | null; playerName?: string | null; jerseyNumber?: number | null } | null,
    extra?: { note?: string | null; reasonCode?: string | null; linkedEventId?: string | null },
  ): Promise<MatchEventResponse> {
    const ctx = currentCtx()
    const body: MatchEventRequest = {
      // 境界の載せ替え（後続 BE 波で生成型へ統合され不要になる）。
      eventType: eventType as MatchEventRequest['eventType'],
      period: ctx.period,
      teamSide: ctx.teamSide,
    }
    if (ctx.minute != null) body.minute = ctx.minute
    if (ctx.stoppageMinute != null) body.stoppageMinute = ctx.stoppageMinute
    if (player?.playerUserId != null) body.playerUserId = player.playerUserId
    if (player?.playerName) body.playerName = player.playerName
    if (player?.jerseyNumber != null) body.jerseyNumber = player.jerseyNumber
    if (extra?.note) body.note = extra.note
    if (extra?.reasonCode) body.cardReasonCode = extra.reasonCode
    if (extra?.linkedEventId) body.linkedEventId = extra.linkedEventId
    const ev = await resilientSender(body)
    recorder.events.value = [ev, ...recorder.events.value]
    return ev
  }

  /** バスケ得点（2P/3P/FT）＋任意アシスト連鎖（§8.2）。 */
  async function recordBasketScore(p: {
    scorer: RecorderPlayer
    assist: RecorderPlayer | null
    scoreType: BasketballEventType
    note: string | null
    assistNote: string | null
  }): Promise<void> {
    const goal = await recordCatalogSingle(p.scoreType, p.scorer, { note: p.note })
    if (p.assist && (p.assist.playerUserId != null || p.assist.playerName)) {
      await recordCatalogSingle('ASSIST', p.assist, { note: p.assistNote, linkedEventId: goal.id })
    }
    await refreshScore()
  }

  /** バスケ単発スタッツ（リバウンド等・選手のみ・スコア非影響）。 */
  async function recordBasketStat(p: { player: RecorderPlayer; eventType: BasketballEventType; note: string | null }): Promise<void> {
    await recordCatalogSingle(p.eventType, p.player, { note: p.note })
  }

  /** バスケ ファウル（PF/TF）＋理由コード（§8.3）。 */
  async function recordBasketFoul(p: { player: RecorderPlayer; foulType: BasketballEventType; reasonCode: BasketballFoulCode | null; note: string | null }): Promise<void> {
    await recordCatalogSingle(p.foulType, p.player, { note: p.note, reasonCode: p.reasonCode })
  }

  // === オフライン flush（online 復帰時） ===
  async function flushOffline(): Promise<void> {
    // flushAll は path から復元した orgId/matchId を渡してくる。現セッションの addEvent に転送する。
    if (teamId.value === null) return
    const results = await offlineQueue.flushAll(
      (oid, mid, body) => eventApi.addEvent(oid, teamId.value!, mid, body),
    )
    const ok = results.filter((r) => r.response).length
    if (ok > 0) {
      notification.success(t('match.live.offline.flushed', { count: ok }))
      await reloadEvents()
    }
  }
  async function reloadEvents(): Promise<void> {
    if (teamId.value === null) return
    const res = await eventApi.listEvents(orgId.value, teamId.value, matchId)
    recorder.setEvents(res.events ?? [])
    applyDerivedScore(res)
  }

  /**
   * 試合終了。タイマーを COMPLETED に遷移させたうえで、本戦スコア（延長合算済みの導出値）と
   * PK 戦スコアを BE に確定保存してから status を COMPLETED に永続化する
   * （旧実装はローカル timer 状態のみで status が未永続化＝再訪時に IN_PROGRESS のまま
   * 残るバグがあった・04 §G.2 / §G.4）。
   *
   * <p><b>順位連携の要（F08.10 ②）:</b> BE の {@code changeStatus(COMPLETED)} は保存済み
   * Entity の {@code home_score}/{@code away_score}/{@code home_penalty_score}/
   * {@code away_penalty_score} を読んで {@code MatchCompletedEvent} に載せる
   * （MatchService.changeStatus）。したがって COMPLETED の前に {@code finalizeScore} で
   * これらを Entity に確定保存しておかないと、延長/PK の結果が大会 fixture の順位連携
   * （tournament/MatchScoreFixtureListener #1444）に乗らない。本戦は延長得点を含むタイムライン
   * 由来の導出スコア（{@code homeScore}/{@code awayScore}）、PK は引数で受けた成功数を渡す。</p>
   *
   * - 冪等: 既に COMPLETED（matchStatus 同期値）なら finalize/status を再送しない。
   * - orgId/teamId 未解決時はスキップ（ガード）。
   * - finalize 失敗時も status 遷移は試みる（changeStatus は内部でトースト済み）。
   * - PATCH 失敗時は症状を隠さず警告し、再 throw はしない（タイマー UI は終了済み）。
   *
   * @param penalty PK 戦スコア（成功数・絶対 home/away）。PK 戦なしの試合は null。
   */
  async function completeMatch(penalty?: { home: number; away: number } | null): Promise<void> {
    await timer.complete()
    if (matchStatus.value === 'COMPLETED') return
    if (teamId.value === null) return
    try {
      // 本戦（延長得点合算済みの導出スコア）＋ PK 戦スコアを Entity に確定保存。
      // これが COMPLETED 時に MatchCompletedEvent へ載り、大会順位連携に反映される（#1444）。
      await matchApi.finalizeScore(orgId.value, teamId.value, matchId, {
        homeScore: homeScore.value,
        awayScore: awayScore.value,
        homePenaltyScore: penalty ? penalty.home : undefined,
        awayPenaltyScore: penalty ? penalty.away : undefined,
      })
    } catch {
      // finalizeScore は composable 内でトースト済み。スコア確定に失敗しても
      // status 遷移自体は試みる（タイマー UI は既に終了状態）。症状は隠さない。
    }
    try {
      await matchApi.changeStatus(orgId.value, teamId.value, matchId, { status: 'COMPLETED' })
      matchStatus.value = 'COMPLETED'
    } catch {
      // changeStatus は composable 内でトースト済みだが、終了の永続化失敗は
      // 集計に直結するため明示的に警告する（症状を隠さない・根治原則）。
      notification.warn(t('match.live.error.complete_failed'))
    }
  }

  return {
    homeScore,
    awayScore,
    timer,
    recorder,
    matchStatus,
    setMatchStatus,
    completeMatch,
    applyDerivedScore,
    refreshScore,
    recordGoal,
    recordAssist,
    recordCard,
    recordSub,
    recordOther,
    recordBasketScore,
    recordBasketStat,
    recordBasketFoul,
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
