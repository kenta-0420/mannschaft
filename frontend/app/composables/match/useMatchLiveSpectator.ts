/**
 * F08.10 ライブ観戦（read-only spectator）composable（07_realtime_spectator.md §J.4 / 04 §G.17）。
 *
 * ## 役割（read-only・配信専用）
 * 観戦者向けに `/topic/matches/{matchId}/live` を STOMP 購読し、記録者の HTTP 書き込みがコミット後に
 * 配信される差分（{@link MatchLiveUpdatePayload}・serverSeq 付き）でスコア/タイムライン/ステータスを
 * リアルタイム更新する。**書き込み経路は一切持たない**（正本は HTTP・記録は記録者のみ・07 §J.1）。
 *
 * ## 設計の要（07 §J.4）
 * 1. **初期スナップショット → 差分追従**: 購読確立後にまず HTTP（GET match ＋ GET events）で現在状態を
 *    取得（初期スナップショット）し、以後 topic の差分で部分更新する。
 * 2. **serverSeq による順序保証・重複排除・古い seq 無視**: 受信 seq が「最後に適用した seq」以下なら
 *    無視（重複・順序逆転）。seq が飛んでいたら（>lastSeq+1）スナップショット再取得で整合回復。
 * 3. **再接続**: WebSocket 切断→再接続時に再度スナップショットを取得して整合を取り直す（取りこぼし回復）。
 * 4. **接続状態**: CONNECTING / LIVE / RECONNECTING / OFFLINE / DENIED を公開（04 §G.17）。
 *    WebSocket・HTTP ともに不通なら OFFLINE（最後のスナップショットを保持し「最新でない可能性」を明示）。
 * 5. **購読拒否（可視性なし）**: BE が SUBSCRIBE を ERROR フレームで拒否（07 §J.3.1）。FE は DENIED にする。
 *
 * ## STOMP クライアントの所有
 * 接続状態・ERROR フレーム（購読拒否）・再接続フックを正確に扱う必要があるため、本観戦は
 * **専用の `@stomp/stompjs` Client を所有**する（chat の共有シングルトン `useChatWebSocket` は接続状態と
 * ERROR を握り潰すため流用しない）。CONNECT に Bearer を付与し、beforeConnect で最新トークンへ差し替える
 * （chat と同じ作法）。
 *
 * ## 前向きユニオン境界（any 禁止）
 * 配信ペイロード DTO は OpenAPI 未露出（STOMP 専用・REST DTO でない）のため、`types/match.ts` の
 * 前向きユニオン（{@link MatchLiveUpdatePayload}）で受ける。STOMP 受信本文（JSON 文字列）→ 型への
 * 載せ替えは {@link parseLivePayload} の **1 箇所**に閉じる（他は型付きで厳格・生成型再生成で解消）。
 */
import type { Client, IFrame, StompSubscription } from '@stomp/stompjs'
import { Client as StompClient } from '@stomp/stompjs'
import type {
  MatchEventResponse,
  MatchLiveEventView,
  MatchLiveScoreSummary,
  MatchLiveUpdatePayload,
  MatchSummaryStatus,
  SpectatorConnectionState,
} from '~/types/match'

// ============================================================
// 観戦スナップショット（純データ・差分適用ロジックの対象）
// ============================================================

/**
 * 観戦者が画面に保持する現在状態（初期スナップショット＋受信済み差分の反映結果）。
 * 完全オフライン時もこの最終スナップショットを表示し続ける（04 §G.17）。
 */
export interface SpectatorSnapshot {
  /** 本戦スコア（延長合算済み）。 */
  homeScore: number
  awayScore: number
  /** PK 戦スコア（PK 戦なしは 0）。 */
  homePenaltyScore: number
  awayPenaltyScore: number
  /** 試合ステータス。 */
  status: MatchSummaryStatus | null
  /** タイムライン（sortSeq 昇順）。 */
  events: MatchEventResponse[]
  /** 最後に適用した配信 serverSeq（順序保証・重複排除の基準・0=未適用）。 */
  lastSeq: number
}

/** 空（初期化前）のスナップショットを生成する。 */
export function emptySnapshot(): SpectatorSnapshot {
  return {
    homeScore: 0,
    awayScore: 0,
    homePenaltyScore: 0,
    awayPenaltyScore: 0,
    status: null,
    events: [],
    lastSeq: 0,
  }
}

/**
 * 差分適用の結果種別（呼び出し側の制御＝スナップショット再取得トリガに使う）。
 * - APPLIED:    差分を適用した（seq を更新した）
 * - IGNORED:    古い/重複 seq のため無視した（seq <= lastSeq）
 * - RESYNC:     seq が飛んでいる（> lastSeq+1）。スナップショット再取得が必要（07 §J.4）
 */
