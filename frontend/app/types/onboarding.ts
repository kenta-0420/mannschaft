export type OnboardingTemplateStatus = 'DRAFT' | 'ACTIVE' | 'ARCHIVED'
export type OnboardingProgressStatus = 'IN_PROGRESS' | 'COMPLETED' | 'SKIPPED'
export type OnboardingStepType = 'MANUAL' | 'URL' | 'FORM' | 'KNOWLEDGE_BASE' | 'PROFILE_COMPLETION'
export type OnboardingCompletionType = 'MANUAL' | 'AUTO_FORM' | 'AUTO_KB_VIEW' | 'AUTO_PROFILE' | 'ADMIN_OVERRIDE'
export type OnboardingPresetCategory = 'SPORTS' | 'RESIDENTIAL' | 'BUSINESS' | 'MEDICAL' | 'EDUCATION' | 'OTHER'

export interface OnboardingTemplate {
  id: number
  name: string
  description: string | null
  status: OnboardingTemplateStatus
  deadlineDays: number | null
  reminderDaysBefore: number | null
  isOrderEnforced: boolean
  isAdminNotifiedOnComplete: boolean
  isTimelinePostedOnComplete: boolean
  steps: OnboardingTemplateStep[]
  createdAt: string
  updatedAt: string
}

export interface OnboardingTemplateStep {
  id: number
  title: string
  description: string | null
  stepType: OnboardingStepType
  referenceId: number | null
  referenceUrl: string | null
  deadlineOffsetDays: number | null
  sortOrder: number
}

export interface OnboardingProgress {
  id: number
  templateId: number
  templateName: string
  userId: number
  userName: string
  status: OnboardingProgressStatus
  totalSteps: number
  completedSteps: number
  deadlineAt: string | null
  startedAt: string
  completedAt: string | null
  stepCompletions: OnboardingStepCompletion[]
}

export interface OnboardingStepCompletion {
  stepId: number
  stepTitle: string
  stepType: OnboardingStepType
  description: string | null
  referenceUrl: string | null
  completionType: OnboardingCompletionType | null
  completedAt: string | null
}

export interface OnboardingPreset {
  id: number
  name: string
  description: string | null
  category: OnboardingPresetCategory
  stepsJson: OnboardingPresetStep[]
  isActive: boolean
}

export interface OnboardingPresetStep {
  title: string
  description: string | null
  stepType: OnboardingStepType
  referenceUrl: string | null
}

export interface CreateTemplateRequest {
  name: string
  description?: string
  deadlineDays?: number
  reminderDaysBefore?: number
  isOrderEnforced: boolean
  isAdminNotifiedOnComplete: boolean
  isTimelinePostedOnComplete: boolean
  presetId?: number
  steps: CreateStepRequest[]
}

export interface CreateStepRequest {
  title: string
  description?: string
  stepType: OnboardingStepType
  referenceId?: number
  referenceUrl?: string
  deadlineOffsetDays?: number
}
