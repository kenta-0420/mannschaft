/**
 * F03.4+ 臨時休業「確認状況」のリアルタイム購読 composable。
 *
 * ## 役割（read-only・配信専用）
 * チーム管理者（ADMIN / DEPUTY_ADMIN）向けに、患者が臨時休業を確認すると配信される差分
 * （{@link EmergencyClosureConfirmationUpdate}）を STOMP 購読し、確認状況パネルを
 * **再読込なしで自動更新**する（「1/3 → 2/3」がリアルタイムに反映される）。
 *
 * トピック宛先（BE 配信先・購読認可 Interceptor と一字一句一致）:
 *   {@code /topic/teams/{teamId}/emergency-closures/{closureId}/confirmations}
 *   （teamId / closureId はいずれも **数値 Long**）。
 *
 * ## お手本（useMatchLiveSpectator）との対応
 * 本 composable は F08.10 ライブ観戦の {@code useMatchLiveSpectator} を範に、以下を踏襲する:
 *   1. **初期スナップショット（HTTP getConfirmations）→ WS 差分追従**。
 *   2. **再接続時はスナップショット再 fetch で取りこぼし回復**（切断中に届いた確認を取り戻す）。
 *   3. **接続状態（CONNECTING / LIVE / RECONNECTING / OFFLINE / DENIED）を公開**。
 *   4. **専用 STOMP Client を所有**（接続状態・ERROR フレーム＝購読拒否を正確に扱うため、
 *      共有シングルトンは流用しない）。CONNECT に Bearer を付与し、beforeConnect で最新トークンへ差し替える。
 *   5. **購読拒否（非 ADMIN/DEPUTY＝可視性なし）は STOMP ERROR フレームで届く** → DENIED にする
 *      （BE {@code EmergencyClosureSubscriptionInterceptor} が MessagingException を投げる）。
 *
 * ## match との差分（serverSeq を使わない理由）
 * match live は serverSeq による順序保証・飛び検知が必要だが、臨時休業の確認配信ペイロードは
 * **その時点の確定カウント（confirmedCount / totalCount）と、今回確認した個別ユーザー**を含む
 * （{@link EmergencyClosureConfirmationUpdate}）。カウントは配信ごとに「最新の確定値」が来るため
 * 加算ではなく置換で整合する。個別ユーザーの confirmed 化も冪等（再送・順序逆転でも結果は同じ）。
 * よって serverSeq の飛び検知は不要で、純粋な置換適用（{@link applyConfirmationUpdate}）で足りる。
 *
 * ## 前向きユニオン境界（any 禁止）
 * 配信 DTO は OpenAPI 未露出（STOMP 専用・REST DTO でない）のため、本ファイルの
 * {@link EmergencyClosureConfirmationUpdate} で受ける。STOMP 受信本文（JSON 文字列）→ 型への
 * 載せ替えは {@link parseConfirmationUpdate} の **1 箇所**に閉じる。
 */
import type { Client, IFrame, StompSubscription } from '@stomp/stompjs'
import { Client as StompClient } from '@stomp/stompjs'
import type { ClosureConfirmationItem } from '~/composables/useEmergencyClosureApi'

// ============================================================
// 配信ペイロード（BE EmergencyClosureConfirmationUpdatePayload と一致・STOMP 専用）
// ============================================================

/**
 * 臨時休業の患者確認 1 件の配信差分。
 * BE {@code EmergencyClosureConfirmationUpdatePayload}（フィールド名・型）と一致させる契約。
 */
export interface EmergencyClosureConfirmationUpdate {
  /** 確認済み件数（confirmedAt がセットされたレコード数・配信時点の確定値）。 */
  confirmedCount: number
  /** 通知対象の総件数（この臨時休業の確認追跡レコード数・配信時点の確定値）。 */
  totalCount: number
  /** 今回確認したユーザー ID。 */
  userId: number
  /** 今回確認したユーザーの氏名（姓 + " " + 名）。 */
  userFullName: string
  /** 今回の確認時刻（ISO LocalDateTime）。 */
  confirmedAt: string
}

/**
 * 接続状態（確認状況パネルのインジケーター用）。useMatchLiveSpectator と同義。
 * - CONNECTING:   初期接続中
 * - LIVE:         WS 接続済み・差分追従中
 * - RECONNECTING: 切断 → 自動再接続中
 * - OFFLINE:      WS・HTTP ともに不通（最後の確認状況を保持）
 * - DENIED:       購読拒否（非 ADMIN/DEPUTY＝可視性なし・STOMP ERROR フレーム）
 */
export type EmergencyClosureConnectionState =
  | 'CONNECTING'
  | 'LIVE'
  | 'RECONNECTING'
  | 'OFFLINE'
  | 'DENIED'

