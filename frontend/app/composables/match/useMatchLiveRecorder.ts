/**
 * F08.10 ライブ記録のコアロジック（04_frontend_and_ux.md §G.2 / §G.2a / §G.5 / §G.7・
 * sports/01_soccer.md §8.1 / §8.2）。
 *
 * 責務:
 *   1. 3 タップ記録（種別 → 選手 → 補助）の実 POST。イベント一覧の保持・再取得。
 *   2. 双方向連鎖（GOAL ⇔ ASSIST を linked_event_id で結ぶ）。得点起点でもアシスト起点でも同じ連鎖を作る。
 *   3. 交代（SUB_OUT + SUB_IN）を連続記録し、undo では原子的に両方取り消す。
 *   4. undo（1 ステップ・交代ペアは原子的）。直前操作のイベント群を記憶し DELETE で取り消す。
 *   5. 409（楽観ロック競合）リトライ: フォームを閉じず再取得→再試行する（入力データを失わない）。
 *
 * recorded_by_team_id 等は FE で送らず BE が principal から導出する（送らない＝設計書 §G.4）。
 * オフライン退避は useMatchOfflineQueue に委譲（live.vue でラップ）。本 composable は
 * 「sender（実 POST）」を注入で受け取り、オンライン/オフラインの切替を呼び出し側に委ねる。
 */
import type {
  MatchEventRequest,
  MatchEventResponse,
  MatchEventType,
  MatchPeriod,
  TeamSide,
  CardReasonCode,
} from '~/types/match'

/** 選手 1 人分の最小入力（グリッド選択 or 手入力）。 */
export interface RecorderPlayer {
  playerUserId?: number | null
  playerName?: string | null
  jerseyNumber?: number | null
}

/** 記録時に共通で要る文脈（現在ピリオド・minute・記録対象チーム側）。 */
export interface RecordContext {
  period: MatchPeriod
  minute: number | null
  stoppageMinute?: number | null
  teamSide: TeamSide
}

/** undo 用に「1 操作で生成したイベント群」を束ねたエントリ。 */
interface UndoEntry {
  label: MatchEventType | 'SUBSTITUTION' | 'GOAL_ASSIST'
  eventIds: string[]
}

/** sender: 実際の 1 イベント POST（オンライン=API 直 / オフライン=キュー）。成功で生成イベントを返す。 */
export type EventSender = (body: MatchEventRequest) => Promise<MatchEventResponse>
/** deleter: イベント DELETE（undo 用）。 */
export type EventDeleter = (eventId: string) => Promise<void>

export interface UseMatchLiveRecorderOptions {
  sender: EventSender
  deleter: EventDeleter
  /** 一覧の再取得（409 リトライ・記録後の同期に使う）。 */
  reload: () => Promise<MatchEventResponse[]>
  /** 409 競合を検知する判定（既定: error.statusCode/status === 409）。 */
  isConflict?: (err: unknown) => boolean
  /** 409 検知時の通知（「他の記録者が更新しました」）。 */
  onConflict?: () => void
  /** 交代の補償削除（SUB_IN 失敗時の SUB_OUT 巻き戻し）が失敗したときの通知。 */
  onCompensationFailed?: () => void
}

function defaultIsConflict(err: unknown): boolean {
  if (typeof err !== 'object' || err === null) return false
  const e = err as { statusCode?: number; status?: number; response?: { status?: number } }
  return e.statusCode === 409 || e.status === 409 || e.response?.status === 409
}

