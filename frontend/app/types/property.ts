/**
 * 物件履歴台帳（F09.13）— パッケージ・文書 型定義
 *
 * バックエンド DTO:
 *  - PropertyWorkPackageResponse / PropertyWorkPackageSummaryResponse
 *  - PropertyWorkPackageRequest
 *  - PropertyWorkDocumentResponse / PropertyWorkDocumentRequest
 *  - ChangeStatusRequest / CategorySuggestionResponse
 * 設計書: docs/features/F09.13_property_history.md §4
 */

export type WorkType =
  | 'RENOVATION'
  | 'REPAIR'
  | 'INCIDENT'
  | 'INSPECTION'
  | 'DISASTER'
  | 'MEETING'
  | 'OTHER'

export type WorkPackageStatus =
  | 'PLANNED'
  | 'IN_PROGRESS'
  | 'COMPLETED'
  | 'CLOSED'
  | 'CANCELLED'

export type WorkPackageVisibility =
  | 'ADMINS_ONLY'
  | 'MEMBERS_ONLY'
  | 'MEMBERS_MASKED'
  | 'PUBLIC_MASKED'

export type DocumentKind =
  | 'MINUTES'
  | 'QUOTE'
  | 'CONTRACT'
  | 'REPORT'
  | 'PHOTO'
  | 'DRAWING'
  | 'INVOICE'
  | 'RECEIPT'
  | 'OTHER'

export type ScopeName = 'teams' | 'organizations'

export interface PropertyWorkDocumentResponse {
  id: number
  packageId: number
  sharedFileId: number
  documentKind: DocumentKind
  displayOrder: number
  note: string | null
  createdBy: number
  createdAt: string
  /** 一覧表示用に SharedFile から補完されることがある（バックエンド未実装時は undefined）。 */
  fileName?: string
}

export interface PropertyWorkDocumentRequest {
  sharedFileId: number
  documentKind: DocumentKind
  displayOrder?: number | null
  note?: string | null
}

export interface PropertyWorkPackagePermissions {
  canEdit: boolean
  canDelete: boolean
  canViewAmount: boolean
}

export interface PropertyWorkPackageResponse {
  id: number
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: string
  dwellingUnitId: number | null
  workType: WorkType
  category: string | null
  title: string
  description: string | null
  incidentId: number | null
  incidentDate: string | null
  incidentNarrative: string | null
  plannedStartDate: string | null
  plannedEndDate: string | null
  actualStartDate: string | null
  actualEndDate: string | null
  vendorId: number | null
  vendorNameSnapshot: string | null
  /** canViewAmount=false 時 null */
  estimatedAmount: number | null
  contractAmount: number | null
  actualAmount: number | null
  currency: string
  budgetTransactionId: number | null
  timelinePostId: number | null
  warrantyUntil: string | null
  isDisclosable: boolean
  visibility: WorkPackageVisibility
  status: WorkPackageStatus
  attachmentCount: number
  commentCount: number
  tags: string[] | null
  documents: PropertyWorkDocumentResponse[] | null
  createdBy: number
  updatedBy: number | null
  createdAt: string
  updatedAt: string
  version: number
  permissions: PropertyWorkPackagePermissions
}

export interface PropertyWorkPackageSummaryResponse {
  id: number
  workType: WorkType
  category: string | null
  title: string
  actualEndDate: string | null
  plannedStartDate: string | null
  plannedEndDate: string | null
  vendorId: number | null
  vendorNameSnapshot: string | null
  /** canViewAmount=false 時 null */
  actualAmount: number | null
  status: WorkPackageStatus
  canViewAmount: boolean
}

export interface PropertyWorkPackageRequest {
  dwellingUnitId?: number | null
  workType: WorkType
  category?: string | null
  title: string
  description?: string | null
  incidentId?: number | null
  incidentDate?: string | null
  incidentNarrative?: string | null
  plannedStartDate?: string | null
  plannedEndDate?: string | null
  actualStartDate?: string | null
  actualEndDate?: string | null
  vendorId?: number | null
  estimatedAmount?: number | null
  contractAmount?: number | null
  actualAmount?: number | null
  currency?: string
  budgetTransactionId?: number | null
  warrantyUntil?: string | null
  isDisclosable: boolean
  visibility: WorkPackageVisibility
  tags?: string[] | null
  /** 楽観的ロック用 version。POST 時は 0、PUT 時は最新値必須。 */
  version: number
}

export interface ChangeWorkPackageStatusRequest {
  status: WorkPackageStatus
}

export interface CategorySuggestionResponse {
  category: string
  count: number
}

export type WorkPackageExportFormat = 'pdf' | 'xlsx'

export interface WorkPackageListFilter {
  from?: string | null
  to?: string | null
  workType?: WorkType | null
  vendorId?: number | null
  status?: WorkPackageStatus | null
  page?: number
  size?: number
}
