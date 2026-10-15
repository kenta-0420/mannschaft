export type VisibilityTemplateRuleType =
  | 'TEAM_FRIEND_OF'
  | 'ORGANIZATION_MEMBER_OF'
  | 'TEAM_MEMBER_OF'
  | 'REGION_MATCH'
  | 'EXPLICIT_TEAM'
  | 'EXPLICIT_USER'
  | 'EXPLICIT_SOCIAL_PROFILE'

export interface VisibilityTemplateRule {
  id: number
  ruleType: VisibilityTemplateRuleType
  ruleTargetId: number | null
  ruleTargetText: string | null
  sortOrder: number
}

export interface VisibilityTemplateSummary {
  id: number
  name: string
  description: string | null
  iconEmoji: string | null
  isSystemPreset: boolean
  presetKey: string | null
  ruleCount: number
  createdAt: string
  updatedAt: string
}

export interface VisibilityTemplateDetail extends VisibilityTemplateSummary {
  rules: VisibilityTemplateRule[]
}

export interface VisibilityTemplateListResponse {
  userTemplates: VisibilityTemplateSummary[]
  systemPresets: VisibilityTemplateSummary[]
}

export interface CreateVisibilityTemplateRequest {
  name: string
  description?: string
  iconEmoji?: string
  rules: {
    ruleType: VisibilityTemplateRuleType
    ruleTargetId?: number
    ruleTargetText?: string
  }[]
}