export type LiveUpdateResult = 'APPLIED' | 'IGNORED' | 'RESYNC'

/**
 * 配信差分を現在スナップショットへ適用する（**純関数・副作用なし**）。
 *
 * <p>serverSeq の規律（07 §J.2.1 / §J.4）:</p>
 * <ul>
 *   <li>seq <= lastSeq → 既適用/順序逆転＝<b>無視</b>（IGNORED・スナップショットは不変）。</li>
 *   <li>seq === lastSeq + 1 → 連続＝差分を適用（APPLIED・lastSeq を更新）。</li>
 *   <li>seq > lastSeq + 1 → 取りこぼし＝<b>適用せず RESYNC</b>を返す（呼び出し側がスナップショット再取得）。
 *       中途半端に飛んだ差分を当てるとスコアがずれるため、当てずに HTTP 再取得で整合回復する。</li>
 * </ul>
 *
 * <p>初回適用（lastSeq=0）は seq>=1 を連続とみなす（スナップショット取得直後の最初の差分を受ける）。</p>
 *
 * @returns 適用結果（IGNORED/APPLIED/RESYNC）と更新後スナップショット（不変＝新オブジェクトを返す）
 */
export function applyLiveUpdate(
  snapshot: SpectatorSnapshot,
  payload: MatchLiveUpdatePayload,
): { result: LiveUpdateResult; snapshot: SpectatorSnapshot } {
  const seq = payload.serverSeq
  // 古い/重複（順序逆転・再送）は無視（スナップショットは不変）。
  if (seq <= snapshot.lastSeq) {
    return { result: 'IGNORED', snapshot }
  }
  // 取りこぼし（飛び）→ 当てずにスナップショット再取得を要求（07 §J.4）。
  if (seq > snapshot.lastSeq + 1) {
    return { result: 'RESYNC', snapshot }
  }

  // seq === lastSeq + 1（連続）→ 種別ごとに部分更新。
  const next: SpectatorSnapshot = { ...snapshot, lastSeq: seq }
  switch (payload.type) {
    case 'EVENT_ADDED':
    case 'EVENT_UPDATED':
      if (payload.event) {
        next.events = upsertEvent(snapshot.events, liveEventViewToResponse(payload.event))
      }
      break
    case 'EVENT_DELETED':
      if (payload.eventId) {
        next.events = snapshot.events.filter((e) => e.id !== payload.eventId)
      }
      break
    case 'SCORE_UPDATED':
      applyScore(next, payload.score)
      break
    case 'STATUS_CHANGED':
      next.status = payload.status ?? snapshot.status
      break
  }
  return { result: 'APPLIED', snapshot: next }
}

/** スコアサマリをスナップショットへ反映する（NULL は据え置かず 0 にしない＝既存値を保つ）。 */
function applyScore(snapshot: SpectatorSnapshot, score: MatchLiveScoreSummary | null | undefined): void {
  if (!score) return
  if (typeof score.homeScore === 'number') snapshot.homeScore = score.homeScore
  if (typeof score.awayScore === 'number') snapshot.awayScore = score.awayScore
  if (typeof score.homePenaltyScore === 'number') snapshot.homePenaltyScore = score.homePenaltyScore
  if (typeof score.awayPenaltyScore === 'number') snapshot.awayPenaltyScore = score.awayPenaltyScore
}

/**
 * イベントを sortSeq 昇順で upsert する（同一 id があれば置換・なければ挿入）。
 * 配信は順不同で届きうるため、毎回 sortSeq でソートして表示順を安定させる（純関数・新配列を返す）。
 */
export function upsertEvent(
  events: readonly MatchEventResponse[],
  ev: MatchEventResponse,
): MatchEventResponse[] {
  const next = events.filter((e) => e.id !== ev.id)
  next.push(ev)
  next.sort((a, b) => (a.sortSeq ?? 0) - (b.sortSeq ?? 0))
  return next
}

/**
 * 配信用最小ビュー（{@link MatchLiveEventView}）→ スナップショットの {@link MatchEventResponse} 形へ変換する。
 *
 * <p>配信ビューは機微情報（player_user_id 等）を持たない（07 §J.3.3）。観戦タイムラインは表示名のみで
 * 足りるため、欠落フィールド（playerUserId/relatedPlayerUserId/matchId/createdAt 等）は未設定のままにする。
 * これは観戦が read-only ＝編集に内部 ID を必要としないため整合する。</p>
 */
