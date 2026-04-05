export type TodoStatus = 'OPEN' | 'IN_PROGRESS' | 'COMPLETED'
export type TodoPriority = 'LOW' | 'MEDIUM' | 'HIGH' | 'URGENT'
export type TodoScopeType = 'PERSONAL' | 'TEAM' | 'ORGANIZATION'

export interface TodoResponse {
  id: number
  scopeType: TodoScopeType
  scopeId: number
  projectId: number | null
  milestoneId: number | null
  title: string
  description: string | null
  status: TodoStatus
  priority: TodoPriority
  dueDate: string | null
  dueTime: string | null
  daysRemaining: number | null
  completedAt: string | null
  completedBy: { id: number; displayName: string } | null
  createdBy: { id: number; displayName: string }
  sortOrder: number
  assignees: TodoAssigneeResponse[]
  createdAt: string
  updatedAt: string
}

export interface TodoAssigneeResponse {
  id: number
  userId: number
  displayName: string
  avatarUrl: string | null
  assignedBy: number
  createdAt: string
}

export interface CreateTodoRequest {
  title: string
  description?: string
  projectId?: number
  milestoneId?: number
  priority?: TodoPriority
  dueDate?: string
  dueTime?: string
  sortOrder?: number
  assigneeIds?: number[]
}

export interface UpdateTodoRequest {
  title?: string
  description?: string
  projectId?: number | null
  milestoneId?: number | null
  priority?: TodoPriority
  dueDate?: string | null
  dueTime?: string | null
  sortOrder?: number
}

export interface TodoCommentResponse {
  id: number
  todoId: number
  userId: number
  displayName: string
  avatarUrl: string | null
  body: string
  createdAt: string
  updatedAt: string
}

export interface CreateTodoCommentRequest {
  body: string
}

export interface BulkStatusChangeRequest {
  todoIds: number[]
  status: TodoStatus
}