// ============================================================
// 差分適用（純関数・副作用なし）
// ============================================================

/**
 * 配信差分を確認状況リストへ適用する（**純関数・新配列を返す**）。
 *
 * <p>今回確認したユーザー（{@code update.userId}）の行を {@code confirmed=true} ＋ {@code confirmedAt}
 * セットへ更新する。冪等（既に confirmed でも結果は同じ）。対象 userId が現スナップショットに無い場合は
 * 何もしない（次回スナップショット再取得で整合する。配信先トピックが closureId 一致なので通常は存在する）。</p>
 *
 * <p>カウント（confirmedCount / totalCount）は呼び出し側が別途「最新の確定値」で置換する
 * （本関数はリスト行のみを扱う。カウント表示は {@link useEmergencyClosureLive} が confirmedCount を保持）。</p>
 */
export function applyConfirmationUpdate(
  items: readonly ClosureConfirmationItem[],
  update: EmergencyClosureConfirmationUpdate,
): ClosureConfirmationItem[] {
  return items.map((item) =>
    item.userId === update.userId
      ? { ...item, confirmed: true, confirmedAt: update.confirmedAt }
      : item,
  )
}

/**
 * STOMP 受信本文（JSON 文字列）→ {@link EmergencyClosureConfirmationUpdate}（前向きユニオン）。
 *
 * <p><b>前向きユニオン境界（any 禁止・型載せ替えはここ 1 箇所）</b>: 配信 DTO は OpenAPI 未露出のため、
 * 受信本文の JSON を本型へ載せる唯一の箇所。必須フィールド（userId/confirmedCount/totalCount）が
 * 数値でなければ不正フレームとして null。</p>
 */
export function parseConfirmationUpdate(body: string): EmergencyClosureConfirmationUpdate | null {
  try {
    const raw: unknown = JSON.parse(body)
    if (typeof raw !== 'object' || raw === null) return null
    const obj = raw as Record<string, unknown>
    if (
      typeof obj.userId !== 'number'
      || typeof obj.confirmedCount !== 'number'
      || typeof obj.totalCount !== 'number'
    ) {
      return null
    }
    // 境界の載せ替え（他に散らさない）。
    return raw as EmergencyClosureConfirmationUpdate
  } catch {
    // eslint-disable-next-line no-restricted-syntax -- 不正な STOMP フレーム（JSON パース不能）を破棄する防御（安全系）。null=不正フレームは購読側で無視
    return null
  }
}

/** トピック宛先（BE 配信先・購読認可 Interceptor と一致）。teamId / closureId は数値。 */
export function confirmationsTopicDestination(teamId: number, closureId: number): string {
  return `/topic/teams/${teamId}/emergency-closures/${closureId}/confirmations`
}

// ============================================================
// composable（STOMP 接続管理＋確認状況スナップショット結線）
// ============================================================

/** 確認状況リアルタイム購読のコンテキスト。 */
export interface EmergencyClosureLiveContext {
  /**
   * 数値 teamId（STOMP トピック宛先に必須）。
   * 画面は slug を持つが、トピックは数値が必要なため、解決後の数値 teamId を ref で渡す
   * （slug をトピックに使うと購読認可 Interceptor の正規表現 `\d+` に弾かれ購読不成立）。
   * 未解決中は null（その間は購読を開始しない）。
   */
  teamId: Ref<number | null>
  /**
   * HTTP API 呼び出しに使うチーム識別子（slug or 数値文字列・BE ScopeSlugIdConverter が両対応）。
   * getConfirmations のパスに使う。
   */
  apiTeamRef: Ref<string>
  /** 対象臨時休業 ID（数値）。STOMP トピック・getConfirmations と一致。 */
  closureId: number
}