export function liveEventViewToResponse(view: MatchLiveEventView): MatchEventResponse {
  return {
    id: view.id,
    minute: view.minute ?? undefined,
    stoppageMinute: view.stoppageMinute ?? undefined,
    period: view.period ?? undefined,
    eventType: view.eventType,
    cardReasonCode: view.cardReasonCode ?? undefined,
    customLabel: view.customLabel ?? undefined,
    teamSide: view.teamSide,
    playerName: view.playerName ?? undefined,
    jerseyNumber: view.jerseyNumber ?? undefined,
    relatedPlayerName: view.relatedPlayerName ?? undefined,
    note: view.note ?? undefined,
    linkedEventId: view.linkedEventId ?? undefined,
    sortSeq: view.sortSeq,
  }
}

/**
 * STOMP 受信本文（JSON 文字列）→ {@link MatchLiveUpdatePayload}（前向きユニオン）。
 *
 * <p><b>前向きユニオン境界（any 禁止・型載せ替えはここ 1 箇所）</b>: 配信 DTO は OpenAPI 未露出のため、
 * 受信本文の JSON を本ユニオンへ載せる唯一の箇所。serverSeq が数値でなければ不正フレームとして null。</p>
 */
export function parseLivePayload(body: string): MatchLiveUpdatePayload | null {
  try {
    const raw: unknown = JSON.parse(body)
    if (typeof raw !== 'object' || raw === null) return null
    const obj = raw as Record<string, unknown>
    if (typeof obj.serverSeq !== 'number' || typeof obj.type !== 'string') return null
    // 境界の載せ替え（生成型再生成で解消・他に散らさない）。
    return raw as MatchLiveUpdatePayload
  } catch {
    // eslint-disable-next-line no-restricted-syntax -- 不正な STOMP フレーム（JSON パース不能）を破棄する防御。null=不正フレームは呼び出し側で無視
    return null
  }
}

// ============================================================
// composable（STOMP 接続管理＋スナップショット結線）
// ============================================================

/** 観戦 composable のコンテキスト。 */
export interface MatchSpectatorContext {
  /** 数値 orgId（events スナップショット取得に必要）。 */
  orgId: Ref<number | null>
  /** 数値 teamId（match 詳細スナップショット取得に必要）。 */
  teamId: Ref<number | null>
  /** 対象試合 ID（UUID 文字列）。STOMP 宛先と一致。 */
  matchId: string
}

/** トピック宛先（BE 配信先 `/topic/matches/{matchId}/live` と一致・07 §J.2）。 */
export function liveTopicDestination(matchId: string): string {
  return `/topic/matches/${matchId}/live`
}

