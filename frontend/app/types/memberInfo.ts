export type MemberInfoFieldType = 'TEXT' | 'PHONE' | 'EMAIL' | 'DATE'

export interface MemberInfoFieldResponse {
  id: number
  fieldName: string
  fieldType: MemberInfoFieldType
  isRequired: boolean
  isSensitive: boolean
  refreshIntervalMonths: number | null
  sortOrder: number
  isActive: boolean
}

export interface CreateMemberInfoFieldRequest {
  fieldName: string
  fieldType: MemberInfoFieldType
  isRequired: boolean
  isSensitive: boolean
  refreshIntervalMonths: number | null
  sortOrder?: number
}

export interface UpdateMemberInfoFieldRequest {
  fieldName?: string
  fieldType?: MemberInfoFieldType
  isRequired?: boolean
  isSensitive?: boolean
  refreshIntervalMonths?: number | null
  sortOrder?: number
}

export interface ReorderMemberInfoFieldsRequest {
  orders: Array<{ fieldId: number; sortOrder: number }>
}

export interface UpsertMemberInfoResponseRequest {
  responses: Array<{ fieldId: number; value: string | null }>
}

export interface MemberInfoResponseMeItem {
  fieldId: number
  fieldName: string
  fieldType: MemberInfoFieldType
  isRequired: boolean
  value: string | null
  confirmedAt: string | null
  isOverdue: boolean
  nextDueAt: string | null
}

export interface MemberInfoStatusResponse {
  totalMembers: number
  completedCount: number
  overdueCount: number
  members: MemberStatusItem[]
}

export interface MemberStatusItem {
  userId: number
  displayName: string
  responses: ResponseStatusItem[]
}

export interface ResponseStatusItem {
  fieldId: number
  fieldName: string
  value: string | null
  confirmedAt: string | null
  isOverdue: boolean
}
