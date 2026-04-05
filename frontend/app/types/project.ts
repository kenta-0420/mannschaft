export interface ProjectResponse {
  id: number
  title: string
  emoji: string | null
  color: string | null
  dueDate: string | null
  daysRemaining: number | null
  status: string
  progressRate: number
  totalTodos: number
  completedTodos: number
  milestones: { total: number; completed: number }
  createdBy: { id: number; displayName: string }
  createdAt: string
}

export interface CreateProjectRequest {
  title?: string
  description?: string
  emoji?: string
  color?: string
  dueDate?: string
  visibility?: string
}

export interface UpdateProjectRequest {
  title?: string
  description?: string
  emoji?: string
  color?: string
  dueDate?: string
  visibility?: string
  status?: string
}

export interface MilestoneResponse {
  id: number
  projectId: number
  title: string
  dueDate: string | null
  sortOrder: number
  completed: boolean
  completedAt: string | null
  createdAt: string
  updatedAt: string
}

export interface CreateMilestoneRequest {
  title?: string
  dueDate?: string
  sortOrder?: number
}

export interface UpdateMilestoneRequest {
  title?: string
  dueDate?: string
  sortOrder?: number
}
