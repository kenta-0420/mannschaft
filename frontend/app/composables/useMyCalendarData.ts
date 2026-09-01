import type { CalendarEventItem } from './useCalendarEvents'
import type { CalendarLayerScopeType } from './schedule/useScheduleCrud'
import type { MyCalendarTodo } from '~/types/todo'

interface CalendarEntryRaw {
  // reflection 等 UUID 主キードメインの行は id=null（§6.2/AC-21）。
  id: number | null
  // 親 schedules 行の ID（§1.5/AC-07(b)）。schedule 由来は id と同値、reflection 等は null。
  scheduleId: number | null
  content: {
    title: string
    eventType: string
    status: string
    // UUID 主キードメイン（reflection・F06.5 §6.2）の識別子。schedule 行は両者 null。
    referenceUuid?: string | null
    referenceKind?: string | null
    // F03.19 §4.6: BE が解決済みの表示色（LAYER_USER > SCHEDULE > CATEGORY > LAYER_AUTO の優先順位で決定済み）。
    color?: string | null
    // 同上の「どの優先順位で決まったか」（§4.3.2 の共通4値）。
    // LAYER_AUTO のときだけ color がそのスコープの自動色（§3.3）そのものである。
    colorSource?: string | null
  }
  time: { startAt: string; endAt: string; allDay: boolean }
  scope: { scopeType: string; scopeId: string; scopeName: string | null; scopeIconUrl: string | null; scopeSlug?: string | null }
  myAttendanceStatus: string
  targetMode?: 'ALL_MEMBERS' | 'SELECTED_MEMBERS'
  targetCount?: number
  targets?: Array<{
    userId: number
    displayName: string
    avatarUrl: string | null
    calendarColor: string | null
  }>
}

interface PersonalScheduleRaw {
  id: number
  content: { title: string; eventType: string; color: string | null }
  time: { startAt: string; endAt: string; allDay: boolean }
}

export interface CalEvent extends CalendarEventItem {
  /**
   * レイヤーキー照合用の**数値スコープID**（文字列化）。レイヤー API（`/me/calendar-layers`）の
   * `scopeId` と同じ形式で揃える。詳細API・画面URL用の識別子とは別物 — {@link CalEvent.scopeRouteId} を使うこと
   * （P1修繕: 旧実装はここに slug を格納しており、レイヤーの数値 scopeId と永久に一致しなかった）。
   */
  scopeId?: string
  /** 詳細API・画面URLに渡す公開スコープID（slug があれば slug、無ければ数値IDへフォールバック）。 */
  scopeRouteId?: string
  scopeIconUrl?: string | null
  isTodo?: boolean
  /**
   * 色の由来（§4.3.2 の共通4値）。`LAYER_AUTO` のときに限り {@link CalEvent.color} は
   * そのスコープの自動色（§3.3）そのものであり、フォールバックチップの色として使える。
   */
  colorSource?: string
}

export interface ScopeOption {
  label: string
  value: string
  scopeType: string
  scopeId: string
  /**
   * 表示フィルタ（selectedScopes/allScopeOptions）と同じ数値スコープIDキー（例: `TEAM:1`）。
   * `availableScopes` の `value`（slug形式・作成スコープ選択専用）とは別物 — このフィールドだけが
   * 表示フィルタ側と橋渡しできる。`layers.value` を走査するその場で分かっている値をここへ
   * そのまま積む（P2修繕: 名前（label）での逆引き突き合わせはチーム名の一意性が保証されない
   * ため誤対応の温床になる。詳細は calendar.vue の savedScopeFilterKey 参照）。
   * `availableScopes` 由来のエントリにのみ設定され、`fallbackScopeOptions` 由来には無い
   * （フォールバックは元々レイヤーに存在しないスコープのため対応する数値キーが無い）。
   */
  filterKey?: string
  /**
   * レイヤーチップの色ドットに使う #RRGGBB（F03.19 §6.4）。BE が解決済みで返した色
   * （`/me/calendar-layers` の `color`）をそのまま持つ。FE 側でハッシュ再計算はしない（§3.3）。
   */
  color?: string
  /** 色の由来（`LAYER_USER` / `LAYER_AUTO`）。「自動に戻す」の出し分けに使う。 */
  colorSource?: string
  /**
   * レイヤー一覧に無いスコープのフォールバックチップか（§5.2.1・AC-23）。
   * true のチップは色変更ポップオーバーを開かない（表示／非表示のみ・§6.4）。
   */
  isFallback?: boolean
  /** レイヤー設定 API のパスに渡す数値 scopeId（PERSONAL は 0）。フォールバックには無い。 */
  layerScopeId?: number
}

/** `/me/calendar-layers` 応答を正規化した1レイヤー分の表示情報（F03.19 §4.3）。 */
export interface CalendarLayerView {
  scopeType: string
  scopeId: number
  scopeName: string
  scopeNameKey: string | null
  scopeIconUrl: string | null
  color: string
  colorSource: string
  hidden: boolean
}

interface CalendarLayerRaw {
  scopeType?: string
  scopeId?: number
  scopeName?: string
  scopeNameKey?: string
  scopeIconUrl?: string
  color?: string
  colorSource?: string
  hidden?: boolean
}

function normalizeLayer(raw: CalendarLayerRaw): CalendarLayerView {
  return {
    scopeType: raw.scopeType ?? 'PERSONAL',
    scopeId: raw.scopeId ?? 0,
    scopeName: raw.scopeName ?? '',
    scopeNameKey: raw.scopeNameKey ?? null,
    scopeIconUrl: raw.scopeIconUrl ?? null,
    color: raw.color ?? '#94a3b8',
    colorSource: raw.colorSource ?? 'LAYER_AUTO',
    hidden: raw.hidden ?? false,
  }
}

/** F03.19 §4.3.1: PERSONAL の scopeId は DB・API・URL・FE レイヤーキーのすべてで 0 に統一する。 */
export const PERSONAL_KEY = 'PERSONAL:0'
export const FILTER_OVERFLOW = 5

/** レイヤー一覧に無いスコープ（フォールバックチップ・§5.2.1）の色ドットに使う中立色。 */
export const FALLBACK_CHIP_COLOR = '#94A3B8'

/**
 * レイヤー設定 API（§4.4/§4.5）のエラーコード → §8 の文言キー。
 * ここに無いコードは共通のエラーハンドラへ委譲する。
 */