export function useEmergencyClosureLive(ctx: EmergencyClosureLiveContext) {
  const closureApi = useEmergencyClosureApi()

  /** 画面に保持する確認状況リスト（reactive・初期スナップショット＋受信差分の反映結果）。 */
  const items = ref<ClosureConfirmationItem[]>([])
  /** 確認済み件数（配信の最新確定値。スナップショット取得時は items から再計算）。 */
  const confirmedCount = ref(0)
  /** 通知対象の総件数（配信の最新確定値）。 */
  const totalCount = ref(0)
  /** 接続状態（確認状況パネルのインジケーター）。 */
  const connectionState = ref<EmergencyClosureConnectionState>('CONNECTING')
  /** スナップショットを一度でも取得できたか（OFFLINE 時に「最後の状態」を出すかの判定）。 */
  const hasSnapshot = ref(false)

  let client: Client | null = null
  let subscription: StompSubscription | null = null
  /** 再接続後の再購読・再取得判定用（初回接続では再取得しない）。 */
  let isFirstConnect = true
  /** 破棄済みフラグ（非同期コールバックの後追い実行を抑止）。 */
  let disposed = false

  /** items から確認済み/総件数を再計算する（スナップショット取得直後の整合）。 */
  function recomputeCounts(): void {
    totalCount.value = items.value.length
    confirmedCount.value = items.value.filter((it) => it.confirmed).length
  }

  /**
   * HTTP で初期/再同期スナップショットを取得する。
   * 成功で hasSnapshot=true。失敗（HTTP 不通）時は最後のスナップショットを保持し OFFLINE を示す。
   */
  async function fetchSnapshot(): Promise<boolean> {
    try {
      const res = await closureApi.getConfirmations(ctx.apiTeamRef.value, ctx.closureId)
      if (disposed) return false
      items.value = res.data
      recomputeCounts()
      hasSnapshot.value = true
      return true
    } catch {
      // HTTP 不通。最後のスナップショットを保持し OFFLINE を示す（症状は隠さず useApi 側でトースト済み）。
      return false
    }
  }

  /** 受信差分を確認状況リストへ適用する（カウントは配信の最新確定値で置換）。 */
  function onConfirmationFrame(body: string): void {
    const update = parseConfirmationUpdate(body)
    if (!update || disposed) return
    items.value = applyConfirmationUpdate(items.value, update)
    // カウントは配信の確定値で置換する（加算ではない・配信ごとに最新値が来る）。
    confirmedCount.value = update.confirmedCount
    totalCount.value = update.totalCount
  }

  /** STOMP 購読を確立する（接続済み・teamId 解決済みの時のみ）。 */
  function subscribe(): void {
    if (client === null || !client.connected || disposed) return
    if (ctx.teamId.value === null) return
    subscription?.unsubscribe()
    subscription = client.subscribe(
      confirmationsTopicDestination(ctx.teamId.value, ctx.closureId),
      (frame: IFrame) => onConfirmationFrame(frame.body),
    )
  }

  /**
   * 購読を開始する（STOMP 接続 → スナップショット取得 → 購読 → 差分追従）。
   * teamId 未解決（null）の場合は購読しない（解決後に呼び直すこと）。
   */
  async function start(): Promise<void> {
    if (disposed) return
    // まず初期スナップショットを取得（HTTP 正本）。失敗は OFFLINE 表示で継続。
    const ok = await fetchSnapshot()
    if (disposed) return
    if (!ok) connectionState.value = 'OFFLINE'

    // teamId 未解決ならスナップショット表示のみで WS は張らない（解決後に restart で接続する）。
    if (ctx.teamId.value === null) return

    const auth = useAuthStore()
    // BE の /ws は SockJS 登録のみのため、生 WebSocket は /ws/websocket でしか接続できない
    // （bare /ws は 400。共通ヘルパー useWsUrl 経由で apiBase 込みの URL を解決する）。
    const stomp = new StompClient({
      webSocketFactory: () => new WebSocket(useWsUrl()),
      connectHeaders: { Authorization: `Bearer ${auth.accessToken ?? ''}` },
      beforeConnect: () => {
        // 再接続時に最新トークンへ差し替える（リフレッシュ対応・match と同作法）。
        if (client !== null) {
          client.connectHeaders = { Authorization: `Bearer ${useAuthStore().accessToken ?? ''}` }
        }
      },
      reconnectDelay: 2000,
      onConnect: () => {
        if (disposed) return
        connectionState.value = 'LIVE'
        if (!isFirstConnect) {
          // 再接続: 切断中の取りこぼしを HTTP スナップショット再取得で回復してから再購読。
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
        // SUBSCRIBE 拒否（非 ADMIN/DEPUTY＝可視性なし）は ERROR フレームで届く
        // （BE EmergencyClosureSubscriptionInterceptor が MessagingException を投げる）。購読不可として DENIED。
        connectionState.value = 'DENIED'
      },
    })
    client = stomp
    stomp.activate()
  }

  /** 購読を終了する（購読解除・接続切断・後追いコールバック抑止）。 */
  function stop(): void {
    disposed = true
    subscription?.unsubscribe()
    subscription = null
    void client?.deactivate()
    client = null
  }

  /**
   * オンライン復帰（`window 'online'`）時の再同期。
   * OFFLINE/RECONNECTING から復帰したらスナップショットを取り直し、接続を再活性化する。
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
    items,
    confirmedCount,
    totalCount,
    connectionState,
    hasSnapshot,
    start,
    stop,
    resync,
    /** テスト/特殊用途: スナップショット再取得（通常は内部で呼ばれる）。 */
    fetchSnapshot,
  }
}
