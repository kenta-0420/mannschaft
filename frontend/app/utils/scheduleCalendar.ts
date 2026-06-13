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
}

/** EventDetailPanel.vue が期待する平坦な予定詳細型。 */
export interface FlatScheduleEvent {
  id: number
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
  }
}