const LAYER_ERROR_MESSAGE_KEYS: Record<string, string> = {
  SCHEDULE_101: 'schedule.calendar.error.notMember',
  SCHEDULE_102: 'schedule.calendar.error.invalidColor',
  SCHEDULE_104: 'schedule.calendar.error.layerLimit',
}

/** F03.19 §5.3: localStorage の新スキーマ・旧キー2種。 */
const LAYER_STATE_KEY = 'mannschaft:calendar:layerState'
const LEGACY_CALENDAR_KEY = 'mannschaft:calendar:scopeFilter'
const LEGACY_WIDGET_KEY = 'mannschaft:widget:calendar:scopeFilter'
export type CalendarViewMode = 'month' | 'week' | 'agenda'

interface LayerStateV2 {
  version: 2
  selected: string[]
  view: CalendarViewMode
  /**
   * P2修繕: これまでに一度でも自動選択したフォールバックチップのキー（§5.2.1）。
   * 「初めて現れたときだけ既定選択にする」の判定に使う。ユーザーが明示的に外した後、
   * 月移動・再取得・リロードのたびに selectedScopes へ強制で戻されるのを防ぐため、
   * selectedScopes とは独立に永続化する（一度知ったキーは知ったままにする）。
   */
  knownFallbackKeys: string[]
}

function isValidView(v: unknown): v is CalendarViewMode {
  return v === 'month' || v === 'week' || v === 'agenda'
}

/** 旧キー値 "PERSONAL" を新形式 "PERSONAL:0" へ読み替える（§4.3.1）。 */
function migrateLegacyScopeValue(value: string): string {
  return value === 'PERSONAL' ? PERSONAL_KEY : value
}

/**
 * 【R15 裁定】旧 localStorage キーから新キーへの移行を「セッション中に1度だけ」実行する。
 * `/calendar` とウィジェットの双方が同じ `useMyCalendarData` を共有するため、
 * モジュールスコープのフラグで多重実行を防ぐ（片方が先に旧キーを消すと、もう片方が
 * 初期化フォールバックに落ちて選択状態を失う事故を防止する・設計書 R15）。
 */
let legacyStorageMigrated = false

function migrateLegacyStorage(): void {
  if (legacyStorageMigrated) return
  legacyStorageMigrated = true
  try {
    if (localStorage.getItem(LAYER_STATE_KEY)) return // 新キーが既にあれば移行不要

    // 両方の旧キーが併存する場合は必ず `/calendar` 側（LEGACY_CALENDAR_KEY）を採用する（R15）。
    const calendarRaw = localStorage.getItem(LEGACY_CALENDAR_KEY)
    const widgetRaw = localStorage.getItem(LEGACY_WIDGET_KEY)
    const source = calendarRaw ?? widgetRaw
    if (source == null) return

    let parsed: unknown = null
    try { parsed = JSON.parse(source) }
    catch { parsed = null }

    if (Array.isArray(parsed)) {
      const migrated: LayerStateV2 = {
        version: 2,
        selected: parsed.filter((v): v is string => typeof v === 'string').map(migrateLegacyScopeValue),
        view: 'month',
        knownFallbackKeys: [],
      }
      localStorage.setItem(LAYER_STATE_KEY, JSON.stringify(migrated))
    }
    // 併存有無に関わらず両方の旧キーを削除する（両系統が残って再分裂するのを防ぐ）。
    localStorage.removeItem(LEGACY_CALENDAR_KEY)
    localStorage.removeItem(LEGACY_WIDGET_KEY)
  }
  catch {
    // localStorage が使えない環境（プライベートモード等）。移行できないだけで、
    // これは握りつぶしではなく「新規訪問と同じ扱いで進める」という明示的な劣化。
  }
}

/** テスト専用: 「セッション中1度だけ」のモジュールスコープ移行フラグをリセットする（AC-15b3）。 */
export function __resetCalendarLayerMigrationForTest(): void {
  legacyStorageMigrated = false
}

/**
 * P2修繕: 直近に team/organization ストアを検証したユーザーID（モジュールスコープ）。
 *
 * `undefined` = まだ検証していない（この場合は既存のストア内容を信用し、勝手に消さない —
 * ScopeNavDropdown 等、本 composable 以外が既に正しいユーザーの所属データを読み込んでいる
 * 可能性があるため）。一度検証した後にユーザーIDが変われば「ユーザー切替」とみなす。
 *
 * `useAuthStore.logout()` は team/organization ストアをクリアしない（本戦役の外の既存の
 * 横断的欠陥・別課題として報告する）。このモジュール変数は、その隙間をカレンダー機能側で
 * 自衛するための最小限の状態であり、`legacyStorageMigrated` と同じ「モジュールスコープで
 * SPA セッションを跨いで生存させる」パターンに倣う（composable インスタンス自体は
 * ログアウト時の /login 遷移でページごと破棄され、インスタンス内 state では検知できない）。
 *
 * 【この自衛の限界（Codex四巡目検分・マスター裁定 2026-08-26）】
 * この自衛は「同一 composable（= 本カレンダー機能）を経由したユーザー切替」しか捕捉できない。
 * ユーザーAが**別画面**（例: ScopeNavDropdown・ダッシュボード等）で team/organization ストアを
 * 読み込んでからログアウトし、ユーザーBが**初めてカレンダーを開いた**場合、
 * `lastScopeStoreUserId` はこの composable にとって初観測（`undefined`）のため素通りし、
 * かつ `teamStore.myTeams.length > 0`（A のデータが残存）により再取得もスキップされる。
 * 結果、B に A のチーム名が一瞬でも見える可能性が残る（利用者間の情報漏れ）。
 * これはカレンダーに限らず、所属情報を読む全ての画面に共通する**既存の横断的欠陥**であり、
 * 本 composable 内の自衛では性質上どうやっても塞ぎきれない。
 * 根治は `useAuthStore.logout()` 側で team/organization ストアそのものを破棄することであり、
 * マスターの裁定により**別PRで対処する**（本戦役 W2-a の範囲外）。
 * この自衛は「不完全だがカレンダー経路については塞いでいる」ものとして残す
 * （取り除くと状況が悪化するため）。後から読む者は「これで塞がっている」と誤解しないこと。
 */
let lastScopeStoreUserId: number | null | undefined = undefined

