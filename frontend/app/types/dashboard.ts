export type MinRole = 'PUBLIC' | 'SUPPORTER' | 'MEMBER'

export type ViewerRole = 'SYSTEM_ADMIN' | 'ADMIN' | 'DEPUTY_ADMIN' | 'MEMBER' | 'SUPPORTER' | 'PUBLIC'

export interface WidgetVisibilityActor {
  id: number
  display_name: string
}

export interface WidgetVisibilitySetting {
  widget_key: string
  min_role: MinRole
  is_default: boolean
  updated_by?: WidgetVisibilityActor
  updated_at?: string
}

export interface WidgetVisibilityResponse {
  data: {
    scope_type: 'TEAM' | 'ORGANIZATION'
    scope_id: number
    widgets: WidgetVisibilitySetting[]
  }
}

export interface WidgetVisibilityUpdate {
  widget_key: string
  min_role: MinRole
}

/**
 * F03.18 アクティビティフィードの変更差分 1 件（BE `ScheduleFeedDetail.FieldDiff` に対応）。
 *
 * `field` は既知語彙（title/startAt/endAt/isAllDay/location/status/description）を想定するが、
 * 未知の値も握りつぶさずそのまま表示する契約のため型は string のままにする（設計書 §3.2）。
 * `description` は値を載せず `changed: true` のみが来る。
 */
export interface ActivityDetailField {
  field: string
  before?: string | null
  after?: string | null
  changed?: boolean
}

/**
 * F03.18 アクティビティフィードの `detail`（BE `ScheduleFeedDetail` に対応）。
 * SCHEDULE 系4種別のみ非 null、既存7種別は常に null。
 * 表示用タイトルの正本は `summary` ではなくこの `title`（設計書 §4.1 裁定）。
 */
export interface ActivityFeedDetail {
  scheduleId?: number
  title?: string
  fields?: ActivityDetailField[]
  affectedCount?: number
}