export function useMatchLiveSpectator(ctx: MatchSpectatorContext) {
  const matchApi = useMatchApi()
  const eventApi = useMatchEventApi()

  /** 画面に保持する観戦スナップショット（reactive）。 */
  const snapshot = ref<SpectatorSnapshot>(emptySnapshot())
  /** 接続状態（04 §G.17 インジケーター）。 */
  const connectionState = ref<SpectatorConnectionState>('CONNECTING')
  /** スナップショットを一度でも取得できたか（OFFLINE 時に「最後の状態」を出すかの判定）。 */
  const hasSnapshot = ref(false)

  let client: Client | null = null
  let subscription: StompSubscription | null = null
  /** 再接続後の再購読・再取得判定用（初回接続では再取得しない）。 */
  let isFirstConnect = true
  /** 破棄済みフラグ（非同期コールバックの後追い実行を抑止）。 */
  let disposed = false

  /**
   * HTTP で初期/再同期スナップショットを取得する（07 §J.4）。
   * 成功で hasSnapshot=true。失敗（HTTP 不通）時は最後のスナップショットを保持し OFFLINE を示す。
   */
  async function fetchSnapshot(): Promise<boolean> {
    if (ctx.teamId.value === null) return false
    try {
      const [match, events] = await Promise.all([
        matchApi.getMatch(ctx.orgId.value, ctx.teamId.value, ctx.matchId),
        eventApi.listEvents(ctx.orgId.value, ctx.teamId.value, ctx.matchId),
      ])
      if (disposed) return false
      snapshot.value = {
        homeScore: events.derivedHomeScore ?? match.homeScore ?? 0,
        awayScore: events.derivedAwayScore ?? match.awayScore ?? 0,
        homePenaltyScore: match.homePenaltyScore ?? 0,
        awayPenaltyScore: match.awayPenaltyScore ?? 0,
        status: (match.status as MatchSummaryStatus | undefined) ?? null,
        events: sortBySortSeq(events.events ?? []),
        // スナップショット取得＝以後の差分の基準点。直前の lastSeq は引き継がず 0 から再開する
        // （飛び検知 RESYNC で再取得した直後は、次に来る差分から連続適用する）。
        lastSeq: 0,
      }
      hasSnapshot.value = true
      return true
    } catch {
      // HTTP 不通（端末オフライン等）。最後のスナップショットを保持し OFFLINE を示す（04 §G.17）。
      // useMatchApi/useMatchEventApi が握り潰さずトースト済み（症状は隠さない）。
      return false
    }
  }

  /** 受信差分を適用し、飛び検知時はスナップショット再取得で整合を回復する（07 §J.4）。 */
  async function onLiveFrame(body: string): Promise<void> {
    const payload = parseLivePayload(body)
    if (!payload) return
    const { result, snapshot: next } = applyLiveUpdate(snapshot.value, payload)
    if (result === 'APPLIED') {
      snapshot.value = next
    } else if (result === 'RESYNC') {
      // seq の飛び＝取りこぼし。当てずに HTTP スナップショット再取得で整合回復（07 §J.4）。
      await fetchSnapshot()
    }
    // IGNORED は何もしない（古い/重複）。
  }

  /** STOMP 購読を確立する（接続済み時のみ）。 */
  function subscribe(): void {
    if (client === null || !client.connected || disposed) return
    subscription?.unsubscribe()
    subscription = client.subscribe(liveTopicDestination(ctx.matchId), (frame: IFrame) => {
      void onLiveFrame(frame.body)
    })
  }

  /**
   * 観戦を開始する（STOMP 接続 → スナップショット取得 → 購読 → 差分追従）。
   * 設計（07 §J.4）に従い、購読確立と並行/直後に初期スナップショットを HTTP 取得する。
   */
  async function start(): Promise<void> {
    if (disposed) return
    // まず初期スナップショットを取得（HTTP 正本・07 §J.4）。失敗は OFFLINE 表示で継続。
    const ok = await fetchSnapshot()
    if (disposed) return
    if (!ok) connectionState.value = 'OFFLINE'

    const auth = useAuthStore()
    // BE の /ws は SockJS 登録のみのため、生 WebSocket は /ws/websocket でしか接続できない
    // （bare /ws は 400。共通ヘルパー useWsUrl 経由で apiBase 込みの URL を解決する）。
    const stomp = new StompClient({
      webSocketFactory: () => new WebSocket(useWsUrl()),
      connectHeaders: { Authorization: `Bearer ${auth.accessToken ?? ''}` },
      beforeConnect: () => {
        // 再接続時に最新トークンへ差し替える（リフレッシュ対応・chat と同作法）。
        if (client !== null) {
          client.connectHeaders = { Authorization: `Bearer ${useAuthStore().accessToken ?? ''}` }
        }
      },
      reconnectDelay: 2000,
      onConnect: () => {
        if (disposed) return
        connectionState.value = 'LIVE'
        if (!isFirstConnect) {
          // 再接続: 切断中の取りこぼしを HTTP スナップショット再取得で回復してから再購読（07 §J.4）。
          void fetchSnapshot().then(() => subscribe())
        } else {
          subscribe()
        }
        isFirstConnect = false
      },
      onWebSocketClose: () => {
        if (disposed) return
        // 切断＝再接続中（stompjs が reconnectDelay で自動再接続する）。
        // DENIED は購読拒否の確定状態なので上書きしない。
        if (connectionState.value !== 'DENIED') connectionState.value = 'RECONNECTING'
      },
      onStompError: (_frame: IFrame) => {
        if (disposed) return
        // SUBSCRIBE 拒否（可視性なし・F00／07 §J.3.1）は ERROR フレームで届く。
        // BE は match live 宛先の購読を canView=false で拒否する。観戦不可として DENIED にする。
        connectionState.value = 'DENIED'
      },
    })
    client = stomp
    stomp.activate()
  }

  /** 観戦を終了する（購読解除・接続切断・後追いコールバック抑止）。 */
  function stop(): void {
    disposed = true
    subscription?.unsubscribe()
    subscription = null
    void client?.deactivate()
    client = null
  }

  /**
   * オンライン復帰（`window 'online'`）時の再同期。
   * OFFLINE/RECONNECTING から復帰したらスナップショットを取り直し、接続を再活性化する（04 §G.17）。
   */
  async function resync(): Promise<void> {
    if (disposed) return
    const ok = await fetchSnapshot()
    if (ok && client !== null && !client.active) client.activate()
    if (ok && connectionState.value === 'OFFLINE') {
      connectionState.value = client?.connected ? 'LIVE' : 'RECONNECTING'
    }
  }

  return {
    snapshot,
    connectionState,
    hasSnapshot,
    start,
    stop,
    resync,
    /** テスト/特殊用途: 差分フレーム適用（通常は内部の購読コールバックで呼ばれる）。 */
    fetchSnapshot,
  }
}

/** events を sortSeq 昇順に整列する（純関数・新配列）。 */
function sortBySortSeq(events: readonly MatchEventResponse[]): MatchEventResponse[] {
  return [...events].sort((a, b) => (a.sortSeq ?? 0) - (b.sortSeq ?? 0))
}
