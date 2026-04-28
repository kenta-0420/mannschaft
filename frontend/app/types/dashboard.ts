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
