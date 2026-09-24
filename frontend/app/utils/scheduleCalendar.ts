/**
 * スケジュール API（ネスト ScheduleResponse）→ フロントの平坦な型への変換ユーティリティ。
 *
 * バックエンドの `ScheduleResponse` は content / time / scope / academic / audit に
 * ネストされた構造を返す（backend: com.mannschaft.app.schedule.dto.ScheduleResponse）。
 * 一方カレンダー描画（CalendarGrid.vue / useCalendarEvents.ts）と EventDetailPanel.vue は
 * `title` / `startAt` / `endAt` のような平坦なフィールドを期待する。
 *
 * 以前の実装は `res.data as CalendarEventItem[]` という嘘の型キャストで素通しし、
 * `event.title` / `event.startAt` が常に undefined になりカレンダーにイベントが描画されなかった。
 * 本ユーティリティでネスト→平坦へ正しく変換することで根治する。
 *
 * 変換は純関数として切り出し、organizations/[slug]/schedule.vue と teams/[slug]/schedule.vue で
 * 共有する（重複ロジック回避・ユニットテスト容易化）。
 */
import type { CalendarEventItem } from '~/composables/useCalendarEvents'
import type { components } from '~/types/generated'

/** バックエンド ScheduleResponse のネスト構造（必要なフィールドのみ）。 */
export interface NestedScheduleResponse {
  id: number
  content?: {
    title?: string | null
    status?: string | null
    eventType?: string | null
    location?: string | null
    attendanceRequired?: boolean | null
  } | null
  time?: {
    startAt?: string | null
    endAt?: string | null
    allDay?: boolean | null
  } | null
  scope?: {
    scopeName?: string | null
    scopeIconUrl?: string | null
  } | null
  academic?: {
    eventCategory?: {
      id?: number | null
      name?: string | null
      color?: string | null
    } | null
  } | null
  audit?: {
    createdByDisplayName?: string | null
  } | null
  myAttendanceStatus?: string | null
  targetMode?: 'ALL_MEMBERS' | 'SELECTED_MEMBERS'
  targetCount?: number
  targets?: Array<{
    userId: number
    displayName: string
    avatarUrl: string | null
    calendarColor: string | null
  }>
}

/** EventDetailPanel.vue が期待する平坦な予定詳細型。 */
export interface FlatScheduleEvent {
  id: number
  /** 親 schedules 行の ID（BE CalendarEntryResponse.scheduleId・設計書 §1.5 / AC-07(b)）。 */
  scheduleId: number | null
  title: string
  description: string | null
  location: string | null
  startAt: string
  endAt: string
  allDay: boolean
  status: string
  eventType: string | null
  categoryName: string | null
  categoryColor: string | null
  createdBy: { displayName: string }
  attendanceRequired: boolean
  myAttendance: string | null
  attendanceStats: { yes: number; no: number; maybe: number; pending: number; total: number } | null
  targetMode?: 'ALL_MEMBERS' | 'SELECTED_MEMBERS'
  targetCount?: number
  targets?: Array<{
    userId: number
    displayName: string
    avatarUrl: string | null
    calendarColor: string | null
  }>
}

/**
 * ネスト ScheduleResponse 1件 → カレンダー描画用の平坦な CalendarEventItem へ変換する。
 *
 * @param raw       バックエンドの ScheduleResponse（ネスト）
 * @param scopeType このカレンダーのスコープ種別（'TEAM' / 'ORGANIZATION'）。
 *                  CalendarGrid のアイコン表示で使用する。
 */