export function useMatchLiveRecorder(options: UseMatchLiveRecorderOptions) {
  const isConflict = options.isConflict ?? defaultIsConflict

  /** タイムライン（時系列・最新が先頭）。 */
  const events = ref<MatchEventResponse[]>([])
  /** 直近操作の undo スタック（最後の 1 件のみ undo・§G.5）。 */
  const undoStack = ref<UndoEntry[]>([])
  /** 記録中フラグ（多重送信防止）。 */
  const recording = ref(false)

  const canUndo = computed(() => undoStack.value.length > 0)

  /** イベント一覧をセット（live.vue 初期ロード時）。 */
  function setEvents(list: MatchEventResponse[]): void {
    events.value = sortDesc(list)
  }

  /**
   * 1 イベント送信（409 リトライ込み）。
   * 409 を検知したら onConflict 通知＋reload で再取得し、1 回だけ再試行する
   * （フォームは閉じない＝入力データを失わない・§G.7）。
   */
  async function sendOne(body: MatchEventRequest): Promise<MatchEventResponse> {
    try {
      return await options.sender(body)
    } catch (err) {
      if (isConflict(err)) {
        options.onConflict?.()
        const latest = await options.reload()
        events.value = sortDesc(latest)
        // 再試行（1 回）。再度競合したらそのまま throw（無限リトライしない＝握りつぶさない）。
        return await options.sender(body)
      }
      throw err
    }
  }

  /** events 配列へ前方挿入（最新が先頭）。 */
  function prepend(ev: MatchEventResponse): void {
    events.value = sortDesc([ev, ...events.value])
  }

  // ============================================================
  // 単発イベント記録（カード・その他・PERIOD_START/END 等）
  // ============================================================

  /**
   * 単発イベントを記録する（GOAL/ASSIST 単体・YELLOW_CARD・OTHER・SAVE・PERIOD_* など）。
   * カードは cardReasonCode・OTHER は customLabel を任意で付帯できる。
   */
  async function recordSingle(
    eventType: MatchEventType,
    ctx: RecordContext,
    player?: RecorderPlayer | null,
    extra?: { cardReasonCode?: CardReasonCode | null; customLabel?: string | null; note?: string | null },
  ): Promise<MatchEventResponse> {
    recording.value = true
    try {
      const body = buildBody(eventType, ctx, player, extra)
      const ev = await sendOne(body)
      prepend(ev)
      if (ev.id) pushUndo({ label: eventType, eventIds: [ev.id] })
      return ev
    } finally {
      recording.value = false
    }
  }

  // ============================================================
  // 得点⇔アシスト 双方向連鎖（§G.2a・soccer §8.2）
  // ============================================================

  /**
   * 得点起点の連鎖（速い道）: GOAL を記録し、任意でアシストを紐付ける。
   * assist が渡されれば ASSIST を記録し、両イベントを linked_event_id で双方向に結ぶ。
   * goalType で PENALTY_GOAL / OWN_GOAL にも対応する。
   */
  async function recordGoalWithAssist(
    ctx: RecordContext,
    scorer: RecorderPlayer,
    assist?: RecorderPlayer | null,
    goalType: 'GOAL' | 'PENALTY_GOAL' | 'OWN_GOAL' = 'GOAL',
    notes?: { goalNote?: string | null; assistNote?: string | null },
  ): Promise<{ goal: MatchEventResponse; assist: MatchEventResponse | null }> {
    recording.value = true
    try {
      const goal = await sendOne(buildBody(goalType, ctx, scorer, { note: notes?.goalNote }))
      prepend(goal)

      let assistEv: MatchEventResponse | null = null
      if (assist && (assist.playerUserId != null || assist.playerName)) {
        assistEv = await sendOne(
          buildBody('ASSIST', ctx, assist, { note: notes?.assistNote, linkedEventId: goal.id }),
        )
        prepend(assistEv)
        // GOAL 側にも逆参照を張る（双方向連鎖）。
        if (goal.id && assistEv.id) {
          linkBack(goal.id, assistEv.id)
        }
      }

      const ids = [goal.id, assistEv?.id].filter((x): x is string => !!x)
      pushUndo({ label: 'GOAL_ASSIST', eventIds: ids })
      return { goal, assist: assistEv }
    } finally {
      recording.value = false
    }
  }

  /**
   * アシスト起点の連鎖（物語る道）: ASSIST を記録し、続けて得点へつなぐ。
   * scorer が渡されれば GOAL を記録し双方向に結ぶ。
   */
  async function recordAssistThenGoal(
    ctx: RecordContext,
    assist: RecorderPlayer,
    scorer?: RecorderPlayer | null,
    goalType: 'GOAL' | 'PENALTY_GOAL' = 'GOAL',
    notes?: { assistNote?: string | null; goalNote?: string | null },
  ): Promise<{ assist: MatchEventResponse; goal: MatchEventResponse | null }> {
    recording.value = true
    try {
      const assistEv = await sendOne(buildBody('ASSIST', ctx, assist, { note: notes?.assistNote }))
      prepend(assistEv)

      let goal: MatchEventResponse | null = null
      if (scorer && (scorer.playerUserId != null || scorer.playerName)) {
        goal = await sendOne(
          buildBody(goalType, ctx, scorer, { note: notes?.goalNote, linkedEventId: assistEv.id }),
        )
        prepend(goal)
        if (goal.id && assistEv.id) {
          linkBack(assistEv.id, goal.id)
        }
      }

      const ids = [assistEv.id, goal?.id].filter((x): x is string => !!x)
      pushUndo({ label: 'GOAL_ASSIST', eventIds: ids })
      return { assist: assistEv, goal }
    } finally {
      recording.value = false
    }
  }

  /**
   * 先発側イベントに linked_event_id をローカル反映する（双方向連鎖の表示束ね・§G.2b）。
   * 後発側は POST 時に linkedEventId を持つため、先発側へは events 配列上で逆参照を張る
   * （BE は逆参照を自動補完しない設計のため、表示の束ねを FE 側で双方向に成立させる）。
   */
  function linkBack(targetId: string, linkedId: string): void {
    const idx = events.value.findIndex((e) => e.id === targetId)
    if (idx >= 0) {
      const cur = events.value[idx]
      if (cur) events.value[idx] = { ...cur, linkedEventId: linkedId }
    }
  }

  // ============================================================
  // 交代（SUB_OUT + SUB_IN・原子的 undo・§G.5）
  // ============================================================

  /**
   * 交代を記録する: SUB_OUT → SUB_IN を連続記録し 1 操作として扱う。
   * undo では両方を原子的に取り消す（片方だけ残さない）。
   */
  async function recordSubstitution(
    ctx: RecordContext,
    out: RecorderPlayer,
    inPlayer: RecorderPlayer,
  ): Promise<{ subOut: MatchEventResponse; subIn: MatchEventResponse }> {
    recording.value = true
    try {
      const subOut = await sendOne(
        buildBody('SUB_OUT', ctx, out, { relatedPlayer: inPlayer }),
      )
      prepend(subOut)
      let subIn: MatchEventResponse
      try {
        subIn = await sendOne(buildBody('SUB_IN', ctx, inPlayer, { relatedPlayer: out }))
      } catch (err) {
        // SUB_IN が失敗したら SUB_OUT を巻き戻して中途半端な OUT を残さない（§各ステップ UI 仕様）。
        if (subOut.id) {
          try {
            await options.deleter(subOut.id)
            // 巻き戻し削除が成功したときだけ局所からも除去する（サーバと一致）。
            events.value = events.value.filter((e) => e.id !== subOut.id)
          } catch (delErr) {
            // 補償削除が失敗＝サーバに SUB_OUT が残る。ここで局所を楽観除去するとサーバと乖離し、
            // 「消えたはずの OUT がリロードで復活」する事故になる。局所にも残したまま（再試行余地を保つ）、
            // 失敗を通知＋ログで表面化する（症状を隠さない・根治原則）。
            console.error('[match] 交代の巻き戻し削除（SUB_OUT）に失敗。サーバと局所を一致させ残置します', delErr)
            options.onCompensationFailed?.()
          }
        }
        throw err
      }
      prepend(subIn)
      const ids = [subOut.id, subIn.id].filter((x): x is string => !!x)
      pushUndo({ label: 'SUBSTITUTION', eventIds: ids })
      return { subOut, subIn }
    } finally {
      recording.value = false
    }
  }

  // ============================================================
  // undo（直前 1 操作・交代/連鎖は原子的）
  // ============================================================

  /**
   * 直前操作を取り消す。スタック最後のエントリの全イベントを DELETE し、events から除去する。
   * 交代ペア・連鎖ペアは両方まとめて取り消す（原子的）。
   */
  async function undoLast(): Promise<void> {
    const entry = undoStack.value[undoStack.value.length - 1]
    if (!entry) return
    for (const id of entry.eventIds) {
      await options.deleter(id)
      events.value = events.value.filter((e) => e.id !== id)
    }
    undoStack.value = undoStack.value.slice(0, -1)
  }

  /** 任意イベントを削除する（タイムラインのスワイプ削除・連鎖の片側のみ）。 */
  async function deleteEvent(eventId: string): Promise<void> {
    await options.deleter(eventId)
    events.value = events.value.filter((e) => e.id !== eventId)
    // 連鎖の相手側が残る場合は linkedEventId を外す（単独化・§G.2b）。
    events.value = events.value.map((e) =>
      e.linkedEventId === eventId ? { ...e, linkedEventId: undefined } : e,
    )
    // undo スタックから当該 ID を含むエントリを除去（整合維持）。
    undoStack.value = undoStack.value.filter((entry) => !entry.eventIds.includes(eventId))
  }

  function pushUndo(entry: UndoEntry): void {
    if (entry.eventIds.length === 0) return
    undoStack.value = [...undoStack.value, entry]
  }

  return {
    events,
    undoStack,
    canUndo,
    recording,
    setEvents,
    recordSingle,
    recordGoalWithAssist,
    recordAssistThenGoal,
    recordSubstitution,
    undoLast,
    deleteEvent,
  }
}

