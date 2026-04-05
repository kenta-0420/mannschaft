export type IncidentStatus = 'OPEN' | 'IN_PROGRESS' | 'RESOLVED' | 'CLOSED'
export type IncidentPriority = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'

export interface IncidentSummaryResponse {
  id: number
  title: string
  status: IncidentStatus
  priority: IncidentPriority
  slaDeadline: string | null
  isSlaBreached: boolean
  reportedBy: number
  createdAt: string
}

export interface IncidentResponse {
  id: number
  scopeType: string
  scopeId: number
  categoryId: number | null
  title: string
  description: string | null
  status: IncidentStatus
  priority: IncidentPriority
  slaDeadline: string | null
  isSlaBreached: boolean
  reportedBy: number
  workflowRequestId: number | null
  createdAt: string
  updatedAt: string
}

export interface IncidentCategoryResponse {
  id: number
  scopeType: string
  scopeId: number
  name: string
  description: string | null
  icon: string | null
  color: string | null
  slaHours: number | null
  isActive: boolean
  sortOrder: number
}

export interface ReportIncidentRequest {
  scopeType: string
  scopeId: number
  categoryId?: number
  title: string
  description?: string
  priority?: IncidentPriority
}

export interface UpdateIncidentRequest {
  title?: string
  description?: string
  priority?: IncidentPriority
}

export interface AssignIncidentRequest {
  assigneeId: number
  assigneeType: string
}

export interface CreateIncidentCategoryRequest {
  scopeType: string
  scopeId: number
  name: string
  description?: string
  icon?: string
  color?: string
  slaHours?: number
  sortOrder?: number
}

export interface UpdateIncidentCategoryRequest {
  name?: string
  description?: string
  icon?: string
  color?: string
  slaHours?: number
  isActive?: boolean
  sortOrder?: number
}

export interface ChangeStatusRequest {
  status: IncidentStatus
  comment?: string
}

export interface IncidentCommentResponse {
  id: number
  incidentId: number
  userId: number
  body: string
  createdAt: string
}

export interface PagedResponseIncidentSummaryResponse {
  data: IncidentSummaryResponse[]
  meta: {
    page: number
    size: number
    totalElements: number
    totalPages: number
  }
}