export function toCalendarEventItem(
  raw: NestedScheduleResponse,
  scopeType: 'TEAM' | 'ORGANIZATION',
): CalendarEventItem {
  const content = raw.content ?? {}
  const time = raw.time ?? {}
  const category = raw.academic?.eventCategory ?? null
  const scope = raw.scope ?? {}

  return {
    id: raw.id,
    scheduleId: raw.id,
    uniqueKey: `${scopeType.toLowerCase()}:${raw.id}`,
    title: content.title ?? '',
    startAt: time.startAt ?? '',
    endAt: time.endAt ?? time.startAt ?? '',
    allDay: time.allDay ?? false,
    color: category?.color ?? null,
    isPersonal: false,
    eventType: content.eventType ?? undefined,
    scopeType,
    scopeName: scope.scopeName ?? null,
    scopeIconUrl: scope.scopeIconUrl ?? null,
    // モバイルのリストビューで行内 RSVP を出し分けるために出欠情報も引き継ぐ。
    // 一覧 API は myAttendanceStatus=null を返す（詳細 GET でのみ実値・BE 現仕様）。
    attendanceRequired: content.attendanceRequired ?? false,
    myAttendance: raw.myAttendanceStatus ?? null,
    targetMode: raw.targetMode,
    targetCount: raw.targetCount,
    targets: raw.targets,
  }
}

/**
 * ネスト ScheduleResponse 配列 → CalendarEventItem 配列へ変換する。
 */
export function toCalendarEventItems(
  rawList: NestedScheduleResponse[],
  scopeType: 'TEAM' | 'ORGANIZATION',
): CalendarEventItem[] {
  return rawList.map((raw) => toCalendarEventItem(raw, scopeType))
}

/**
 * ネスト ScheduleResponse（詳細 GET）→ EventDetailPanel 用の平坦な型へ変換する。
 *
 * 詳細 GET（TeamScheduleController#getSchedule 等）は academic.eventCategory を null で返すため、
 * categoryName / categoryColor は null になる（バックエンドの現仕様）。
 * description / attendanceStats も ScheduleResponse には存在しないため null とする。
 */
export function toFlatScheduleEvent(raw: NestedScheduleResponse): FlatScheduleEvent {
  const content = raw.content ?? {}
  const time = raw.time ?? {}
  const category = raw.academic?.eventCategory ?? null

  return {
    id: raw.id,
    scheduleId: raw.id,
    title: content.title ?? '',
    description: null,
    location: content.location ?? null,
    startAt: time.startAt ?? '',
    endAt: time.endAt ?? time.startAt ?? '',
    allDay: time.allDay ?? false,
    status: content.status ?? '',
    eventType: content.eventType ?? null,
    categoryName: category?.name ?? null,
    categoryColor: category?.color ?? null,
    createdBy: { displayName: raw.audit?.createdByDisplayName ?? '' },
    attendanceRequired: content.attendanceRequired ?? false,
    myAttendance: raw.myAttendanceStatus ?? null,
    attendanceStats: null,
    targetMode: raw.targetMode,
    targetCount: raw.targetCount,
    targets: raw.targets,
  }
}

/**
 * カレンダー詳細パネル（`calendar.vue` / `WidgetMyCalendar.vue`）が表示する予定。
 * {@link FlatScheduleEvent} に、応答には含まれない「どのスコープのカレンダーから開いたか」を足したもの。
 */
export interface CalendarPanelEvent extends FlatScheduleEvent {
  scopeType?: string
  /** 画面URL・詳細APIに渡す公開スコープID（slug）。 */
  scopeId?: string
  scopeName?: string | null
  scopeIconUrl?: string | null
  color?: string | null
}

/** 応答からは分からない、開いた側（カレンダーの行）が持っている情報。 */
export interface CalendarPanelContext {
  /** 親 schedules 行の ID（コメント欄の表示ガードに使う）。 */
  scheduleId?: number | null
  scopeType?: string
  scopeId?: string
  scopeName?: string | null
  targetMode?: 'ALL_MEMBERS' | 'SELECTED_MEMBERS'
  targetCount?: number
  targets?: FlatScheduleEvent['targets']
}

