export type KbAccessLevel = 'ALL_MEMBERS' | 'ADMIN_ONLY' | 'CUSTOM'
export type KbPageStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'

export interface KbPageSummaryResponse {
  id: number
  parentId: number | null
  path: string
  depth: number
  title: string
  slug: string
  icon: string | null
  accessLevel: KbAccessLevel
  status: KbPageStatus
  viewCount: number
  version: number
  updatedAt: string
}

export interface KbPageResponse {
  id: number
  scopeType: string
  scopeId: number
  parentId: number | null
  path: string
  depth: number
  title: string
  slug: string
  body: string | null
  icon: string | null
  accessLevel: KbAccessLevel
  status: KbPageStatus
  viewCount: number
  createdBy: number | null
  lastEditedBy: number | null
  version: number
  createdAt: string
  updatedAt: string
}

export interface KbPageRevisionSummaryResponse {
  id: number
  pageId: number
  version: number
  editedBy: number | null
  createdAt: string
}

export interface CreateKbPageRequest {
  title: string
  slug?: string
  body?: string
  icon?: string
  accessLevel?: KbAccessLevel
  parentId?: number
  templateId?: number
}

export interface UpdateKbPageRequest {
  title?: string
  body?: string
  icon?: string
  accessLevel?: KbAccessLevel
  version: number
}

export interface MoveKbPageRequest {
  newParentId?: number | null
}