/** テスト専用: ユーザー切替検知のモジュールスコープ状態をリセットする。 */
export function __resetScopeStoreUserTrackingForTest(): void {
  lastScopeStoreUserId = undefined
}

/**
 * エラーから HTTP ステータスを取り出す（ofetch の FetchError は statusCode と response.status の双方を持つ）。
 * ネットワーク断・タイムアウトなど応答が無い失敗では undefined を返す。
 */
export function extractHttpStatus(error: unknown): number | undefined {
  const e = error as { statusCode?: number; status?: number; response?: { status?: number } } | null
  return e?.statusCode ?? e?.status ?? e?.response?.status
}

/**
 * TODO レイヤの取得失敗について、この層から追加のトーストを出してよいかを判定する（Issue #2637）。
 *
 * <p>useApi の onResponseError は次の status で既にトーストを出している（実コードで確認）:
 * <ul>
 *   <li>429: 即時に severity=warn のレート制限トースト（集約しない）</li>
 *   <li>5xx: 500ms 集約後に severity=error のサーバーエラートースト</li>
 * </ul>
 * これらで TODO 側もトーストを出すと、同じ1回の失敗で警告が2件出る。
 * よって共通ハンドラが出さない失敗（403・404 等の 4xx、応答なしのネットワーク失敗）に限り、
 * この層からユーザーへ提示する。共通側が出す場合でも todosFailed と常設注記は必ず立てる。</p>
 */
export function shouldNotifyTodoLoadFailure(status: number | undefined): boolean {
  if (status === undefined) return true // 応答なし（ネットワーク断・タイムアウト）: 共通ハンドラは何も出さない
  if (status === 429) return false
  if (status >= 500) return false
  return true
}

/**
 * 自己担当TODOの表示可否。リンク先予定が既に見えている場合は予定を正本として抑止する。
 */
export function shouldDisplayMyCalendarTodo(todo: MyCalendarTodo, visibleScheduleIds: Set<number>): boolean {
  return !!todo.dueDate
    && todo.status !== 'COMPLETED'
    && (!todo.linkedScheduleId || !visibleScheduleIds.has(todo.linkedScheduleId))
}

/**
 * API がフィルタ済みであることを正本としつつ、現在ユーザーIDが得られる場面だけ防御的に確認する。
 * ID 未初期化時に予定を消してしまわないため、unknown は常に表示する。
 */
export function shouldDisplayScheduleForCurrentUser(
  targetMode: CalendarEntryRaw['targetMode'],
  targets: CalendarEntryRaw['targets'],
  currentUserId: number | null | undefined,
): boolean {
  if (targetMode !== 'SELECTED_MEMBERS' || currentUserId == null || !targets?.length) return true
  return targets.some(target => target.userId === currentUserId)
}

/** 共有予定の詳細API・画面URLに渡す公開スコープIDを選ぶ。旧応答は内部IDへフォールバックする。 */
export function resolveCalendarScopeRouteId(scope: CalendarEntryRaw['scope']): string {
  return scope?.scopeSlug ?? String(scope?.scopeId ?? '')
}