/**
 * ネスト ScheduleResponse（詳細 GET）→ 詳細パネル用の平坦な型へ変換する。
 *
 * F03.19 実機E2E 欠陥1 の根治: `calendar.vue` と `WidgetMyCalendar.vue` は
 * 応答を `as EventDetail`（平坦な型）でキャストしてそのままスプレッドしており、
 * 題名・日時が常に undefined になっていた。詰め替えを各画面に書くと1画面だけ
 * 取り残されるため、**変換関数をここ1つに集約**する。
 *
 * 応答が持たない値（スコープ名など）は `ctx` で補うが、応答が値を持つ場合は
 * 応答を優先する（同じ予定について2つの真実を作らない）。
 */
export function toCalendarPanelEvent(
  raw: NestedScheduleResponse,
  ctx: CalendarPanelContext = {},
): CalendarPanelEvent {
  const flat = toFlatScheduleEvent(raw)
  return {
    ...flat,
    // 詳細 GET は親 schedules 行の ID を返さないため、カレンダー行の値をそのまま使う。
    scheduleId: ctx.scheduleId ?? null,
    // 応答に作成者名が無いときに空文字の createdBy を作らない（「作成者: 空欄」を出さない）。
    createdBy: raw.audit?.createdByDisplayName
      ? { displayName: raw.audit.createdByDisplayName }
      : { displayName: '' },
    color: flat.categoryColor,
    scopeType: ctx.scopeType,
    scopeId: ctx.scopeId,
    scopeName: raw.scope?.scopeName ?? ctx.scopeName ?? null,
    scopeIconUrl: raw.scope?.scopeIconUrl ?? null,
    targetMode: flat.targetMode ?? ctx.targetMode,
    targetCount: flat.targetCount ?? ctx.targetCount,
    targets: flat.targets ?? ctx.targets,
  }
}

/**
 * ---- 生成型（OpenAPI 由来）との整合を型で固定する番人 ----
 *
 * 欠陥1 の根本原因は「画面が読む構造」と「API が返す構造」の食い違いであり、
 * 平坦な `title` / `startAt` を読むコードがコンパイルを通ってしまった点にある。
 * そこで {@link NestedScheduleResponse} が読むフィールド名が、生成型
 * `components['schemas']['ScheduleResponse']` に**実在すること**を型で固定する。
 *
 * ここに平坦な `title` 等を足そうとすると（＝欠陥1 の再発）、生成型に無いキーなので
 * 下の代入がコンパイルエラーになる。BE 応答の構造が変わった場合も同様に落ちる。
 */
type GeneratedScheduleResponse = components['schemas']['ScheduleResponse']
type KeysExistIn<T, U> = keyof T extends keyof U ? true : { 'このキーは API 応答に存在しない': Exclude<keyof T, keyof U> }

const _scheduleResponseKeysExist: KeysExistIn<NestedScheduleResponse, GeneratedScheduleResponse> = true
const _contentKeysExist: KeysExistIn<
  NonNullable<NestedScheduleResponse['content']>,
  NonNullable<GeneratedScheduleResponse['content']>
> = true
const _timeKeysExist: KeysExistIn<
  NonNullable<NestedScheduleResponse['time']>,
  NonNullable<GeneratedScheduleResponse['time']>
> = true
const _scopeKeysExist: KeysExistIn<
  NonNullable<NestedScheduleResponse['scope']>,
  NonNullable<GeneratedScheduleResponse['scope']>
> = true
const _auditKeysExist: KeysExistIn<
  NonNullable<NestedScheduleResponse['audit']>,
  NonNullable<GeneratedScheduleResponse['audit']>
> = true
const _academicKeysExist: KeysExistIn<
  NonNullable<NestedScheduleResponse['academic']>,
  NonNullable<GeneratedScheduleResponse['academic']>
> = true

// 未使用変数扱いを避けつつ、番人が実際に評価されることを保つ。
export const SCHEDULE_RESPONSE_SHAPE_VERIFIED
  = _scheduleResponseKeysExist && _contentKeysExist && _timeKeysExist
    && _scopeKeysExist && _auditKeysExist && _academicKeysExist