// ============================================================
// ヘルパ
// ============================================================

/** MatchEventRequest を組み立てる（recorded_by_team_id は送らない＝BE 導出）。 */
function buildBody(
  eventType: MatchEventType,
  ctx: RecordContext,
  player?: RecorderPlayer | null,
  extra?: {
    cardReasonCode?: CardReasonCode | null
    customLabel?: string | null
    note?: string | null
    linkedEventId?: string | null
    relatedPlayer?: RecorderPlayer | null
  },
): MatchEventRequest {
  const body: MatchEventRequest = {
    eventType,
    period: ctx.period,
    teamSide: ctx.teamSide,
  }
  if (ctx.minute != null) body.minute = ctx.minute
  if (ctx.stoppageMinute != null) body.stoppageMinute = ctx.stoppageMinute
  if (player?.playerUserId != null) body.playerUserId = player.playerUserId
  if (player?.playerName) body.playerName = player.playerName
  if (player?.jerseyNumber != null) body.jerseyNumber = player.jerseyNumber
  if (extra?.cardReasonCode) body.cardReasonCode = extra.cardReasonCode
  if (extra?.customLabel) body.customLabel = extra.customLabel
  if (extra?.note) body.note = extra.note
  if (extra?.linkedEventId) body.linkedEventId = extra.linkedEventId
  if (extra?.relatedPlayer?.playerUserId != null) {
    body.relatedPlayerUserId = extra.relatedPlayer.playerUserId
  }
  if (extra?.relatedPlayer?.playerName) body.relatedPlayerName = extra.relatedPlayer.playerName
  return body
}

/** 時系列降順（最新が先頭）にソート。minute → createdAt の順で比較。 */
function sortDesc(list: MatchEventResponse[]): MatchEventResponse[] {
  return [...list].sort((a, b) => {
    const am = a.minute ?? -1
    const bm = b.minute ?? -1
    if (am !== bm) return bm - am
    const at = a.createdAt ?? ''
    const bt = b.createdAt ?? ''
    return bt.localeCompare(at)
  })
}