export function useMyCalendarData() {
  const scheduleApi = useScheduleApi()
  const ganttApi = useTodoGantt()
  const { buildDayStartStr, buildDayEndStr } = useDatetime()
  const errorHandler = useErrorHandler()
  const notification = useNotification()
  const errorReport = useErrorReport()
  const authStore = useAuthStore()
  const teamStore = useTeamStore()
  const orgStore = useOrganizationStore()
  const { t } = useI18n()

  const extendedEvents = ref<CalEvent[]>([])
  /** `/me/calendar-layers` 由来のレイヤー一覧。events とは独立に取得し、月移動では再取得しない（AC-03）。 */
  const layers = ref<CalendarLayerView[]>([])
  const layersLoaded = ref(false)
  /** P2修繕: レイヤー一覧の取得に失敗したか（部分失敗をユーザーと利用側に見せる。todosFailed と同じ流儀）。 */
  const layersFailed = ref(false)
  /**
   * P2修繕: レイヤー取得が初回から失敗し、選択肢を組み立てられない間の劣化モード。
   * true の間は filteredEvents がスコープフィルタを掛けず全件表示する
   * （「レイヤーが読めない＝予定も見えない」という前回修繕漏れの再発防止）。
   * loadLayers が成功すると自動的に解除し、通常の初期選択へ復帰する。
   */
  const layersDegraded = ref(false)
  const view = ref<CalendarViewMode>('month')
  /** TODO レイヤの取得に失敗したか（Issue #2637: 部分失敗をユーザーと利用側に見せる） */
  const todosFailed = ref(false)

  const fetcher = async (from: string, to: string): Promise<CalendarEventItem[]> => {
    // TODO 取得だけは部分失敗として扱う（カレンダー本体＝個人予定・共有予定・reflection は描画を続ける）。
    // ただし失敗は握りつぶさず、必ずエラー報告に載せ、失敗状態（todosFailed）を立てる（Issue #2637）。
    const fetchTodos = async (): Promise<{ data: MyCalendarTodo[] }> => {
      try {
        const res = await ganttApi.getMyCalendarTodos(from, to)
        todosFailed.value = false
        return res
      }
      catch (e) {
        const context = `useMyCalendarData.getMyCalendarTodos(${from}..${to})`
        if (shouldNotifyTodoLoadFailure(extractHttpStatus(e))) {
          // 共通ハンドラが提示しない失敗（4xx・応答なし）。共通エラーハンドラへ委譲し、
          // errorReport への記録とユーザー提示の双方を任せる。
          errorHandler.handleApiError(e, context)
        }
        else {
          // 429 / 5xx は useApi の onResponseError が既にトーストを出しているため、
          // ここで重ねない（二重トースト防止）。ただし「TODO レイヤの失敗」という文脈は
          // 共通側の記録に残らないため、調査情報として静かに追加報告する（握りつぶさない）。
          errorReport.captureQuiet(e, { context })
        }
        todosFailed.value = true
        return { data: [] }
      }
    }

    const [personal, shared, todosRes] = await Promise.all([
      scheduleApi.listPersonalSchedules({ from, to }),
      scheduleApi.getCalendarRange(from, to),
      fetchTodos(),
    ])

    const personalEvents = ((personal.data ?? []) as unknown as PersonalScheduleRaw[]).map((e): CalEvent => ({
      id: e.id,
      scheduleId: e.id,
      uniqueKey: `personal:${e.id}`,
      title: e.content?.title ?? '',
      startAt: e.time?.startAt ?? '',
      endAt: e.time?.endAt ?? '',
      allDay: e.time?.allDay ?? false,
      color: e.content?.color ?? '#22c55e',
      isPersonal: true,
      scopeType: 'PERSONAL',
      scopeId: undefined,
      scopeName: null,
    }))

    const sharedRaw = (shared.data as unknown as CalendarEntryRaw[]) ?? []

    // reflection 等 UUID 主キードメインの印（referenceKind を持つ・id=null）。
    // §6.2/AC-21: 既存の id 依存ルックアップが id=null で壊れるのを防ぐため、ここで分岐して
    // 一意キーを referenceUuid+referenceKind から作る。reflection 行は scopeType=PERSONAL で来るため
    // 下の PERSONAL 除外フィルタより先に拾う。
    const reflectionEvents = sharedRaw
      .filter((e) => !!e.content?.referenceKind && !!e.content?.referenceUuid)
      .map((e): CalEvent => ({
        id: -1, // UUID 主キーゆえ数値 id は持たない（ルックアップ/描画は uniqueKey を使う）
        scheduleId: null, // reflection 行は親 schedules を持たない
        uniqueKey: `ref:${e.content.referenceKind}:${e.content.referenceUuid}`,
        title: e.content?.title ?? '',
        startAt: e.time?.startAt ?? '',
        endAt: e.time?.endAt ?? '',
        allDay: e.time?.allDay ?? true,
        // 想起予定=橙、振り返り記入=藍。CalendarGrid の凡例と整合。
        color: e.content?.referenceKind === 'REFLECTION_RECALL' ? '#f59e0b' : '#6366f1',
        isPersonal: true,
        isReflection: true,
        referenceUuid: e.content?.referenceUuid ?? null,
        referenceKind: e.content?.referenceKind ?? null,
        eventType: e.content?.eventType ?? undefined,
        scopeType: 'PERSONAL',
        scopeId: undefined,
        scopeName: null,
      }))

    const sharedEvents = sharedRaw
      // reflection 等 UUID ドメイン行は上で別途処理済み。残りの PERSONAL 行は除外（重複防止）。
      .filter((e) => !e.content?.referenceKind && e.scope?.scopeType !== 'PERSONAL')
      .filter(e => shouldDisplayScheduleForCurrentUser(e.targetMode, e.targets, authStore.currentUser?.id))
      .map((e): CalEvent => ({
        id: e.id as number,
        scheduleId: e.scheduleId ?? null,
        uniqueKey: `shared:${e.id}`,
        title: e.content?.title ?? '',
        startAt: e.time?.startAt ?? '',
        endAt: e.time?.endAt ?? '',
        allDay: e.time?.allDay ?? false,
        // F03.19 §4.6/§5.2: BE が解決済みの色を使う（FE でハッシュ計算しない）。
        // 未デプロイ環境等で content.color が来ない場合のみ null へフォールバックする（明示的な劣化）。
        color: e.content?.color ?? null,
        colorSource: e.content?.colorSource ?? undefined,
        isPersonal: false,
        scopeType: e.scope?.scopeType ?? '',
        // P1修繕: scopeId は必ずレイヤー API と同じ「数値ID文字列」にする（フィルタ照合用）。
        // slug が要るのは詳細API・画面URLの経路のみで、それは scopeRouteId 側に分離した。
        scopeId: e.scope?.scopeId != null ? String(e.scope.scopeId) : undefined,
        // 詳細API・画面URLは内部数値IDではなく公開slugを要求する。
        scopeRouteId: resolveCalendarScopeRouteId(e.scope),
        scopeName: e.scope?.scopeName ?? null,
        scopeIconUrl: e.scope?.scopeIconUrl ?? null,
        targetMode: e.targetMode,
        targetCount: e.targetCount,
        targets: e.targets,
      }))

    // 期限付き TODO をカレンダーに追加（完了済みは除外）
    // ID は負数にしてスケジュール ID と衝突しないようにする
    const visibleScheduleIds = new Set([
      ...personalEvents.map(event => event.scheduleId),
      ...sharedEvents.map(event => event.scheduleId),
    ].filter((id): id is number => id !== null && id !== undefined))
    const todos = todosRes.data ?? []
    const todoEvents: CalEvent[] = todos
      .filter(t => shouldDisplayMyCalendarTodo(t, visibleScheduleIds))
      .map((t) => ({
        id: -(t.id + 1),
        uniqueKey: `todo:${t.id}`,
        title: t.title,
        // TODO の期限は LocalDate。ユーザーTZの 00:00:00 / 23:59:59 としてオフセット付きで組む
        // （ナイーブ連結だと表示時に再度 TZ 変換されて日がずれる。Issue #2508 Phase 2）
        startAt: buildDayStartStr(t.startDate ?? t.dueDate),
        endAt: buildDayEndStr(t.dueDate),
        allDay: true,
        color: t.priority === 'HIGH' ? '#f97316'
          : t.priority === 'LOW' ? '#22c55e'
          : '#3b82f6',
        isPersonal: t.scopeType === 'PERSONAL',
        scopeType: t.scopeType,
        // TODO は元々レイヤー API と同じ数値IDを持つ（MyCalendarTodo.scopeId: number）。
        scopeId: t.scopeId == null ? undefined : String(t.scopeId),
        scopeRouteId: t.scopeSlug ?? (t.scopeId == null ? undefined : String(t.scopeId)),
        scopeName: t.scopeName,
        scopeIconUrl: null,
        isTodo: true,
      }))

    const merged = [...personalEvents, ...sharedEvents, ...reflectionEvents, ...todoEvents]
    extendedEvents.value = merged
    return merged
  }

  /**
   * 直近の予定取得（`loadEvents` / `refresh`）が失敗したか。
   *
   * `useCalendarEvents` の `refresh()` は例外を内部で捕らえて**正常に解決する**
   * （月移動で画面全体が落ちないための既存の設計であり、他の呼び出し元がその挙動に
   * 依存しているため消さない）。そのぶん、失敗を知りたい呼び出し箇所は
   * このフラグで検知する。`onError` は `loadEvents`/`refresh` の失敗時にのみ呼ばれる。
   */
  const eventsFetchFailed = ref(false)

  const { currentYear, currentMonth, events, loading, calendarLoading, loadEvents, refresh, onPrevMonth, onNextMonth, goToToday, navigateTo } =
    useCalendarEvents(fetcher, {
      cacheHalfMonths: 0,
      onError: (error: unknown) => {
        eventsFetchFailed.value = true
        // ユーザー提示は各呼び出し箇所の文脈で行う（ここで一律にトーストを出すと
        // useApi の共通ハンドラと二重になる）。調査用の記録だけは必ず残す。
        errorReport.captureQuiet(error, { context: 'useMyCalendarData.calendarEvents' })
      },
    })

  /**
   * 予定を取り直し、**成功したかを返す**（`refresh()` は失敗しても解決するため、
   * 戻り値だけでは成否が分からない）。呼び出し前にフラグを倒してから測る。
   */
  async function refreshEventsChecked(): Promise<boolean> {
    eventsFetchFailed.value = false
    await refresh()
    return !eventsFetchFailed.value
  }

  /**
   * レイヤー一覧の取得（§5.1）。events とは独立。月移動では呼ばない（AC-03）。
   *
   * P2修繕: 失敗しても re-throw しない。設計書 §5.1/AC-03 は layers と events を独立取得する
   * 方針であり、レイヤー取得の失敗で予定の取得（loadEvents）まで止まってはならない
   * （呼び出し元 initStorage が reject すると calendar.vue の onMounted が後続の
   * loadEvents を一度も呼ばずに終わり、取得できるはずの予定まで空になっていた）。
   * ただし握りつぶしはしない — errorHandler へ委譲して報告し、layersFailed で
   * 利用側（画面）に部分失敗を明示できるようにする（todosFailed と同じ流儀）。
   */
  async function loadLayers(): Promise<void> {
    try {
      // P2修繕: 同一SPAセッション内でのユーザー切替を検知する。useAuthStore.logout() は
      // team/organization ストアをクリアしないため、旧ユーザーの所属データが残ったままだと
      // 下の「取得済みなら再取得しない」判定が誤って再取得をスキップし、新ユーザーの
      // 所属チーム／組織が作成スコープ候補から消える。ここではカレンダー機能側の自衛として
      // ユーザーIDの変化を検知したときだけストアを強制的に空にし、再取得させる
      // （ストア自体のクリアは logout 側の根治であり本戦役の範囲外・別課題として報告する）。
      // 【限界】本 composable を経由した切替しか捕捉できない。別画面でストアが読まれてから
      // ログアウト→別ユーザーが初めてカレンダーを開いた場合は捕捉できない（詳細は
      // lastScopeStoreUserId の宣言部コメント参照）。
      const currentUserId = authStore.currentUser?.id ?? null
      if (lastScopeStoreUserId !== undefined && lastScopeStoreUserId !== currentUserId) {
        teamStore.myTeams = []
        orgStore.myOrganizations = []
      }
      lastScopeStoreUserId = currentUserId

      // 作成スコープ選択（availableScopes）が要る slug を、レイヤー API とは別経路
      // （既存の /me/teams・/me/organizations を持つ team/organization ストア）から補う。
      // 未取得なら取得する（取得済みなら再取得しない＝月移動等で無駄打ちしない）。
      const [res] = await Promise.all([
        scheduleApi.getMyCalendarLayers(),
        teamStore.myTeams.length ? Promise.resolve() : teamStore.fetchMyTeams(),
        orgStore.myOrganizations.length ? Promise.resolve() : orgStore.fetchMyOrganizations(),
      ])
      layers.value = ((res.data ?? []) as unknown as CalendarLayerRaw[]).map(normalizeLayer)
      layersLoaded.value = true
      layersFailed.value = false
      if (layersDegraded.value) {
        // P2修繕: 障害から回復した。劣化モード（フィルタなしの全件表示）を解除し、
        // 通常の初期選択（hidden=false 全選択）へ復帰する。
        layersDegraded.value = false
        selectedScopes.value = layers.value.filter((l) => !l.hidden).map((l) => layerKey(l.scopeType, l.scopeId))
      }
    }
    catch (e) {
      layersFailed.value = true
      errorHandler.handleApiError(e, 'useMyCalendarData.getMyCalendarLayers')
      // layersLoaded は false のまま。layers.value は空配列のまま（初期値）で、
      // 呼び出し元（initStorage）はレイヤー情報無しの前提で続行できる。
    }
  }

  function layerKey(scopeType: string, scopeId: number | string): string {
    return `${scopeType}:${scopeId}`
  }

  function layerLabel(l: CalendarLayerView): string {
    if (l.scopeType === 'PERSONAL' && l.scopeNameKey) return t(l.scopeNameKey)
    return l.scopeName || t('schedule.calendar.layer.unknown')
  }

  /**
   * scopeType:数値scopeId → slug のマップ。team/organization ストア
   * （既存の GET /me/teams・GET /me/organizations。design doc §4.2 参照）由来。
   *
   * P2修繕: レイヤー API（`/me/calendar-layers`）は slug を持たないため、作成スコープ選択
   * （`availableScopes`）をレイヤー由来にする際に slug が必要になる。events から拾う旧実装は
   * 「表示月に予定が1件も無い所属チーム／組織が作成候補に出ない」という鶏卵問題を持つため、
   * slug の出所を events から切り離し、この別経路（team/organization ストア）に一本化する。
   */
  const scopeSlugMap = computed(() => {
    const map = new Map<string, string>()
    for (const t of teamStore.myTeams) map.set(layerKey('TEAM', t.id), t.slug)
    for (const o of orgStore.myOrganizations) map.set(layerKey('ORGANIZATION', o.id), o.slug)
    return map
  })

  /**
   * 作成スコープ選択 UI（`createScopeOptions`）向けの候補。
   *
   * P1/P2修繕: 予定の有無に依存しないよう、レイヤー API（`/me/calendar-layers`）を典拠にする
   * （§5.2）。ただしレイヤー API は slug を返さないため、作成 API（`scheduleApi.createSchedule`
   * 等・buildBase 経由で公開スコープID＝slug を要求する）に渡す `scopeId` は scopeSlugMap から
   * 補う。**表示フィルタ用の数値キー（allScopeOptions/layerKeySet 側）とはここで初めて
   * 交わる**が、この ScopeOption.value はあくまで「作成スコープ選択」専用の値であり、
   * 表示フィルタ（selectedScopes）へ混入させてはならない（結合切り自体は W2-b の担当）。
   * slug が解決できない（team/organization ストア未取得・該当スコープが所属外 等）間は、
   * 誤った ID を渡して 404 を起こすより「候補に出さない」方を選ぶ（対処療法禁止）。
   */
  const availableScopes = computed<ScopeOption[]>(() => {
    const result: ScopeOption[] = []
    for (const l of layers.value) {
      if (l.scopeType === 'PERSONAL') continue
      const slug = scopeSlugMap.value.get(layerKey(l.scopeType, l.scopeId))
      if (!slug) continue
      result.push({
        label: layerLabel(l),
        value: `${l.scopeType}:${slug}`,
        scopeType: l.scopeType,
        scopeId: slug,
        // P2修繕: layers.value の走査中にしか分からない数値キーを、slug と一緒にここで確定させる。
        // 後から名前（label）で逆引きすると、チーム名の一意性が保証されないため誤対応しうる。
        filterKey: layerKey(l.scopeType, l.scopeId),
      })
    }
    return result
  })

  /**
   * レイヤー一覧に存在しないスコープの予定を集めたフォールバックチップ（§5.2.1・AC-23）。
   * 「イベントが1件でも存在するスコープには必ず対応するチップが存在する」不変条件を守る。
   */
  const layerKeySet = computed(() => new Set(layers.value.map((l) => layerKey(l.scopeType, l.scopeId))))

  function eventLayerKey(ext: CalEvent): string {
    if (ext.isPersonal || ext.scopeType === 'PERSONAL') return PERSONAL_KEY
    return layerKey(ext.scopeType ?? '', ext.scopeId ?? '')
  }

  /**
   * フォールバックチップの色を決める（§5.2.1 は「§3.3 の自動色」を要求する）。
   *
   * **FE で自動色を算出してはならない**（§3.3「FE 側にハッシュ実装を持たない」）。
   * そこで、そのスコープの予定のうち **`colorSource === 'LAYER_AUTO'`** のもの、すなわち
   * BE が自動色そのものを載せて返した予定の色を採る。これは BE 由来の自動色であり、
   * §5.2.1 の要求と §3.3 の禁止を同時に満たす唯一の経路である。
   *
   * **限界（設計書の要求を完全には満たせない）**: そのスコープの予定が全て予定自身の色
   * （`SCHEDULE`）やカテゴリ色（`CATEGORY`）で塗られている場合、応答のどこにも
   * そのスコープの自動色は載っていない（`/my/calendar` は解決済み色と由来しか返さず、
   * 自動色を別フィールドで返さない。`/me/calendar-layers` は所属スコープしか返さず、
   * フォールバックは定義上そこに現れない）。その場合は**予定の色を借りず中立色を使う** —
   * 予定色やカテゴリ色をチップ色に流用すると「チップの色＝そのレイヤーの色」という
   * 読みが崩れ、レイヤー色と食い違う嘘になるためである。
   * 恒久解は BE 応答に自動色（またはフォールバックスコープを含むレイヤー行）を載せること
   * であり、別工程・別 PR の範囲。
   */
  function fallbackChipColor(key: string): string {
    for (const e of extendedEvents.value) {
      if (eventLayerKey(e) !== key) continue
      if (e.colorSource === 'LAYER_AUTO' && e.color) return e.color
    }
    return FALLBACK_CHIP_COLOR
  }

  const fallbackScopeOptions = computed<ScopeOption[]>(() => {
    if (!layersLoaded.value) return []
    const seen = new Set<string>()
    const result: ScopeOption[] = []
    for (const e of extendedEvents.value) {
      const key = eventLayerKey(e)
      if (key === PERSONAL_KEY || layerKeySet.value.has(key) || seen.has(key)) continue
      seen.add(key)
      result.push({
        label: e.scopeName ?? t('schedule.calendar.layer.unknown'),
        value: key,
        scopeType: e.scopeType ?? '',
        scopeId: e.scopeId ?? '',
        // §6.4: フォールバックチップも通常チップと同じ見た目（色ドット＋名前）で並べる。
        // 色は BE 由来の自動色のみを採用する（上記 fallbackChipColor の限界コメント参照）。
        // 色変更は開かない（isFallback）。
        color: fallbackChipColor(key),
        isFallback: true,
      })
    }
    return result
  })

  /** レイヤー一覧（BE 由来・予定の有無に依存しない）＋フォールバックチップ（§5.1/§5.2.1）。 */
  const allScopeOptions = computed<ScopeOption[]>(() => [
    ...layers.value.map((l) => ({
      label: layerLabel(l),
      value: layerKey(l.scopeType, l.scopeId),
      scopeType: l.scopeType,
      scopeId: String(l.scopeId),
      // §6.4: チップは色ドット＋名前を併記する。色は BE 解決済みの値をそのまま使う。
      color: l.color,
      colorSource: l.colorSource,
      isFallback: false,
      layerScopeId: l.scopeId,
    })),
    ...fallbackScopeOptions.value,
  ])

  const selectedScopes = ref<string[]>([])
  /** P2修繕: 自動選択済みフォールバックキーの記録（§5.2.1）。initStorage で永続化状態から復元する。 */
  const knownFallbackKeys = ref<Set<string>>(new Set())

  // §5.2: extendedEvents を uniqueKey で索引化し、filteredEvents は O(N) の Map 参照にする
  // （旧実装はイベント1件ごとに extendedEvents を線形探索する O(N²) だった）。
  // extendedEvents の配列自体は既存利用箇所（CalendarGrid 等）のため残す。
  const eventsByUniqueKey = computed(() => {
    const map = new Map<string, CalEvent>()
    for (const e of extendedEvents.value) map.set(e.uniqueKey, e)
    return map
  })

  const filteredEvents = computed(() =>
    events.value.filter((e) => {
      // §6.2/AC-21: id は reflection 行で衝突する（id=null/-1）ため uniqueKey でルックアップする。
      const ext = eventsByUniqueKey.value.get(e.uniqueKey)
      if (!ext) return false
      // P2修繕: レイヤー取得が初回から失敗し選択肢を組み立てられない間は、フィルタを掛けず
      // 全件表示に劣化させる。「レイヤーが読めない＝予定も一切見えない」を再発させないため。
      if (layersDegraded.value) return true
      return selectedScopes.value.includes(eventLayerKey(ext))
    }),
  )

  function toggleScope(value: string) {
    const idx = selectedScopes.value.indexOf(value)
    if (idx >= 0) selectedScopes.value = selectedScopes.value.filter((_, i) => i !== idx)
    else selectedScopes.value = [...selectedScopes.value, value]
  }

  const multiSelectScopes = computed({
    get: () => [...selectedScopes.value],
    set: (vals: string[]) => { selectedScopes.value = vals },
  })

  /**
   * P1修繕: 劣化モード（layersDegraded）中は永続化しない。
   *
   * 劣化中の selectedScopes=[] は「ユーザーが意図して全解除した」のではなく
   * 「レイヤー一覧を組み立てられず選択肢そのものが無かった」ことの副産物であり、
   * 正当な選択状態ではない。これを他の選択状態と区別せず保存すると、次回リロード時に
   * initStorage が「永続化済みの正当な選択」として復元してしまい、レイヤー API が
   * 回復してイベントが取れても空の選択のまま全件が弾かれ続ける
   * （一度の API 障害が再読み込み後も消えない「カレンダーが真っ白」状態を作る）。
   * 劣化状態そのものを保存対象から外すことで、次回 initStorage は localStorage に
   * 何も無い状態から再判定する（layersLoaded の成否に応じて通常初期化 or 再度劣化）。
   */
  function persistLayerState(): void {
    if (layersDegraded.value) return
    try {
      const payload: LayerStateV2 = {
        version: 2,
        selected: selectedScopes.value,
        view: view.value,
        knownFallbackKeys: [...knownFallbackKeys.value],
      }
      localStorage.setItem(LAYER_STATE_KEY, JSON.stringify(payload))
    }
    catch { /* localStorage 不可時は永続化を諦めるが、選択状態そのものはメモリ上で機能し続ける */ }
  }

  watch(selectedScopes, persistLayerState, { deep: true })
  watch(view, persistLayerState)

  // §5.2.1: レイヤー一覧に無いフォールバックチップは「初めて現れたときだけ」既定選択にする（AC-23）。
  // P2修繕: knownFallbackKeys に無いキーだけを新規とみなす。一度選択したキーをユーザーが
  // 明示的に外した後、月移動・再取得のたびに selectedScopes へ戻すと「外せない」バグになる。
  watch(fallbackScopeOptions, (opts) => {
    const newlySeen = opts.map((o) => o.value).filter((v) => !knownFallbackKeys.value.has(v))
    if (!newlySeen.length) return
    // P2修繕: knownFallbackKeys を持たない旧 V2 状態から移行した直後は、既に selectedScopes に
    // 入っている値（=以前のセッションで選択済みだったキー）も newlySeen に紛れ込む。
    // それを無条件で追加すると selectedScopes に同じキーが二重登録され、toggleScope は
    // indexOf ベースで1件しか消さないため「最初のクリックでチップが外れない」バグになる。
    // 既に選択済みのキーは既知集合への登録だけ行い、選択の追加はしない。
    const toAdd = newlySeen.filter((v) => !selectedScopes.value.includes(v))
    for (const v of newlySeen) knownFallbackKeys.value.add(v)
    if (toAdd.length) selectedScopes.value = [...selectedScopes.value, ...toAdd]
    persistLayerState() // knownFallbackKeys の更新も即座に永続化する（selectedScopes の watch と二重書きにはなるが冪等）
  })

  /**
   * localStorage 初期化（§5.3）。レイヤー一覧を（未取得なら）取得したうえで、
   * 1) 新キーがあればそれを使う 2) 無ければ旧キーから移行する 3) どちらも無ければ
   * `hidden=false` のレイヤーを全選択する（AC-15c）。移行そのものは
   * migrateLegacyStorage 内でセッション中1度だけに限定される（R15）。
   */
  async function initStorage(): Promise<void> {
    if (!layersLoaded.value) await loadLayers()

    migrateLegacyStorage()

    try {
      const raw = localStorage.getItem(LAYER_STATE_KEY)
      if (raw) {
        const parsed = JSON.parse(raw) as Partial<LayerStateV2> | string[]
        // 移行直後を含め、新形式 { version, selected, view } を前提とする。
        const selected = Array.isArray(parsed) ? parsed : parsed.selected
        if (Array.isArray(selected)) {
          selectedScopes.value = selected.map(migrateLegacyScopeValue)
          const parsedView = Array.isArray(parsed) ? undefined : parsed.view
          view.value = isValidView(parsedView) ? parsedView : 'month'
          // P2修繕: 永続化済みの knownFallbackKeys を復元する。復元し損ねると、リロードのたびに
          // 「初めて見た」扱いになり、ユーザーが外したフォールバックチップが復活してしまう。
          const parsedKnown = Array.isArray(parsed) ? undefined : parsed.knownFallbackKeys
          knownFallbackKeys.value = new Set(Array.isArray(parsedKnown) ? parsedKnown.filter((v): v is string => typeof v === 'string') : [])
          return
        }
      }
    }
    catch { /* 壊れたデータは初期化フォールバックへ */ }

    if (!layersLoaded.value) {
      // P2修繕: レイヤー取得が失敗し、かつ永続化済みの選択も無い（初回利用中の障害）。
      // layers.value が空のため「hidden=false を全選択」は空集合になり、そのまま selectedScopes
      // へ適用すると全予定が弾かれて画面が真っ白になる（前回修繕が達成できていなかった箇所）。
      // 選択肢を組み立てられない以上フィルタは機能させようがないため、劣化モードへ入り
      // filteredEvents 側でフィルタそのものを迂回させ、取得できた予定は見えるようにする。
      layersDegraded.value = true
      selectedScopes.value = []
      view.value = 'month'
      return
    }

    // 初回訪問、または localStorage が壊れている場合: hidden=false のレイヤーを全選択（P1・AC-15c）。
    selectedScopes.value = layers.value.filter((l) => !l.hidden).map((l) => layerKey(l.scopeType, l.scopeId))
    view.value = 'month'
  }

  /**
   * レイヤー設定の変更でサーバーが返したエラーを、設計書 §7 のコードに対応する
   * §8 の文言でユーザーへ見せる。既知コード以外は共通ハンドラへ委譲する。
   * **握りつぶさない** — 必ずどちらかの経路で表示・記録する。
   */
  function reportLayerError(e: unknown, context: string): void {
    const code = (e as { data?: { error?: { code?: string } } })?.data?.error?.code
    const messageKey = LAYER_ERROR_MESSAGE_KEYS[code ?? '']
    if (messageKey) {
      errorReport.captureQuiet(e, { context })
      notification.error(t('dialog.error'), t(messageKey))
      return
    }
    errorHandler.handleApiError(e, context)
  }

  /** レイヤー一覧の該当行を差し替える（PATCH 応答・DELETE 後の再取得結果の反映）。 */
  function replaceLayer(updated: CalendarLayerView): void {
    const key = layerKey(updated.scopeType, updated.scopeId)
    layers.value = layers.value.map((l) => (layerKey(l.scopeType, l.scopeId) === key ? updated : l))
  }

  /**
   * 表示中の予定の色を、変更後のレイヤー色で塗り替える（§10 のキャッシュ方針・P2）。
   *
   * レイヤー色は §3.4 の優先1であり、**そのスコープの予定は例外なくこの色になる**。
   * よって PATCH 成功後にローカルで塗り替えれば BE の再解決と必ず一致する
   * （再取得を強いない＝月移動やリロードまで旧色が残る不整合を作らない）。
   *
   * `extendedEvents` の要素は `useCalendarEvents` の `allEvents`（`events`/`filteredEvents` の出所）と
   * **同一オブジェクト**である（fetcher が返した配列がそのまま両者に入る）ため、ここでの
   * in-place 更新はグリッド・アジェンダの描画にもそのまま届く。
   *
   * TODO と reflection は §3.4.1 でレイヤー色の対象外（固定色が優先度・種別の意味を担う）
   * ため塗り替えない。
   */
  function applyLayerColorToLoadedEvents(scopeType: string, scopeId: number, color: string): void {
    const key = layerKey(scopeType, scopeId)
    for (const e of extendedEvents.value) {
      if (e.isTodo || e.isReflection) continue
      if (eventLayerKey(e) !== key) continue
      e.color = color
    }
  }

  /**
   * レイヤーの色をユーザー指定色へ変更する（§4.4 の PATCH）。
   *
   * **`color` のみ送る。`hidden` は送らない**ため現在値が保たれる（AC-08b）。
   * 成功したらチップだけでなく**表示中の予定の色も同時に更新する**（§10 のキャッシュ方針は
   * 「色変更直後に反映されない不整合」を害と明記しており、FE 側で同じ不整合を作らない）。
   *
   * こちらは PATCH 応答だけで完結し、**失敗を内部で捕らえる関数（`refresh` / `loadLayers`）を
   * 一切呼ばない**。よって「途中の失敗を握りつぶしたまま true を返す」経路は存在しない
   * （PATCH 自体が失敗すれば catch に入り false を返す）。
   *
   * @returns 成功したか。失敗時はユーザーへ通知済み（例外は投げ直さない）。
   */
  async function setLayerColor(scopeType: string, scopeId: number, color: string): Promise<boolean> {
    try {
      const res = await scheduleApi.updateMyCalendarLayer(
        scopeType as CalendarLayerScopeType, scopeId, { color },
      )
      const updated = normalizeLayer(res.data as unknown as CalendarLayerRaw)
      replaceLayer(updated)
      // 応答が返した色（BE の正規化後の値）で塗る。送った文字列をそのまま使わない。
      applyLayerColorToLoadedEvents(scopeType, scopeId, updated.color)
      return true
    }
    catch (e) {
      reportLayerError(e, 'useMyCalendarData.setLayerColor')
      return false
    }
  }

  /**
   * レイヤーの色設定を消して自動色へ戻す（§4.5 の DELETE）。
   *
   * PATCH の `color: null` は「変更しない」であって「自動に戻す」ではないため、
   * この操作は必ず DELETE で行う（§4.4 の注記）。DELETE は応答本文を持たないので、
   * 解決後の自動色を知るためにレイヤー一覧を取り直す。
   *
   * **予定の色は `refresh()` で取り直す。** リセット後の各予定の色は §3.4 の優先2〜4
   * （予定自身の色 → カテゴリ色 → 自動色）で個別に決まり、**どれが効くかは予定ごとに違う**。
   * FE はカテゴリ色も自動色も持たない（§3.3 は FE でのハッシュ実装を禁じている）ため、
   * ローカルでは正しい色を作れない。ここだけは BE に解かせるのが唯一の正解である。
   * ユーザー操作を待たずその場で取り直すので、旧色が残ることはない（`refresh` は
   * 全画面スピナーを出さない静かな再取得）。
   *
   * @returns 成功したか。失敗時はユーザーへ通知済み（例外は投げ直さない）。
   */
  async function resetLayerColor(scopeType: string, scopeId: number): Promise<boolean> {
    try {
      await scheduleApi.deleteMyCalendarLayer(scopeType as CalendarLayerScopeType, scopeId)
      // loadLayers も refresh も失敗を内部で捕らえて解決するため、Promise.all の完了は
      // 「両方成功した」ことを意味しない。**それぞれの失敗フラグで測る**
      // （握りつぶしの上に「成功しました」という嘘を重ねない）。
      const [, eventsOk] = await Promise.all([loadLayers(), refreshEventsChecked()])
      if (!eventsOk || layersFailed.value) {
        // 設定の削除自体は成功している（サーバー上は自動色に戻っている）。
        // 食い違っているのは手元の表示だけなので、そう伝える。
        notification.error(t('dialog.error'), t('schedule.calendar.error.colorResetRefreshFailed'))
        return false
      }
      return true
    }
    catch (e) {
      reportLayerError(e, 'useMyCalendarData.resetLayerColor')
      return false
    }
  }

  return {
    setLayerColor, resetLayerColor,
    currentYear, currentMonth, events, loading, calendarLoading, loadEvents, refresh, onPrevMonth, onNextMonth, goToToday, navigateTo,
    extendedEvents, todosFailed, availableScopes, allScopeOptions, selectedScopes, filteredEvents,
    toggleScope, multiSelectScopes, initStorage,
    layers, layersLoaded, layersFailed, layersDegraded, loadLayers, view,
  }
}
